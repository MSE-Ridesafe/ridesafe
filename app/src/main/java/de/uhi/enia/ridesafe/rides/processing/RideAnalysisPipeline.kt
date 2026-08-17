package de.uhi.enia.ridesafe.rides.processing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideAnalysisState
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.RideSample
import de.uhi.enia.ridesafe.rides.recording.forEachSampleInTimeOrder
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

private const val TAG = "RideAnalysis"

/**
 * How many rides are analysed at once. Each worker is a whole ride's pipeline — file read, Kalman
 * filter, detection — so this is a CPU knob, not an I/O one. Raise it and a large backfill finishes
 * sooner at the cost of cores the UI wants; lower it and the first launch after a version bump takes
 * proportionally longer. Three leaves room to scroll the Logbook while 75 rides are re-analysed.
 */
private const val MAX_PARALLEL_RIDES = 3

/** How often the sample loop checks for cancellation — ~every 80 ms of recorded driving. */
private const val CANCELLATION_CHECK_SAMPLES = 4096L

/**
 * A filtered fix and its position in the stream, as the count of motion samples that had gone out
 * before it. That position cannot be recovered from the fix's own timestamp: [TrackFilter] holds a
 * fix back until the run it belongs to is long enough to believe and then releases the whole backlog
 * at once, and where fixes and motion share a timestamp the reader's ordering between them is
 * arbitrary. Counting motion samples pins both down — the reader delivers them identically on every
 * read of the same file — so a later pass replays the exact stream the filtering pass produced.
 */
data class ReleasedFix(
    val afterMotionSamples: Long,
    val fix: LocationSample,
)

/** A consumer of one ride's samples in time order — the streaming unit every stage builds on. */
fun interface SampleSink {
    fun onSample(sample: RideSample)
}

/**
 * What the stages of one ride's analysis hand each other. Concrete fields rather than a keyed
 * blackboard: a new stage's output is one more field here, checked by the compiler, with no key
 * registry to keep in sync and no casting at the read end.
 *
 * Created per ride and touched by one coroutine, like the stages themselves.
 */
class RideAnalysisContext(
    val appContext: Context,
    val ride: Ride,
) {
    /**
     * The ride's Kalman-filtered GPS, produced by the pass driver rather than by any one stage. Null
     * until a pass has actually filtered — distinct from an empty list, which is a filter that ran
     * and kept nothing. Conflating the two would re-filter a ride with no usable track on every pass.
     */
    var filteredFixes: List<ReleasedFix>? = null

    /** Whether the ride recorded any acceleration at all — without it there is nothing to detect. */
    var hasAccel = false

    /** Unit vector of the vehicle's forward axis in the device frame; null when unrecoverable. */
    var forwardAxis: DoubleArray? = null

    /** Distance + average speed off the filtered track. */
    var metrics: RideMetrics? = null

    /** The ride's driving events, freshly detected or loaded from storage. */
    var events: List<RideEvent>? = null
}

/**
 * One step of a ride's analysis (ANL-01/ANL-02). Steps run strictly in order for a given ride and
 * exchange results through [RideAnalysisContext], so adding one — a safety score, a fuel estimate —
 * means implementing this and naming it in [analysisPasses], not touching the runner.
 *
 * Instances are created per ride and touched by exactly one coroutine, so a stage may hold whatever
 * mutable state it likes: that confinement is what makes analysing several rides at once safe. The
 * rule to preserve is the same one the detector already documents — nothing shared at file scope
 * except immutable config.
 */
interface RideStage {
    /** Stable key stored in `ride_analysis`; renaming one needs a migration to match. */
    val id: String

    /** Bump when this stage's output changes meaning — every ride is then re-derived on next launch. */
    val version: Int

    /**
     * Stages whose output this one is derived from. Drives invalidation in both directions: a
     * dependency that re-runs forces this stage to re-run, and this stage running forces its
     * dependencies to be available — restored via [load] when they are current, re-derived otherwise.
     */
    val dependsOn: List<String> get() = emptyList()

    /**
     * Whether this stage needs the ride's raw samples. False for a stage that works purely off what
     * earlier stages left on the context — its pass then costs no file read at all, which is the
     * point of versioning stages separately.
     */
    val needsSamples: Boolean get() = true

    /** Whether a current output can be restored by [load] instead of being derived again. */
    val restorable: Boolean get() = false

    /**
     * A sink to attach to this stage's pass, or null when the stage needs no per-sample callback
     * (it may still read [RideAnalysisContext.filteredFixes], which the pass driver fills).
     */
    fun sink(ctx: RideAnalysisContext): SampleSink? = null

