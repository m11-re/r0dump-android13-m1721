package com.android.r0dumpmanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.android.r0dumpmanager.R
import com.android.r0dumpmanager.domain.WalkMode
import com.android.r0dumpmanager.ui.theme.R0dumpManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AdvancedSettingsGroupTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun collapsedHeaderShowsStateSummaryAndExpansionAction() {
        var toggleCalls = 0
        val title = context.getString(R.string.walk)
        composeTestRule.setContent {
            R0dumpManagerTheme {
                SettingsGroupHeader(
                    title = title,
                    summary = context.getString(R.string.setting_enabled),
                    expanded = false,
                    onToggle = { toggleCalls++ },
                )
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.setting_enabled)).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.expand_settings_group, title))
            .performClick()

        assertEquals(1, toggleCalls)
    }

    @Test
    fun enumDropdownShowsCurrentSelectionAndChangesValue() {
        var selectedMode: WalkMode? = null
        composeTestRule.setContent {
            R0dumpManagerTheme {
                EnumDropdownSelector(
                    title = context.getString(R.string.walk_mode),
                    choices = WalkMode.entries,
                    selected = WalkMode.LOADED_ONLY,
                    enabled = true,
                    onSelected = { selectedMode = it },
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.walk_mode_loaded_only))
            .assertIsDisplayed()
            .performClick()
        composeTestRule
            .onNodeWithText(context.getString(R.string.walk_mode_dex_names))
            .performClick()

        assertEquals(WalkMode.DEX_NAMES, selectedMode)
    }
}
