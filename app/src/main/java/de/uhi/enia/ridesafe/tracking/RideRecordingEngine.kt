package de.uhi.enia.ridesafe.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideDao
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "RideRecording"

/**
 * Records a ride's GPS + motion stream (TRK-01/TRK-04). Implements [RideRecorder] so the
 * auto-tracking trigger can later drive it by setting [AutoTracking.recorder] — that wiring,
 * the foreground service (TRK-05) and the runtime permission flow (NFR-05) are a later round;
 * this is the standalone capture + persistence engine.
 *
 * Per [de.uhi.enia.ridesafe.data.Ride]: only a summary row lands in the DB; the full sample
 * stream is appended (gzip'd NDJSON) to a per-ride file so the DB stays lean. start/stop are
 * serialized through a command channel so they apply in call order regardless of caller thread.
 */
class RideRecordingEngine(
    private val appContext: Context,
    private val dao: RideDao = RidesafeDatabase.getInstance(appContext).rideDao(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val locationIntervalMs: Long = 1_000, // ~1 Hz GPS; calibration knob (NFR-08)
    private val motionSamplingPeriodUs: Int = 20_000, // ~50 Hz motion; calibration knob (NFR-08)
    private val motionBatchLatencyUs: Int = 5_000_000, // batch motion in the sensor FIFO to save power (NFR-08)
) : RideRecorder {
    private val json =
        Json {
            classDiscriminator = "ty"
            encodeDefaults = false
        }
    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(appContext)
    }
    private val sensorManager: SensorManager? by lazy {
        appContext.getSystemService(SensorManager::class.java)
    }

    private sealed interface Cmd

    private data class Start(
        val vehicleId: Long?,
    ) : Cmd

    private data class Stop(
        val done: CompletableDeferred<Unit>? = null,
    ) : Cmd

    private data object Recover : Cmd

    // Single consumer => start/stop/recover run sequentially, in order, off the caller thread.
    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    private var session: Session? = null

    init {
        scope.launch {
            for (cmd in commands) {
                runCatching {
                    when (cmd) {
                        is Start -> startSession(cmd.vehicleId)
                        is Stop -> stopSession()
                        Recover -> recover()
                    }
                }.onFailure { Log.e(TAG, "command $cmd failed", it) }
                if (cmd is Stop) cmd.done?.complete(Unit)
            }
        }
    }

    override fun onTripStart(vehicleId: Long?) {
        commands.trySend(Start(vehicleId))
    }

    override fun onTripEnd() {
        commands.trySend(Stop())
    }

    /** Stop recording and suspend until the ride is finalized — used by [RideRecordingService]. */
    suspend fun stopAndAwait() {
        val done = CompletableDeferred<Unit>()
        commands.send(Stop(done))
        done.await()
    }

    /** Dispose the engine's command consumer; call when the owner is done with it. */
    fun close() {
        scope.cancel()
    }

    /** Finalize rides left open by a crash/kill (NFR-06). Call once on app start. */
    fun recoverDanglingAsync() {
        commands.trySend(Recover)
    }

    private suspend fun startSession(vehicleId: Long?) {
        if (session != null) {
            Log.w(TAG, "start ignored: already recording")
            return
        }
        session = Session(vehicleId).also { it.start() }
    }

    private suspend fun stopSession() {
        val s = session
        if (s == null) {
            Log.w(TAG, "stop ignored: not recording")
            return
        }
        session = null
        s.stop()
    }

    private suspend fun recover() {
        val dir = ridesDir(appContext)
        for (ride in dao.dangling()) {
            runCatching {
                val file = File(dir, ride.sampleFile)
                val locations = if (file.exists()) readRideLocations(file) else emptyList()
                val stats = rideStatsOf(locations)
                val lastT = locations.lastOrNull()?.t
                val endedMs =
                    if (lastT != null) {
                        ride.startedAtEpochMs + ((lastT - ride.startedElapsedNanos) / 1_000_000).coerceAtLeast(0)
                    } else {
                        ride.startedAtEpochMs
                    }
                dao.finalize(
                    ride.id,
                    endedMs,
                    stats.startFix?.lat,
                    stats.startFix?.lon,
                    stats.endFix?.lat,
                    stats.endFix?.lon,
                    stats.maxSpeedMps,
                )
                Log.i(TAG, "recovered ride ${ride.id}: ${locations.size} fixes")
            }.onFailure { Log.e(TAG, "recover failed for ride ${ride.id}", it) }
        }
    }

    private inner class Session(
        private val vehicleId: Long?,
    ) {
        private val startedAtEpochMs = System.currentTimeMillis()
        private val startedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        private val fileName = "ride_$startedElapsedNanos.ndjson.gz"
        private val channel = Channel<RideSample>(Channel.UNLIMITED)
        private val stats = RideStats()
        private var rideId = 0L
        private lateinit var writerJob: Job
        private var handlerThread: HandlerThread? = null
        private var locationCallback: LocationCallback? = null
        private var sensorListener: SensorEventListener2? = null
        private var flushDone: CompletableDeferred<Unit>? = null

        suspend fun start() {
            val dir = ridesDir(appContext).apply { mkdirs() }
            val file = File(dir, fileName)
            writerJob = scope.launch { writeLoop(file) }

            val thread = HandlerThread("ride-recording").apply { start() }
            handlerThread = thread
            registerSensors(Handler(thread.looper))
            requestLocation(thread.looper)

            rideId =
                dao.insert(
                    Ride(
                        vehicleId = vehicleId,
                        startedAtEpochMs = startedAtEpochMs,
                        startedElapsedNanos = startedElapsedNanos,
                        sampleFile = fileName,
                    ),
                )
            Log.i(TAG, "recording ride $rideId -> $fileName (vehicle=$vehicleId)")
        }

        suspend fun stop() {
            // Stop the sources, but first drain the sensor FIFO so the last batched samples aren't
            // lost, then close the channel and drain the writer.
            locationCallback?.let { fusedClient.removeLocationUpdates(it) }
            flushAndUnregisterSensors()
            channel.close()
            writerJob.join()
            handlerThread?.quitSafely()

            // safe to read stats: writeLoop finished (join above)
            if (rideId != 0L) {
                dao.finalize(
                    rideId,
                    System.currentTimeMillis(),
                    stats.startFix?.lat,
                    stats.startFix?.lon,
                    stats.endFix?.lat,
                    stats.endFix?.lon,
                    stats.maxSpeedMps,
                )
            }
            Log.i(TAG, "stopped ride $rideId: maxSpeed=${stats.maxSpeedMps} mps")
        }

        // Flush the hardware FIFO so any batched motion still buffered is delivered into the channel
        // before we tear down. onFlushCompleted (per sensor) signals the drain; bounded so it can't
        // hang if a sensor never reports completion. ponytail: awaits the first completion, which is
        // enough since the FIFO is shared — the writer then drains whatever reached the channel.
        private suspend fun flushAndUnregisterSensors() {
            val sm = sensorManager ?: return
            val listener = sensorListener ?: return
            val done = CompletableDeferred<Unit>()
            flushDone = done
            if (sm.flush(listener)) {
                withTimeoutOrNull(2_000.milliseconds) { done.await() }
            }
            sm.unregisterListener(listener)
        }

        // Sole owner of the file handle; flushes on each GPS fix to bound crash loss to ~1s (NFR-06).
        // ponytail: gzip syncFlush survives an app crash (data is in the OS cache), not a power cut.
        private suspend fun writeLoop(file: File) =
            withContext(Dispatchers.IO) {
                BufferedWriter(
                    OutputStreamWriter(GZIPOutputStream(FileOutputStream(file), true), Charsets.UTF_8),
                ).use { w ->
                    for (sample in channel) {
                        w.write(json.encodeToString<RideSample>(sample))
                        w.newLine()
                        if (sample is LocationSample) {
                            stats.add(sample)
                            w.flush()
                        }
                    }
                    w.flush()
                }
            }

        private fun registerSensors(handler: Handler) {
            val sm = sensorManager ?: return
            val listener =
                object : SensorEventListener2 {
                    override fun onSensorChanged(e: SensorEvent) {
                        val kind =
                            when (e.sensor.type) {
                                Sensor.TYPE_ACCELEROMETER -> MotionSensor.ACCEL
                                Sensor.TYPE_GYROSCOPE -> MotionSensor.GYRO
                                Sensor.TYPE_ROTATION_VECTOR -> MotionSensor.ROTATION
                                else -> return
                            }
                        val v = e.values
                        channel.trySend(
                            MotionSample(
                                // Source time the sample was taken (elapsedRealtimeNanos base), not
                                // receipt time — preserves true spacing even when the FIFO batches.
                                t = e.timestamp,
                                sensor = kind,
                                x = v.getOrElse(0) { 0f },
                                y = v.getOrElse(1) { 0f },
                                z = v.getOrElse(2) { 0f },
                                w = if (kind == MotionSensor.ROTATION) v.getOrNull(3) else null,
                            ),
                        )
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {}

                    override fun onFlushCompleted(sensor: Sensor?) {
                        flushDone?.complete(Unit)
                    }
                }
            sensorListener = listener
            // TODO: Open Q2: degrade gracefully when a sensor is absent — just skip it, log it.
            for (type in intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR)) {
                val sensor = sm.getDefaultSensor(type)
                if (sensor == null) {
                    Log.w(TAG, "sensor type $type unavailable on this device")
                } else {
                    sm.registerListener(listener, sensor, motionSamplingPeriodUs, motionBatchLatencyUs, handler)
                }
            }
        }

        @SuppressLint("MissingPermission") // TODO: permission flow is a later round (NFR-05); guarded below
        private fun requestLocation(looper: Looper) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, locationIntervalMs).build()
            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (loc in result.locations) {
                            channel.trySend(
                                LocationSample(
                                    // Time of the fix (elapsedRealtimeNanos base), not receipt time —
                                    // immune to delivery lag; recommended for lining up with sensors.
                                    t = loc.elapsedRealtimeNanos,
                                    lat = loc.latitude,
                                    lon = loc.longitude,
                                    alt = if (loc.hasAltitude()) loc.altitude else 0.0,
                                    speed = if (loc.hasSpeed()) loc.speed else 0f,
                                    bearing = if (loc.hasBearing()) loc.bearing else 0f,
                                    accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f,
                                ),
                            )
                        }
                    }
                }
            locationCallback = callback
            try {
                fusedClient.requestLocationUpdates(request, callback, looper)
            } catch (e: SecurityException) {
                Log.e(TAG, "location permission not granted; GPS not recorded", e)
            }
        }
    }
}