    /**
     * Publish results onto [ctx] and persist them. Runs once the stage's pass is over, in
     * declaration order, so a later stage in the same pass sees an earlier one's output.
     *
     * Reaching the end counts as a result, including "there was nothing here" — the stage is stamped
     * and not revisited until its version moves. A stage that genuinely could not run should throw:
     * the ride is then left unstamped and retried on a later launch.
     */
    suspend fun finish(ctx: RideAnalysisContext)

    /** Restore a current output onto [ctx]; false means it must be derived after all. */
    suspend fun load(ctx: RideAnalysisContext): Boolean = false
}

/**
 * The pipeline, as passes. Every stage in a pass shares one read of the ride's sample file, and the
 * passes run in order — so this list is both the execution order and the read count.
 *
 * Two passes rather than one, because the vehicle's forward axis is a whole-ride statistic and
 * nothing can be split into longitudinal and lateral before it is known. Everything downstream of
 * *that* constraint is already shared: the GPS is Kalman-filtered once, in pass one, and pass two
 * reuses those fixes instead of filtering again.
 *
 * ponytail: a stage that needs its own pass adds a list here; one that fits an existing pass costs
 * nothing. A pure-derivation stage (the safety score, next) belongs in a third pass with
 * [RideStage.needsSamples] false, so bumping it re-derives from stored events with no file read.
 */
private fun analysisPasses(db: RidesafeDatabase): List<List<RideStage>> =
    listOf(
        listOf(RouteStage(db), RideEndpointStage(db), ForwardAxisStage()),
        listOf(RideEventStage(db)),
    )

/**
 * One ride the pipeline is working through. [progress] runs 0..1 across every file pass the ride's
 * plan calls for, so a ride needing only detection fills as fast as one needing the whole pipeline.
 */
data class RideAnalysisJob(
    val rideId: Long,
    val startedAtEpochMs: Long,
    val progress: Float = 0f,
)

/**
 * What the pipeline is currently chewing through, for the UI to render. [jobs] shrinks as rides
 * finish while [total] stays put, which is what makes "32 of 72" countable; both reset to empty
 * once the run ends. A ride that failed or had nothing to derive still leaves [jobs] — from the
 * queue's point of view it is dealt with either way.
 */
data class RideAnalysisProgress(
    val jobs: List<RideAnalysisJob> = emptyList(),
    val total: Int = 0,
) {
    val completed: Int get() = total - jobs.size

    /** True while there is anything left to show; the status bar and queue key off this. */
    val running: Boolean get() = jobs.isNotEmpty()
}

/**
 * Runs each ride's analysis steps as one ordered pipeline, and separate rides in parallel.
 *
 * The order matters and used to be missing: route filtering and event detection ran as two
 * independent backfills over two different ride sets, so a single ride's sample file was read three
 * times and its GPS Kalman-filtered three times, by passes that then competed for the disk. Here a
 * ride is one unit of work — filter, then axis, then detection, then whatever comes later — and the
 * parallelism moved to where it belongs, across rides.
 */
