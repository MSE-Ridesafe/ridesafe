package de.uhi.enia.ridesafe.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.uhi.enia.ridesafe.data.db.RidesafeDatabase
import de.uhi.enia.ridesafe.data.entity.Refuel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefuelDaoTest {
    private lateinit var db: RidesafeDatabase
    private lateinit var dao: RefuelDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RidesafeDatabase::class.java).build()
        dao = db.refuelDao()
    }

    @After
    fun tearDown() = db.close()

    private fun refuel(timestamp: Long) =
        Refuel(
            vehicleId = 42,
            timestampEpochMs = timestamp,
            fuelAmountMilliliters = 38_400,
            totalPriceMinor = 6_720,
            currencyCode = "EUR",
            odometerMeters = 123_456_789_000,
        )

    @Test
    fun insertEmitsNewestFirstAndPreservesLargeValues() =
        runBlocking {
            dao.insert(refuel(100))
            dao.insert(refuel(200))

            val rows = dao.observeAll().first()
            assertEquals(listOf(200L, 100L), rows.map { it.timestampEpochMs })
            assertEquals(123_456_789_000, rows.first().odometerMeters)
        }

    @Test
    fun updatePreservesIdAndDoesNotInsertDuplicate() =
        runBlocking {
            val id = dao.insert(refuel(100))
            val existing = dao.getById(id)!!

            dao.update(existing.copy(timestampEpochMs = 300, totalPriceMinor = 7_000))

            val rows = dao.observeAll().first()
            assertEquals(1, rows.size)
            assertEquals(id, rows.single().id)
            assertEquals(300L, rows.single().timestampEpochMs)
            assertEquals(7_000, rows.single().totalPriceMinor)
        }

    @Test
    fun attachAndDetachOnlyChangeJourneyAnchor() =
        runBlocking {
            val id = dao.insert(refuel(100))
            val before = dao.getById(id)!!

            dao.setJourneyAnchor(id, 77)
            val attached = dao.getById(id)!!
            assertEquals(before.copy(journeyAnchorRideId = 77), attached)

            dao.clearJourneyAnchor(listOf(id))
            assertEquals(before, dao.getById(id))
        }

    @Test
    fun clearingRideAnchorsDetachesOnlyRefuelsFromThoseRides() =
        runBlocking {
            val first = dao.insert(refuel(100))
            val second = dao.insert(refuel(200))
            dao.setJourneyAnchor(first, 10)
            dao.setJourneyAnchor(second, 20)

            dao.clearJourneyAnchorsForRides(listOf(10))

            assertNull(dao.getById(first)!!.journeyAnchorRideId)
            assertEquals(20L, dao.getById(second)!!.journeyAnchorRideId)
        }
}
