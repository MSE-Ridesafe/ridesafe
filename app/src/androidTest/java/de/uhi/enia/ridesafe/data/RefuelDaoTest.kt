package de.uhi.enia.ridesafe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun refuel(timestamp: Long, address: String? = null) =
        Refuel(
            vehicleId = 42,
            timestampEpochMs = timestamp,
            fuelAmountMilliliters = 38_400,
            totalPriceMinor = 6_720,
            currencyCode = "EUR",
            odometerMeters = 123_456_789_000,
            stationAddress = address,
        )

    @Test
    fun insertEmitsNewestFirstAndPreservesNullableAddressAndLargeValues() =
        runBlocking {
            dao.insert(refuel(100, null))
            dao.insert(refuel(200, "Station"))

            val rows = dao.observeAll().first()
            assertEquals(listOf(200L, 100L), rows.map { it.timestampEpochMs })
            assertEquals(123_456_789_000, rows.first().odometerMeters)
            assertNull(rows.last().stationAddress)
        }
}
