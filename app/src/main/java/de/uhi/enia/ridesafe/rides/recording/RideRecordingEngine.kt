package de.uhi.enia.ridesafe.rides.recording

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
import kotlinx.coroutines.delay
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
 * Records a ride's GPS + motion stream (TRK-01/TRK-04), driven by [RideRecordingService].
 *
 * Per [Ride]: only a summary row lands in the DB; the full sample
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
    // Both default to the user's settings, read at construction — one engine is built per ride, so
    // a change applies from the next ride on. 0 disables either rule.
    private val reconnectGraceMs: Long = ReconnectGracePrefs.get(appContext).millis, // TRK-09/SET-10
    private val minRideMs: Long = MinRideLengthPrefs.get(appContext).millis, // TRK-10/SET-11
) {
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

    /**
     * The trip ended — provisionally. The ride keeps recording through the reconnect grace
     * (TRK-09); [done] completes with true once it is really finalized, false if the car came
     * back first and the service must stay alive.
     */
    private data class End(
        val done: CompletableDeferred<Boolean>? = null,
        val immediate: Boolean = false,
        val drop: Boolean = false,
    ) : Cmd

    /** The reconnect grace ran out: no car came back, so finalize and drop the tail. */
    private data object Expire : Cmd

    private data object Recover : Cmd

    // Single consumer => start/stop/recover run sequentially, in order, off the caller thread.
    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    private var session: Session? = null
    private var graceJob: Job? = null
    private var pendingEnd: CompletableDeferred<Boolean>? = null

    init {
        scope.launch {
            for (cmd in commands) {
                runCatching {
                    when (cmd) {
                        is Start -> startSession(cmd.vehicleId)
                        is End -> endSession(cmd.done, cmd.immediate, cmd.drop)
                        Expire -> expireGrace()
                        Recover -> recover()
                    }
                }.onFailure {
                    Log.e(TAG, "command $cmd failed", it)
                    // Never leave the service waiting on a failed end: it would stay foreground forever.
                    if (cmd is End || cmd is Expire) finishEnd(stopped = true)
                }
            }
        }
    }

    fun onTripStart(vehicleId: Long?) {
        commands.trySend(Start(vehicleId))
    }

    /**
     * End the trip and suspend until its fate is settled — used by [RideRecordingService].
     * Returns true once the ride is finalized, false if the same car reconnected within the
     * grace and recording continues, in which case the caller must keep the service running.
     * [immediate] skips the grace: a stop the user asked for by hand (TRK-07) is the end of the
     * ride, there is no car coming back. [drop] throws the ride away instead of logging it — the
     * driver was a passenger, or the trip was never meant to be in the logbook.
     */
    suspend fun endAndAwait(
        immediate: Boolean = false,
        drop: Boolean = false,
    ): Boolean {
        val done = CompletableDeferred<Boolean>()
        commands.send(End(done, immediate, drop))
        return done.await()
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
        val open = session
        if (open != null) {
            if (!open.isEnding) {
                Log.w(TAG, "start ignored: already recording")
                return
            }
            // The provisionally-ended ride is still recording (TRK-09). Same car back in time =>
            // one uninterrupted ride; a different car => close that one at its mark and start fresh.
            graceJob?.cancel()
            graceJob = null
            if (open.vehicleId == vehicleId) {
                open.rejoin()
                Log.i(TAG, "vehicle $vehicleId reconnected within the grace; continuing the ride")
                finishEnd(stopped = false)
                return
            }
            stopSession()
            finishEnd(stopped = false)
        }
        session =
            Session(vehicleId).also {
                it.start()
                RecordingStatus.onStarted(it.startedElapsedNanos, vehicleId)
            }
    }

    /** The trip ended: keep recording into the tail buffer and give the car [reconnectGraceMs] to return. */
    private suspend fun endSession(
        done: CompletableDeferred<Boolean>?,
        immediate: Boolean,
        drop: Boolean,
    ) {
        pendingEnd?.complete(false) // superseded: this waiter now owns the stop decision
        pendingEnd = done
        val s = session
        if (s == null) {
            Log.w(TAG, "end ignored: not recording")
            finishEnd(stopped = true)
            return
        }
        if (reconnectGraceMs <= 0 || immediate) {
            // Grace turned off (SET-10), or the user ended the ride by hand (TRK-07): either way
            // this is the end of the ride, full stop — no waiting for a car to come back.
            stopSession(drop)
            finishEnd(stopped = true)
            return
        }
        if (s.isEnding) return // duplicate end: keep the original mark and timer
        s.beginEnding()
        graceJob =
            scope.launch {
                delay(reconnectGraceMs.milliseconds)
                commands.send(Expire)
            }
    }

    /** Nobody reconnected: finalize the ride at its mark, dropping everything recorded after it. */
    private suspend fun expireGrace() {
        val s = session
        if (s == null || !s.isEnding) return // a reconnect won the race with the timer
        stopSession()
        finishEnd(stopped = true)
    }

    private fun finishEnd(stopped: Boolean) {
        pendingEnd?.complete(stopped)
        pendingEnd = null
    }

    private suspend fun stopSession(drop: Boolean = false) {
        val s = session
        if (s == null) {
            Log.w(TAG, "stop ignored: not recording")
            return
        }
        session = null
        RecordingStatus.onStopped()
        graceJob?.cancel()
        graceJob = null
        s.stop(drop)
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
                if (minRideMs > 0 && endedMs - ride.startedAtEpochMs < minRideMs) {
                    // Same rule as a clean stop (TRK-10): a killed ride that never got going is
                    // not worth a logbook row either.
                    dao.deleteById(ride.id)
                    file.delete()
                    Log.i(TAG, "discarded dangling ride ${ride.id}: too short")
                    return@runCatching
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
        val vehicleId: Long?,
    ) {
        private val startedAtEpochMs = System.currentTimeMillis()
        val startedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        private val fileName = "ride_$startedElapsedNanos.ndjson.gz"
        private val channel = Channel<RideSample>(Channel.UNLIMITED)
        private val stats = RideStats()
        private val tail = RideTail()
        private var endedAtEpochMs: Long? = null
        private var rideId = 0L
        private lateinit var writerJob: Job
        private var handlerThread: HandlerThread? = null
        private var locationCallback: LocationCallback? = null
        private var sensorListener: SensorEventListener2? = null
        private var flushDone: CompletableDeferred<Unit>? = null

        /** True while the trip has provisionally ended and the ride is recording into its tail. */
        val isEnding: Boolean
            get() = tail.isHolding

        /** Mark where the ride ends if nobody reconnects; recording carries on into [tail]. */
        fun beginEnding() {
            endedAtEpochMs = System.currentTimeMillis()
            tail.begin(SystemClock.elapsedRealtimeNanos())
        }

        /** The same car came back: the tail is part of the ride and the end mark is void. */
        fun rejoin() {
            endedAtEpochMs = null
            tail.rejoin()
        }

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

        suspend fun stop(drop: Boolean = false) {
            // Stop the sources, but first drain the sensor FIFO so the last batched samples aren't
            // lost, then close the channel and drain the writer.
            locationCallback?.let { fusedClient.removeLocationUpdates(it) }
            flushAndUnregisterSensors()
            channel.close()
            writerJob.join()
            handlerThread?.quitSafely()

            if (rideId == 0L) return
            // The mark, not now: the tail was dropped, so the ride ends where it stopped — which
            // is also what makes the length a real one, unpadded by the reconnect grace.
            val endedAt = endedAtEpochMs ?: System.currentTimeMillis()
            val lengthMs = endedAt - startedAtEpochMs
            if (drop) {
                // The driver threw this one away from the car screen; no outcome to report back,
                // they watched it happen.
                discard(lengthMs, "dropped by the driver")
                RecordingStatus.onFinished(null)
                return
            }
            if (minRideMs > 0 && lengthMs < minRideMs) {
                discard(lengthMs, "shorter than the minimum")
                RecordingStatus.onFinished(RideOutcome.TooShort(lengthMs))
                return
            }
            // safe to read stats: writeLoop finished (join above)
            dao.finalize(
                rideId,
                endedAt,
                stats.startFix?.lat,
                stats.startFix?.lon,
                stats.endFix?.lat,
                stats.endFix?.lon,
                stats.maxSpeedMps,
            )
            RecordingStatus.onFinished(RideOutcome.Saved(lengthMs, rideId))
            Log.i(TAG, "stopped ride $rideId: maxSpeed=${stats.maxSpeedMps} mps")
        }

        /** Too short to be a trip (TRK-10): drop the row and its samples instead of logging it. */
        private suspend fun discard(
            lengthMs: Long,
            why: String,
        ) {
            dao.deleteById(rideId)
            File(ridesDir(appContext), fileName).delete()
            Log.i(TAG, "discarded ride $rideId ($why): ${lengthMs}ms long")
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
                    val write = { sample: RideSample ->
                        w.write(json.encodeToString<RideSample>(sample))
                        w.newLine()
                        if (sample is LocationSample) {
                            // Only what is written counts, so a dropped tail leaves the endpoints
                            // and top speed as they were at the mark.
                            stats.add(sample)
                            w.flush()
                        }
                    }
                    for (sample in channel) {
                        tail.accept(sample, write)
                    }
                    tail.drain(write) // held samples stay unwritten if the grace never reopened
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
