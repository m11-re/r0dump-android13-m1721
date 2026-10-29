package com.android.r0dumpmanager.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.android.r0dumpmanager.data.audit.MutationAuditEntry
import com.android.r0dumpmanager.data.draft.SavedFusionDraft
import com.android.r0dumpmanager.data.export.ArtifactExportResult
import com.android.r0dumpmanager.data.packageinfo.TargetApplication
import com.android.r0dumpmanager.data.property.FusionDeviceSnapshot
import com.android.r0dumpmanager.domain.FusionConfigDraft
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.FusionConfigValidator
import com.android.r0dumpmanager.domain.ProfileId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R0dumpManagerViewModelTest {
    private val application: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test
    fun publishWritesDisabledSnapshotWithoutLaunchOrActiveNotification() {
        val dependencies = FakeDependencies()
        val viewModel = configuredViewModel(dependencies)

        viewModel.publish()

        assertEquals(false, dependencies.publishedDraft?.enabled)
        assertEquals(1, dependencies.publishCalls)
        assertEquals(0, dependencies.launchCalls)
        assertEquals(0, dependencies.showNotificationCalls)
        assertEquals(1, dependencies.cancelNotificationCalls)
        assertTrue(viewModel.uiState.value.deviceSnapshot is FusionDeviceSnapshot.Disabled)
    }

    @Test
    fun publishAndLaunchWritesEnabledSnapshotAndStartsTarget() {
        val dependencies = FakeDependencies()
        val viewModel = configuredViewModel(dependencies)

        viewModel.publishAndLaunch()

        assertEquals(true, dependencies.publishedDraft?.enabled)
        assertEquals(1, dependencies.publishCalls)
        assertEquals(1, dependencies.forceStopCalls)
        assertEquals(1, dependencies.launchCalls)
        assertEquals(1, dependencies.showNotificationCalls)
        assertEquals(0, dependencies.cancelNotificationCalls)
        assertTrue(viewModel.uiState.value.deviceSnapshot is FusionDeviceSnapshot.Enabled)
        assertEquals(listOf("stop:com.example.target", "publish", "notify", "launch:com.example.target"), dependencies.events)
    }

    @Test
    fun managerOwnedRunIdIsGeneratedInsteadOfReportedAsUserError() {
        val dependencies = FakeDependencies()
        val viewModel = configuredViewModel(dependencies)

        viewModel.setDraft(validDraft().copy(runId = ""))

        assertFalse(viewModel.uiState.value.validation.errors.containsKey("run_id"))

        viewModel.publish()

        assertTrue(FusionConfigValidator.isValidRunId(dependencies.publishedDraft?.runId.orEmpty()))
    }

    @Test
    fun repeatedMutationIsRejectedWhileTransactionIsInFlight() {
        val gate = CompletableDeferred<Unit>()
        val dependencies = FakeDependencies().apply { publishGate = gate }
        val viewModel = configuredViewModel(dependencies)

        viewModel.publishAndLaunch()
        viewModel.publishAndLaunch()

        assertEquals(1, dependencies.publishCalls)
        assertEquals(MutationOperation.Publishing, viewModel.uiState.value.operation)

        gate.complete(Unit)

        assertEquals(1, dependencies.launchCalls)
        assertEquals(MutationOperation.Idle, viewModel.uiState.value.operation)
    }

    @Test
    fun mutationRunsWithoutAuthenticationSetup() {
        val dependencies = FakeDependencies()
        val viewModel = R0dumpManagerViewModel(
            application = application,
            savedStateHandle = SavedStateHandle(),
            dependencies = dependencies,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.setForeground(true)
        viewModel.setDraft(validDraft())

        viewModel.publish()

        assertEquals(1, dependencies.publishCalls)
        assertTrue(dependencies.publishedDraft != null)
        assertTrue(viewModel.uiState.value.canMutate)
    }

    @Test
    fun publishFailureDoesNotLaunchOrShowActiveNotification() {
        val dependencies = FakeDependencies().apply {
            publishFailure = IllegalStateException("property_write_failed")
        }
        val viewModel = configuredViewModel(dependencies)

        viewModel.publishAndLaunch()

        assertEquals(1, dependencies.publishCalls)
        assertEquals(0, dependencies.launchCalls)
        assertEquals(0, dependencies.showNotificationCalls)
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("property_write_failed"))
    }

    @Test
    fun stopFailureDoesNotDisableEnabledSnapshot() {
        val activeDraft = validDraft().copy(enabled = true)
        val dependencies = FakeDependencies().apply {
            snapshot = FusionDeviceSnapshot.Enabled(activeDraft, 2L)
            forceStopFailure = IllegalStateException("target_still_running")
        }
        val viewModel = configuredViewModel(dependencies)

        viewModel.endSession()

        assertEquals(1, dependencies.forceStopCalls)
        assertEquals(0, dependencies.disableCalls)
        assertEquals(0, dependencies.cancelNotificationCalls)
        assertTrue(viewModel.uiState.value.deviceSnapshot is FusionDeviceSnapshot.Enabled)
    }

    @Test
    fun disableNewProcessesCommitsBeforeCancellingNotificationWithoutStoppingTarget() {
        val activeDraft = validDraft().copy(enabled = true)
        val dependencies = FakeDependencies().apply {
            snapshot = FusionDeviceSnapshot.Enabled(activeDraft, 2L)
        }
        val viewModel = configuredViewModel(dependencies)

        viewModel.disableNewProcesses()

        assertEquals(0, dependencies.forceStopCalls)
        assertEquals(1, dependencies.disableCalls)
        assertEquals(listOf("disable", "cancel-notification"), dependencies.events)
        assertTrue(viewModel.uiState.value.deviceSnapshot is FusionDeviceSnapshot.Disabled)
    }

    @Test
    fun interruptedSnapshotCanOnlyRecoverToDisabledEvenGeneration() {
        val dependencies = FakeDependencies().apply {
            snapshot = FusionDeviceSnapshot.Interrupted(5L)
        }
        val viewModel = configuredViewModel(dependencies)

        viewModel.publish()
        assertEquals(0, dependencies.publishCalls)

        viewModel.recoverInterrupted()

        assertEquals(1, dependencies.recoverCalls)
        assertEquals(6L, (viewModel.uiState.value.deviceSnapshot as FusionDeviceSnapshot.Disabled).generation)
    }

    @Test
    fun concurrentAndInvalidStatesAreReadOnly() {
        val editableState = R0dumpUiState(isOwnerUser = true)

        assertFalse(editableState.copy(deviceSnapshot = FusionDeviceSnapshot.ConcurrentChange).canEdit)
        assertFalse(editableState.copy(deviceSnapshot = FusionDeviceSnapshot.Invalid("schema")).canEdit)
        assertTrue(editableState.copy(deviceSnapshot = FusionDeviceSnapshot.Unconfigured).canEdit)
    }

    @Test
    fun resetRestoresCompleteDefaultsWhilePreservingTargetIdentity() {
        val dependencies = FakeDependencies()
        val viewModel = configuredViewModel(dependencies)
        val custom = validDraft().copy(
            maxTotalBytes = 536_870_912,
            maxDexBytes = 134_217_728,
            bufferPoolBytes = 268_435_456,
            maxCodeItemBytes = 1_048_576,
            manifestEnabled = false,
            walkEnabled = false,
            walkMaxClasses = 500,
            walkMaxMethods = 2_000,
            forceInvokeEnabled = false,
            anrProtectionEnabled = false,
        )
        viewModel.setDraft(custom)

        viewModel.restoreDefaults()

        val reset = viewModel.uiState.value.draft
        val defaults = FusionConfigSchema.defaults()
        assertEquals(custom.runId, reset.runId)
        assertEquals(custom.targetPackage, reset.targetPackage)
        assertEquals(custom.targetProcess, reset.targetProcess)
        assertEquals(defaults.maxTotalBytes, reset.maxTotalBytes)
        assertEquals(defaults.maxDexBytes, reset.maxDexBytes)
        assertEquals(defaults.bufferPoolBytes, reset.bufferPoolBytes)
        assertEquals(defaults.maxCodeItemBytes, reset.maxCodeItemBytes)
        assertEquals(defaults.manifestEnabled, reset.manifestEnabled)
        assertEquals(defaults.walkEnabled, reset.walkEnabled)
        assertEquals(defaults.walkMaxClasses, reset.walkMaxClasses)
        assertEquals(defaults.walkMaxMethods, reset.walkMaxMethods)
        assertEquals(defaults.forceInvokeEnabled, reset.forceInvokeEnabled)
        assertEquals(defaults.anrProtectionEnabled, reset.anrProtectionEnabled)
        assertEquals(ProfileId.DEFAULTS, viewModel.uiState.value.selectedProfile)
    }

    @Test
    fun exportLatestUsesSelectedPackageAndPublishesHelperResult() {
        val dependencies = FakeDependencies()
        val viewModel = configuredViewModel(dependencies)

        viewModel.exportLatest()

        assertEquals(listOf("export:com.example.target"), dependencies.exportEvents)
        assertEquals("/sdcard/Downloads/r0dump-exports/com.example.target/run-1.zip", viewModel.uiState.value.lastExport?.archivePath)
        assertEquals(MutationOperation.Idle, viewModel.uiState.value.operation)
    }

    private fun configuredViewModel(dependencies: FakeDependencies): R0dumpManagerViewModel {
        val viewModel = R0dumpManagerViewModel(
            application = application,
            savedStateHandle = SavedStateHandle(),
            dependencies = dependencies,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.setForeground(true)
        viewModel.setDraft(validDraft())
        return viewModel
    }

    private fun validDraft() = FusionConfigSchema.defaults().copy(
        enabled = false,
        runId = "run-1",
        targetPackage = "com.example.target",
        targetProcess = "com.example.target",
        capturePointMask = 1uL shl 39,
        forceInvokeEnabled = false,
    )
}

private class FakeDependencies : R0dumpManagerDependencies {
    var snapshot: FusionDeviceSnapshot = FusionDeviceSnapshot.Unconfigured
    var publishedDraft: FusionConfigDraft? = null
    var publishCalls = 0
    var disableCalls = 0
    var forceStopCalls = 0
    var launchCalls = 0
    var showNotificationCalls = 0
    var cancelNotificationCalls = 0
    var recoverCalls = 0
    var publishFailure: Throwable? = null
    var forceStopFailure: Throwable? = null
    var publishGate: CompletableDeferred<Unit>? = null
    val events = mutableListOf<String>()
    val exportEvents = mutableListOf<String>()
    private var persistedDraft: SavedFusionDraft? = null

    override suspend fun readSnapshot() = snapshot

    override suspend fun publish(draft: FusionConfigDraft, expectedGeneration: Long): FusionDeviceSnapshot {
        publishCalls++
        events += "publish"
        publishGate?.await()
        publishFailure?.let { throw it }
        publishedDraft = draft
        val generation = expectedGeneration + 2L
        return if (draft.enabled) {
            FusionDeviceSnapshot.Enabled(draft, generation)
        } else {
            FusionDeviceSnapshot.Disabled(draft, generation)
        }.also { snapshot = it }
    }

    override suspend fun disable(expectedGeneration: Long): FusionDeviceSnapshot {
        disableCalls++
        events += "disable"
        val draft = (snapshot as FusionDeviceSnapshot.Enabled).draft.copy(enabled = false)
        return FusionDeviceSnapshot.Disabled(draft, expectedGeneration + 2L).also { snapshot = it }
    }

    override suspend fun recoverInterrupted(): FusionDeviceSnapshot {
        recoverCalls++
        events += "recover"
        val generation = (snapshot as FusionDeviceSnapshot.Interrupted).generation + 1L
        return FusionDeviceSnapshot.Disabled(FusionConfigSchema.defaults(), generation).also { snapshot = it }
    }

    override fun loadInstalledApplications(): List<TargetApplication> = emptyList()
    override fun launch(packageName: String): Result<Unit> = Result.success(Unit).also {
        launchCalls++
        events += "launch:$packageName"
    }
    override suspend fun forceStopAndWait(packageName: String): Result<Unit> {
        forceStopCalls++
        events += "stop:$packageName"
        return forceStopFailure?.let(Result.Companion::failure) ?: Result.success(Unit)
    }
    override suspend fun exportLatest(packageName: String): ArtifactExportResult {
        exportEvents += "export:$packageName"
        return ArtifactExportResult(
            runId = "run-1",
            archivePath = "/sdcard/Downloads/r0dump-exports/$packageName/run-1.zip",
            sha256 = "a".repeat(64),
            remoteArtifactCount = 3,
            recordCount = 3,
            verified = 1,
            repaired = 1,
            partial = 1,
            usableOutputs = 2,
            codeItemsBackfilled = 1,
        )
    }
    override fun savePersistedDraft(draft: FusionConfigDraft, generation: Long, profile: ProfileId) {
        persistedDraft = SavedFusionDraft(draft, generation, profile)
    }
    override fun restorePersistedDraft(generation: Long) = persistedDraft?.takeIf { it.generation == generation }
    override fun clearPersistedDraft() { persistedDraft = null }
    override fun appendAudit(entry: MutationAuditEntry) = Unit
    override fun showActiveSession(targetPackage: String, runId: String) {
        showNotificationCalls++
        events += "notify"
    }
    override fun cancelActiveSession() {
        cancelNotificationCalls++
        events += "cancel-notification"
    }
}
