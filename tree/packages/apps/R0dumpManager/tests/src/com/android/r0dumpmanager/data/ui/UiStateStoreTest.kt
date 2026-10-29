package com.android.r0dumpmanager.data.ui

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UiStateStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearState() {
        UiStateStore(context).clear()
    }

    @Test
    fun clickStateSurvivesStoreRecreation() {
        val store = UiStateStore(context)
        assertNull(store.read(UiStateKey.CAPTURE_GROUPS))

        store.write(UiStateKey.DESTINATION, "LIMITS")
        store.write(UiStateKey.CAPTURE_GROUPS, "1,4")
        store.write(UiStateKey.BUDGET_GROUPS, "CAPACITY")
        store.write(UiStateKey.ADVANCED_GROUPS, "MANIFEST,FORCE_ANR")

        val restored = UiStateStore(context)
        assertEquals("LIMITS", restored.read(UiStateKey.DESTINATION))
        assertEquals("1,4", restored.read(UiStateKey.CAPTURE_GROUPS))
        assertEquals("CAPACITY", restored.read(UiStateKey.BUDGET_GROUPS))
        assertEquals("MANIFEST,FORCE_ANR", restored.read(UiStateKey.ADVANCED_GROUPS))
    }
}
