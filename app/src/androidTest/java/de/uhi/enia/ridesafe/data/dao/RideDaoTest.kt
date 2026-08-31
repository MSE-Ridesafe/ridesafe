package de.uhi.enia.ridesafe.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.uhi.enia.ridesafe.data.db.RidesafeDatabase
import de.uhi.enia.ridesafe.data.entity.Ride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the one thing the logbook depends on: [RideDao.observeFinished] hiding the ride in progress. */
@RunWith(AndroidJUnit4::class)
class RideDaoTest {
    private lateinit var db: RidesafeDatabase
    private lateinit var dao: RideDao

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    RidesafeDatabase::class.java,
                ).build()
        dao = db.rideDao()
    }

    @After
    fun tearDown() = db.close()

    private fun ride(
        startedAtEpochMs: Long,
        endedAtEpochMs: Long?,
    ) = Ride(
        startedAtEpochMs = startedAtEpochMs,
        startedElapsedNanos = startedAtEpochMs * 1_000_000,
        endedAtEpochMs = endedAtEpochMs,
        sampleFile = "$startedAtEpochMs.ndjson.gz",
    )

    @Test
    fun observeFinishedSkipsTheRideStillRecording() =
        runBlocking {
            dao.insert(ride(startedAtEpochMs = 1_000, endedAtEpochMs = 2_000))
            dao.insert(ride(startedAtEpochMs = 3_000, endedAtEpochMs = null))

            val finished = dao.observeFinished().first()

            assertEquals(listOf(1_000L), finished.map { it.startedAtEpochMs })
            assertEquals(2, dao.observeAll().first().size)
        }

    @Test
    fun observeFinishedPicksUpTheRideOnceItIsFinalized() =
        runBlocking {
            val id = dao.insert(ride(startedAtEpochMs = 3_000, endedAtEpochMs = null))
            assertEquals(emptyList<Long>(), dao.observeFinished().first().map { it.id })

            dao.finalize(
                id = id,
                endedAtEpochMs = 4_000,
                startLat = null,
                startLon = null,
                endLat = null,
                endLon = null,
                maxSpeedMps = 0.0,
            )

            assertEquals(listOf(id), dao.observeFinished().first().map { it.id })
        }
}
