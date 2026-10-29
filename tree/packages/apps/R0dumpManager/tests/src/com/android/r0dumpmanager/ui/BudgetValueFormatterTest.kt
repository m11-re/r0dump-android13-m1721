package com.android.r0dumpmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetValueFormatterTest {
    @Test fun wholeMiBValuesDoNotExposeBytesToTheEditor() {
        assertEquals("192", mibTextOf(201_326_592))
        assertEquals("32", mibTextOf(33_554_432))
        assertEquals("1", mibTextOf(1_048_576))
    }

    @Test fun decimalMiBInputSerializesToRoundedBytes() {
        assertEquals(1_572_864L, bytesFromMiBInput("1.5"))
        assertEquals(262_144L, bytesFromMiBInput("0,25"))
        assertEquals(1L, bytesFromMiBInput("0.000001"))
    }

    @Test fun malformedOrOverPreciseInputIsRejected() {
        assertNull(bytesFromMiBInput(""))
        assertNull(bytesFromMiBInput("1 MiB"))
        assertNull(bytesFromMiBInput("1.0000001"))
        assertNull(bytesFromMiBInput("1e2"))
    }
}
