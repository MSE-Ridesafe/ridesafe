package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.util.UnitSystemSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class RefuelNumbersTest {
    @Test
    fun newestRefuelOdometerRoundsSafelyForVehicleMileage() {
        assertEquals(123_457, odometerMetersToVehicleMileageKm(123_456_789))
        assertEquals(123_456, odometerMetersToVehicleMileageKm(123_456_499))
        assertNull(odometerMetersToVehicleMileageKm(Long.MAX_VALUE))
    }

    @Test
    fun parsesCommaAndDotDecimals() {
        assertEquals(BigDecimal("38.4"), parseRefuelDecimal("38,4"))
        assertEquals(BigDecimal("38.4"), parseRefuelDecimal("38.4"))
    }

    @Test
    fun rejectsAmbiguousAndMalformedDecimals() {
        assertNull(parseRefuelDecimal("1,234.5"))
        assertNull(parseRefuelDecimal("1,2,3"))
        assertNull(parseRefuelDecimal("12 litres"))
    }

    @Test
    fun scalesFuelMoneyAndOdometerExactly() {
        assertEquals(38_400L, litersToMilliliters(BigDecimal("38.4")))
        assertEquals(6_720L, currencyUnitsToMinor(BigDecimal("67.20"), 2))
        assertEquals(12_345_000L, odometerToMeters(BigDecimal("12345"), UnitSystemSetting.METRIC))
        assertEquals(1_609L, odometerToMeters(BigDecimal.ONE, UnitSystemSetting.IMPERIAL))
    }

    @Test
    fun rejectsExcessPrecisionAndOverflow() {
        assertNull(litersToMilliliters(BigDecimal("1.0001")))
        assertNull(currencyUnitsToMinor(BigDecimal("1.001"), 2))
        assertNull(litersToMilliliters(BigDecimal("999999999999999999999")))
    }

    @Test
    fun derivesPricePerLiterWithoutBinaryFloatingPoint() {
        assertEquals(BigDecimal("1.750000"), pricePerLiter(6_720, 38_400, 2))
    }

    @Test
    fun editValuesUseStoredTimeAndGermanEditableDecimals() {
        val zone = ZoneId.of("Europe/Berlin")
        val timestamp =
            LocalDateTime
                .of(2026, 8, 20, 14, 15)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val initial =
            refuelFormInitialValues(
                de.uhi.enia.ridesafe.data.Refuel(
                    id = 9,
                    vehicleId = 4,
                    timestampEpochMs = timestamp,
                    fuelAmountMilliliters = 38_400,
                    totalPriceMinor = 6_720,
                    currencyCode = "EUR",
                    odometerMeters = 123_456_000,
                    stationAddress = "Shell Hildesheim",
                    stationSavedAddressId = 77,
                    isFullTank = true,
                ),
                UnitSystemSetting.METRIC,
                Locale.GERMANY,
                zone,
            )

        assertEquals(4L, initial.vehicleId)
        assertEquals(LocalDateTime.of(2026, 8, 20, 14, 15).toLocalDate().toEpochDay(), initial.dateEpochDay)
        assertEquals(14, initial.hour)
        assertEquals(15, initial.minute)
        assertEquals("38,4", initial.fuelText)
        assertEquals("67,2", initial.totalText)
        assertEquals("123456", initial.odometerText)
        assertEquals("Shell Hildesheim", initial.stationText)
        assertEquals(77L, initial.stationSavedAddressId)
        assertEquals(true, initial.fullTank)
    }

    @Test
    fun imperialOdometerEditTextRoundTripsToCanonicalMeters() {
        val meters = 198_678_000L
        val display = odometerMetersToDisplay(meters, UnitSystemSetting.IMPERIAL)
        assertEquals(meters, odometerToMeters(display, UnitSystemSetting.IMPERIAL))
    }
}
