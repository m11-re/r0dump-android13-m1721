package com.android.r0dumpmanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.android.r0dumpmanager.R
import com.android.r0dumpmanager.domain.CapturePointRegistry
import com.android.r0dumpmanager.ui.theme.R0dumpManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CapturePointGroupTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allGroupsAreCollapsedByDefault() {
        assertEquals(emptySet<Int>(), defaultExpandedCaptureGroupIndexes())
    }

    @Test
    fun activeObservationGroupIncludesNterpEntryPoint() {
        val activeGroup = CapturePointRegistry.points.filter { it.bit in 39..42 }
        assertEquals(listOf(39, 40, 41, 42), activeGroup.map { it.bit })
    }

    @Test
    fun collapsedHeaderShowsSelectionSummaryAndExpansionAction() {
        var toggleCalls = 0
        val title = context.getString(R.string.group_dex_establishment)
        composeTestRule.setContent {
            R0dumpManagerTheme {
                CapturePointGroupHeader(
                    title = title,
                    selectedCount = 2,
                    totalCount = 8,
                    expanded = false,
                    onToggle = { toggleCalls++ },
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.capture_group_selected_count, 2, 8))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.expand_capture_group, title))
            .performClick()

        assertEquals(1, toggleCalls)
    }
}
