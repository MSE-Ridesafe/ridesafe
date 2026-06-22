package de.uhi.enia.ridesafe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the non-trivial bit: [VehicleDao.addVehicle] keeping exactly one primary (GAR-07). */
@RunWith(AndroidJUnit4::class)
class VehicleDaoTest {
    private lateinit var db: RidesafeDatabase
    private lateinit var dao: VehicleDao

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    RidesafeDatabase::class.java,
                ).build()
        dao = db.vehicleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun vehicle(name: String) =
        Vehicle(
            name = name,
            make = "Make",
            model = "Model",
            licensePlate = "P",
            fuelType = FuelType.PETROL,
            mileageKm = 0,
        )

    @Test
    fun firstVehicleBecomesPrimaryEvenWhenNotRequested() =
        runBlocking {
            dao.addVehicle(vehicle("a"), makePrimary = false)
            val all = dao.observeAll().first()
            assertEquals(1, all.count { it.isPrimary })
        }

    @Test
    fun newPrimaryUnsetsThePrevious() =
        runBlocking {
            dao.addVehicle(vehicle("a"), makePrimary = true)
            dao.addVehicle(vehicle("b"), makePrimary = true)
            val all = dao.observeAll().first()
            assertEquals(2, all.size)
            assertEquals(1, all.count { it.isPrimary })
            assertEquals("b", all.single { it.isPrimary }.name)
        }

    @Test
    fun deletingPrimaryPromotesAnotherVehicle() =
        runBlocking {
            dao.addVehicle(vehicle("a"), makePrimary = true)
            dao.addVehicle(vehicle("b"), makePrimary = false)
            val primary = dao.observeAll().first().single { it.isPrimary }

            dao.deleteVehicle(primary)

            val all = dao.observeAll().first()
            assertEquals(1, all.size)
            assertEquals(1, all.count { it.isPrimary })
        }
}
