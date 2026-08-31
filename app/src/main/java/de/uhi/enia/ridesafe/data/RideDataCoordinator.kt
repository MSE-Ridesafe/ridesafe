package de.uhi.enia.ridesafe.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-ride coordination for operations that must see the database rows and sidecar files together.
 * Analysis holds one lock while publishing a ride's derived state. Backup takes every selected
 * ride's lock in numeric order while it reads the database and copies stable file snapshots.
 * Unrelated rides remain independent.
 */
internal object RideDataCoordinator {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withRides(
        rideIds: Collection<Long>,
        block: suspend () -> T,
    ): T {
        val orderedLocks = rideIds.distinct().sorted().map { locks.computeIfAbsent(it) { Mutex() } }
        return acquire(orderedLocks, 0, block)
    }

    private suspend fun <T> acquire(
        orderedLocks: List<Mutex>,
        index: Int,
        block: suspend () -> T,
    ): T =
        if (index == orderedLocks.size) {
            block()
        } else {
            orderedLocks[index].withLock { acquire(orderedLocks, index + 1, block) }
        }
}
