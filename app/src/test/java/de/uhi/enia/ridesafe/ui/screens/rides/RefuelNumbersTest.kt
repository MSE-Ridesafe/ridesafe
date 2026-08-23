package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.util.UnitSystemSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class RefuelNumbersTest {
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
}