class RideAnalysisPipeline(
    private val appContext: Context,
    private val db: RidesafeDatabase,
) {
    private val analysisDao = db.rideAnalysisDao()

    private val _progress = MutableStateFlow(RideAnalysisProgress())

    /** Live view of the queue (ANL-03), for the Rides status bar and the queue screen. */
    val progress: StateFlow<RideAnalysisProgress> = _progress.asStateFlow()

    // Whole rides, not sample batches, are the unit of concurrency; the work is CPU-bound once the
    // file is in page cache, so Default fits better than IO's elastic pool.
    //
    // The cap is a permit held for a ride's whole pipeline, not a limitedParallelism dispatcher.
    // A dispatcher only bounds threads, and a ride releases its thread at every suspending DAO call
    // — so all of them would start, run pass one, and park between passes, each holding its filtered
    // fixes. Seventy rides half-done at once is a lot of retained track for no gain; this way three
    // are genuinely in flight and the rest have not begun.
    private val slots = Semaphore(MAX_PARALLEL_RIDES)

    /**
     * Analyse every ride with a stage that is missing or out of date, up to [MAX_PARALLEL_RIDES] at
     * a time. Idempotent and cheap when there is nothing to do: one query, and rides whose stages are
     * all current are dropped before any file is opened.
     */
    suspend fun runPending() {
        pruneStaleRoutes(appContext)
        val stages = analysisPasses(db).flatten()
        val stamped = analysisDao.all().groupBy { it.rideId }
        val pending =
            db
                .rideDao()
                .processable()
                .filter { ride ->
                    val stored = stamped[ride.id].orEmpty().associate { it.stage to it.version }
                    stages.any { stored[it.id] != it.version }
                }
                // Newest first: the ride a user is most likely to open is the one they just drove,
                // and it is the top of the Logbook. Row order is otherwise the table's, oldest first.
                .sortedByDescending { it.startedAtEpochMs }
        if (pending.isEmpty()) return

        Log.i(TAG, "backfill: ${pending.size} ride(s), $MAX_PARALLEL_RIDES at a time")
        val startedMs = SystemClock.elapsedRealtime()
        _progress.value =
            RideAnalysisProgress(
                jobs = pending.map { RideAnalysisJob(it.id, it.startedAtEpochMs) },
                total = pending.size,
            )
        try {
            supervisorScope {
                pending
                    .map { ride ->
                        async(Dispatchers.Default) { slots.withPermit { runCatchingRide(ride) } }
                    }.awaitAll()
            }
        } finally {
            // Also on cancellation: a queue left standing would tell the UI work is still going on.
            _progress.value = RideAnalysisProgress()
        }
        Log.i(TAG, "backfill: done in ${(SystemClock.elapsedRealtime() - startedMs) / 1000.0} s")
    }

    /** One ride failing (a corrupt file, a full disk) must not take the rest of the batch with it. */
    private suspend fun runCatchingRide(ride: Ride) {
        try {
            runRide(ride)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ride ${ride.id}: analysis failed; leaving it stale for a later run", e)
        } finally {
            // However it went — done, skipped or failed — this ride is off the queue.
            _progress.update { it.copy(jobs = it.jobs.filterNot { job -> job.rideId == ride.id }) }
        }
    }

    /** Publish how far one ride has got, for the queue UI. Called from several workers at once. */
    private fun report(
        rideId: Long,
        fraction: Float,
    ) = _progress.update { state ->
        state.copy(
            jobs = state.jobs.map { if (it.rideId == rideId) it.copy(progress = fraction.coerceIn(0f, 1f)) else it },
        )
    }

    private suspend fun runRide(ride: Ride) {
        val file = File(ridesDir(appContext), ride.sampleFile)
        if (!file.exists()) return // nothing to derive from; stays unstamped and is retried later

        val passes = analysisPasses(db)
        val stored = analysisDao.forRide(ride.id).associate { it.stage to it.version }
        val ctx = RideAnalysisContext(appContext, ride)
        val stages = passes.flatten()
        var plan = planStages(stages, stored)
        if (plan.run.isEmpty()) return

        // Restore what is current before any pass starts, so a stage that turns out not to be
        // restorable after all (its sidecar deleted behind our back) can still be promoted to run.
        val loaded = plan.load.filter { it.load(ctx) }
        if (loaded.size != plan.load.size) {
            val lost = plan.load - loaded.toSet()
            plan = planStages(stages, stored - lost.map { it.id }.toSet())
            plan.load.filterNot { it in loaded }.forEach { it.load(ctx) }
        }

        val ran = mutableListOf<String>()
        // Time spent in the passes, not wall time for the ride: between passes a ride waits for a
        // worker slot while other rides stream, and counting that would report a short ride as slow.
        var passMs = 0L
        // Progress is measured in file passes, the only part whose cost scales with ride length, so
        // it has to be known before the first one starts.
        val plannedReads = passes.count { pass -> pass.any { it in plan.run && it.needsSamples } }
        var readsDone = 0
        for (pass in passes) {
            val scheduled = pass.filter { it in plan.run }
            if (scheduled.isEmpty()) continue
            if (scheduled.any { it.needsSamples }) {
                val startedMs = SystemClock.elapsedRealtime()
                ctx.filteredFixes =
                    streamSamples(ride.id, file, scheduled.mapNotNull { it.sink(ctx) }, ctx.filteredFixes) {
                        report(ride.id, (readsDone + it) / plannedReads)
                    }
                readsDone++
                report(ride.id, readsDone.toFloat() / plannedReads)
                passMs += SystemClock.elapsedRealtime() - startedMs
            }
            for (stage in scheduled) {
                stage.finish(ctx)
                analysisDao.stamp(RideAnalysisState(ride.id, stage.id, stage.version))
                ran += stage.id
            }
        }
        Log.i(TAG, "ride ${ride.id}: ${ran.joinToString(" -> ")} in $passMs ms of passes")
    }
}

/** Stages to derive, and stages whose current output only needs restoring. */
internal data class Plan(
    val run: List<RideStage>,
    val load: List<RideStage>,
)

