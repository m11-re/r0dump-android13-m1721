package com.android.r0dumpmanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.android.r0dumpmanager.R
import com.android.r0dumpmanager.app.R0dumpUiState
import com.android.r0dumpmanager.data.property.FusionDeviceSnapshot
import com.android.r0dumpmanager.domain.FusionConfigDraft
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.FusionConfigValidator
import com.android.r0dumpmanager.domain.ProfileId
import com.android.r0dumpmanager.domain.ProfileRegistry
import com.android.r0dumpmanager.ui.theme.R0dumpManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FusionActionToolbarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun disabledStateEnablesFromTopBarToggle() {
        var publishAndLaunchCalls = 0
        setToggle(
            state = state(snapshot = disabledSnapshot()),
            onPublishAndLaunch = { publishAndLaunchCalls++ },
        )

        composeTestRule
            .onNodeWithContentDescription(text(R.string.capture_toggle_enable))
            .performClick()

        assertEquals(1, publishAndLaunchCalls)
    }

    @Test
    fun enabledStateDisablesFromTopBarToggle() {
        var endSessionCalls = 0
        val draft = validDraft().copy(enabled = true)
        setToggle(
            state = state(FusionDeviceSnapshot.Enabled(draft, 42)),
            onEndSession = { endSessionCalls++ },
        )

        composeTestRule
            .onNodeWithContentDescription(text(R.string.capture_toggle_disable))
            .performClick()

        assertEquals(1, endSessionCalls)
    }

    @Test
    fun interruptedStateRecoversFromTopBarToggle() {
        var recoveryCalls = 0
        setToggle(
            state = state(FusionDeviceSnapshot.Interrupted(43)),
            onRecoverInterrupted = { recoveryCalls++ },
        )

        composeTestRule
            .onNodeWithContentDescription(text(R.string.recover_disabled_state))
            .performClick()

        assertEquals(1, recoveryCalls)
    }

    @Test
    fun validationToolbarNamesTheFieldAndExplainsHowToCorrectIt() {
        setToolbar(state = invalidTargetProcessState())

        composeTestRule.onNodeWithText(
            text(
                R.string.validation_issue_format,
                text(R.string.target_process),
                text(R.string.validation_target_process),
            ),
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun validDirtyDraftUsesToastAndTopBarPublishInsteadOfPersistentToolbar() {
        assertEquals(false, shouldShowActionToolbar(state(snapshot = disabledSnapshot())))
        assertEquals(false, shouldShowActionToolbar(dirtyState()))
        assertEquals(true, shouldShowDraftPublishAction(dirtyState()))

        assertEquals(false, shouldShowDraftChangedToast(consumedSerial = 0, currentSerial = 0))
        assertEquals(true, shouldShowDraftChangedToast(consumedSerial = 0, currentSerial = 1))
        assertEquals(false, shouldShowDraftChangedToast(consumedSerial = 1, currentSerial = 1))
    }

    @Test
    fun processModeReflectsMainAllAndCustomTargets() {
        val draft = validDraft()

        assertEquals(ProcessMode.MAIN, processModeOf(draft))
        assertEquals(ProcessMode.ALL, processModeOf(draft.copy(targetProcess = "")))
        assertEquals(ProcessMode.CUSTOM, processModeOf(draft.copy(targetProcess = "${draft.targetPackage}:remote")))
    }

    private fun setToolbar(
        state: R0dumpUiState,
        horizontal: Boolean = false,
    ) {
        composeTestRule.setContent {
            R0dumpManagerTheme {
                Box(Modifier.requiredWidth(if (horizontal) 900.dp else 400.dp)) {
                    FusionActionToolbar(
                        state = state,
                        horizontal = horizontal,
                    )
                }
            }
        }
    }

    private fun dirtyState(): R0dumpUiState {
        val state = state(snapshot = disabledSnapshot())
        val dirtyDraft = state.draft.copy(maxRecords = state.draft.maxRecords - 1)
        return state.copy(
            draft = dirtyDraft,
            validation = FusionConfigValidator.validate(dirtyDraft, true),
        )
    }

    private fun invalidTargetProcessState(): R0dumpUiState {
        val state = state(snapshot = disabledSnapshot())
        val invalidDraft = state.draft.copy(targetProcess = "${state.draft.targetPackage}:")
        return state.copy(
            draft = invalidDraft,
            validation = FusionConfigValidator.validate(invalidDraft, true),
        )
    }

    private fun setToggle(
        state: R0dumpUiState,
        onPublishAndLaunch: () -> Unit = {},
        onEndSession: () -> Unit = {},
        onRecoverInterrupted: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            R0dumpManagerTheme {
                CaptureStateToggle(
                    state = state,
                    onPublishAndLaunch = onPublishAndLaunch,
                    onEndSession = onEndSession,
                    onRecoverInterrupted = onRecoverInterrupted,
                    notificationsEnabled = true,
                    onRequestNotifications = {},
                )
            }
        }
    }

    private fun state(snapshot: FusionDeviceSnapshot): R0dumpUiState {
        val draft = snapshot.draftOrNull() ?: validDraft()
        return R0dumpUiState(
            draft = draft,
            deviceSnapshot = snapshot,
            validation = FusionConfigValidator.validate(draft, true),
            isForeground = true,
        )
    }

    private fun disabledSnapshot(): FusionDeviceSnapshot.Disabled {
        val draft = validDraft().copy(enabled = false)
        return FusionDeviceSnapshot.Disabled(draft, 40)
    }

    private fun validDraft(): FusionConfigDraft = ProfileRegistry.apply(
        ProfileId.COOLAPK_COOKIE_WALK,
        FusionConfigSchema.defaults().copy(
            runId = "20260830T000000Z-market-coolapk-cookie-walk",
            targetPackage = "com.coolapk.market",
            targetProcess = "com.coolapk.market",
        ),
    )

    private fun FusionDeviceSnapshot.draftOrNull(): FusionConfigDraft? = when (this) {
        is FusionDeviceSnapshot.Disabled -> draft
        is FusionDeviceSnapshot.Enabled -> draft
        else -> null
    }

    private fun text(resId: Int, vararg formatArgs: Any): String = context.getString(resId, *formatArgs)
}