/**
 * Decide what a ride needs, from what is stamped for it. Pure — no I/O — so it can be reasoned
 * about and tested on its own.
 *
 * Forward sweep: a stage is stale if its stamp is missing or from another build, and staleness
 * flows downstream, since a stage derived from a changed one is changed too. Backward sweep: a
 * stage that will run needs its inputs present, so each dependency is either restored (current
 * and [RideStage.restorable]) or dragged into the run. Declaration order is dependency order, so
 * one pass each way is enough.
 */
internal fun planStages(
    stages: List<RideStage>,
    stored: Map<String, Int>,
): Plan {
    val run = LinkedHashSet<String>()
    for (stage in stages) {
        if (stored[stage.id] != stage.version || stage.dependsOn.any { it in run }) run += stage.id
    }
    val load = LinkedHashSet<String>()
    for (stage in stages.reversed()) {
        if (stage.id !in run) continue
        for (dependency in stage.dependsOn) {
            if (dependency in run) continue
            val dep = stages.first { it.id == dependency }
            if (dep.restorable) load += dep.id else run += dep.id
        }
    }
    return Plan(stages.filter { it.id in run }, stages.filter { it.id in load })
}

/**
 * Read one ride's samples and push them at [sinks] in time order, with the GPS already
 * Kalman-filtered. Returns the filtered fixes, for the next pass to reuse.
 *
 * Pass [cachedFixes] empty and the Kalman filter runs, keeping every fix it accepts. Pass those
 * fixes back on a later pass and the file's own [LocationSample]s are dropped and the cached ones
 * merged into the motion stream in their place — which is what makes this one Kalman run per ride
 * rather than one per pass. Sinks cannot tell the two apart: they see the same fixes at the same
 * points in the stream either way. Cheap to hold, too — fixes arrive about once a second, against
 * motion's 50 Hz across three sensors.
 */
internal suspend fun streamSamples(
    rideId: Long,
    file: File,
    sinks: List<SampleSink>,
    cachedFixes: List<ReleasedFix>? = null,
    onProgress: ((Float) -> Unit)? = null,
): List<ReleasedFix>? {
    // Nothing to do only when there is no one to feed *and* the fixes are already in hand. A pass
    // can legitimately have no sinks and still need this: a stage that reads the filtered track
    // without wanting a per-sample callback (see [RideStage.needsSamples]) has nowhere else to get
    // it from, and returning early would hand it an empty track and a silently wrong answer.
    if (sinks.isEmpty() && cachedFixes != null) return cachedFixes
    // A long uninterrupted loop over millions of samples, so cancellation is checked by hand — a
    // queued ride the user cancels would otherwise run to completion with nobody waiting for it.
    val coroutineContext = currentCoroutineContext()
    var seen = 0L
    val emit = { sample: RideSample ->
        if (++seen % CANCELLATION_CHECK_SAMPLES == 0L) coroutineContext.ensureActive()
        sinks.forEach { it.onSample(sample) }
    }

    if (cachedFixes != null) {
        var next = 0
        var motionSeen = 0L
        forEachSampleInTimeOrder(file, onProgress = onProgress) { sample ->
            // The file's raw fixes are superseded by the filtered ones, which slot back in wherever
            // the filtering pass released them — before the motion sample they were released ahead of.
            if (sample !is LocationSample) {
                while (next < cachedFixes.size && cachedFixes[next].afterMotionSamples <= motionSeen) {
                    emit(cachedFixes[next++].fix)
                }
                motionSeen++
                emit(sample)
            }
        }
        while (next < cachedFixes.size) emit(cachedFixes[next++].fix)
        return cachedFixes
    }

    val filter = TrackFilter()
    val fixes = ArrayList<ReleasedFix>()
    var raw = 0
    var worstAccuracy = 0f
    var motionSeen = 0L
    forEachSampleInTimeOrder(file, onProgress = onProgress) { sample ->
        when (sample) {
            is LocationSample -> {
                raw++
                if (sample.accuracy > worstAccuracy) worstAccuracy = sample.accuracy
                // A fix may be released several fixes after its own arrival, if it was held back
                // waiting for its run to be corroborated; what is recorded is where it came out.
                filter.update(sample) {
                    fixes.add(ReleasedFix(motionSeen, it))
                    emit(it)
                }
            }

            else -> {
                motionSeen++
                emit(sample)
            }
        }
    }
    filter.finish()
    // Worst reported accuracy is the tell for *why* a ride's track was bad. The fused provider keeps
    // emitting when GNSS is gone, from Wi-Fi and cell towers, and those fixes land hundreds of meters
    // to kilometers away — but nearly always say so. A large drop count next to a modest worst
    // accuracy is the other case: fixes that were wrong without admitting it.
    Log.i(TAG, "ride $rideId: kept ${fixes.size}/$raw fixes, worst accuracy $worstAccuracy m")
    return fixes
}
