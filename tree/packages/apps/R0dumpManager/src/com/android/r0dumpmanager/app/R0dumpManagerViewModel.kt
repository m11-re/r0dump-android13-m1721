package com.android.r0dumpmanager.app

import android.app.Application
import android.content.Context
import android.os.UserHandle
import com.android.r0dumpmanager.mcp.McpBridgeService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.android.r0dumpmanager.R
import com.android.r0dumpmanager.data.audit.MutationAction
import com.android.r0dumpmanager.data.audit.MutationAuditEntry
import com.android.r0dumpmanager.data.audit.MutationAuditLog
import com.android.r0dumpmanager.data.draft.DraftStateStore
import com.android.r0dumpmanager.data.draft.SavedFusionDraft
import com.android.r0dumpmanager.data.export.ArtifactExportRepository
import com.android.r0dumpmanager.data.export.ArtifactExportResult
import com.android.r0dumpmanager.data.packageinfo.PackageRepository
import com.android.r0dumpmanager.data.packageinfo.TargetApplication
import com.android.r0dumpmanager.data.property.FusionDeviceSnapshot
import com.android.r0dumpmanager.data.property.FusionPropertyRepository
import com.android.r0dumpmanager.domain.CapturePointRegistry
import com.android.r0dumpmanager.domain.FusionConfigDraft
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.FusionConfigValidator
import com.android.r0dumpmanager.domain.FusionValidationResult
import com.android.r0dumpmanager.domain.ProfileId
import com.android.r0dumpmanager.domain.ProfileRegistry
import com.android.r0dumpmanager.notification.ActiveSessionNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface MutationOperation {
    data object Idle : MutationOperation
    data object Publishing : MutationOperation
    data object Exporting : MutationOperation
    data class Failed(val reason: String) : MutationOperation
}

// run_id is validated on publish, not while editing: an empty/partial run_id is
// normal in a draft, so drop that error from the UI-facing validation result.
private fun validateForUi(draft: FusionConfigDraft): FusionValidationResult = FusionValidationResult(
    FusionConfigValidator.validate(draft, true).errors - "run_id",
)

data class R0dumpUiState(
    val draft: FusionConfigDraft = FusionConfigSchema.defaults(),
    val deviceSnapshot: FusionDeviceSnapshot = FusionDeviceSnapshot.Unconfigured,
    val applications: List<TargetApplication> = emptyList(),
    val selectedProfile: ProfileId = ProfileId.DEFAULTS,
    val validation: FusionValidationResult = validateForUi(FusionConfigSchema.defaults()),
    val isOwnerUser: Boolean = UserHandle.myUserId() == UserHandle.USER_SYSTEM,
    val isForeground: Boolean = false,
    val operation: MutationOperation = MutationOperation.Idle,
    val message: String? = null,
    val messageSerial: Long = 0,
    val draftNoticeSerial: Long = 0,
    val draftNoticeShown: Boolean = false,
    val lastExport: ArtifactExportResult? = null,
    val mcpEnabled: Boolean = false,
) {
    val isInterrupted get() = deviceSnapshot is FusionDeviceSnapshot.Interrupted
    val canEdit get() = isOwnerUser && deviceSnapshot !is FusionDeviceSnapshot.Interrupted &&
        deviceSnapshot !is FusionDeviceSnapshot.ConcurrentChange && deviceSnapshot !is FusionDeviceSnapshot.Invalid &&
        operation is MutationOperation.Idle
    val canMutate get() = isOwnerUser && isForeground && operation is MutationOperation.Idle
    val isDirty get() = draft != deviceSnapshot.draftOrNull()
    val maskText get() = FusionConfigSchema.maskText(draft.capturePointMask)
}
private fun FusionDeviceSnapshot.draftOrNull() = when (this) { is FusionDeviceSnapshot.Enabled -> draft; is FusionDeviceSnapshot.Disabled -> draft; else -> null }
private fun FusionDeviceSnapshot.generationOrNull() = when (this) { is FusionDeviceSnapshot.Enabled -> generation; is FusionDeviceSnapshot.Disabled -> generation; is FusionDeviceSnapshot.Interrupted -> generation; else -> null }
private fun R0dumpUiState.message(value: String) = copy(message = value, messageSerial = messageSerial + 1)
private fun R0dumpUiState.withUserDraft(draft: FusionConfigDraft, profile: ProfileId): R0dumpUiState {
    val changed = draft != this.draft
    val dirty = draft != deviceSnapshot.draftOrNull()
    val notify = changed && dirty && !draftNoticeShown
    return copy(
        draft = draft,
        selectedProfile = profile,
        validation = validateForUi(draft),
        draftNoticeSerial = if (notify) draftNoticeSerial + 1 else draftNoticeSerial,
        draftNoticeShown = if (dirty) draftNoticeShown || notify else false,
    )
}

interface R0dumpManagerDependencies {
    suspend fun readSnapshot(): FusionDeviceSnapshot
    suspend fun publish(draft: FusionConfigDraft, expectedGeneration: Long): FusionDeviceSnapshot
    suspend fun disable(expectedGeneration: Long): FusionDeviceSnapshot
    suspend fun recoverInterrupted(): FusionDeviceSnapshot
    fun loadInstalledApplications(): List<TargetApplication>
    fun launch(packageName: String): Result<Unit>
    suspend fun forceStopAndWait(packageName: String): Result<Unit>
    suspend fun exportLatest(packageName: String): ArtifactExportResult
    fun savePersistedDraft(draft: FusionConfigDraft, generation: Long, profile: ProfileId)
    fun restorePersistedDraft(generation: Long): SavedFusionDraft?
    fun clearPersistedDraft()
    fun appendAudit(entry: MutationAuditEntry)
    fun showActiveSession(targetPackage: String, runId: String)
    fun cancelActiveSession()
}

private class AndroidR0dumpManagerDependencies(application: Application) : R0dumpManagerDependencies {
    private val properties = FusionPropertyRepository()
    private val packages = PackageRepository(application)
    private val drafts = DraftStateStore(application)
    private val audit = MutationAuditLog(application)
    private val notifier = ActiveSessionNotifier(application)
    private val exports = ArtifactExportRepository()

    override suspend fun readSnapshot() = properties.read()
    override suspend fun publish(draft: FusionConfigDraft, expectedGeneration: Long) = properties.publish(draft, expectedGeneration)
    override suspend fun disable(expectedGeneration: Long) = properties.disable(expectedGeneration)
    override suspend fun recoverInterrupted() = properties.recoverInterrupted()
    override fun loadInstalledApplications() = packages.loadInstalledApplications()
    override fun launch(packageName: String) = packages.launch(packageName)
    override suspend fun forceStopAndWait(packageName: String) = packages.forceStopAndWait(packageName)
    override suspend fun exportLatest(packageName: String) = exports.exportLatest(packageName)
    override fun savePersistedDraft(draft: FusionConfigDraft, generation: Long, profile: ProfileId) = drafts.save(draft, generation, profile)
    override fun restorePersistedDraft(generation: Long) = drafts.restore(generation)
    override fun clearPersistedDraft() = drafts.clear()
    override fun appendAudit(entry: MutationAuditEntry) = audit.append(entry)
    override fun showActiveSession(targetPackage: String, runId: String) = notifier.show(targetPackage, runId)
    override fun cancelActiveSession() = notifier.cancel()
}

class R0dumpManagerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val dependencies: R0dumpManagerDependencies = AndroidR0dumpManagerDependencies(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, SavedStateHandle())

    private val inFlight = AtomicBoolean(false); private val _uiState = MutableStateFlow(R0dumpUiState()); val uiState: StateFlow<R0dumpUiState> = _uiState.asStateFlow()
    init { refresh(); restoreMcpState() }
    private fun mcpPrefs() = getApplication<Application>().getSharedPreferences("r0dump_mcp", Context.MODE_PRIVATE)
    /** Start/stop the embedded MCP bridge service and remember the choice. */
    fun setMcpEnabled(enabled: Boolean) {
        mcpPrefs().edit().putBoolean("enabled", enabled).apply()
        _uiState.update { it.copy(mcpEnabled = enabled) }
        val application = getApplication<Application>()
        if (enabled) McpBridgeService.start(application) else McpBridgeService.stop(application)
    }
    private fun restoreMcpState() {
        val enabled = mcpPrefs().getBoolean("enabled", false)
        _uiState.update { it.copy(mcpEnabled = enabled) }
        if (enabled) McpBridgeService.start(getApplication<Application>())
    }
    fun refresh() = refreshInternal(preserveDraft = true)
    /** Reload only the installed-app list (e.g. after installing a new target), leaving the draft untouched. */
    fun rescanApplications() = viewModelScope.launch(ioDispatcher) {
        if (!_uiState.value.isOwnerUser) return@launch
        val applications = dependencies.loadInstalledApplications()
        _uiState.update { it.copy(applications = applications) }
    }
    private fun refreshInternal(preserveDraft: Boolean) = viewModelScope.launch(ioDispatcher) {
        val snapshot = dependencies.readSnapshot()
        val applications = if (_uiState.value.isOwnerUser) dependencies.loadInstalledApplications() else emptyList()
        val generation = snapshot.generationOrNull() ?: 0L
        val restored = if (preserveDraft) restoredDraft(generation) else null
        val loadedDraft = restored ?: snapshot.draftOrNull() ?: if (preserveDraft) _uiState.value.draft else FusionConfigSchema.defaults()
        val profile = if (restored != null) restoredProfile(generation) else if (snapshot.draftOrNull() != null) ProfileId.CUSTOM else ProfileId.DEFAULTS
        val draft = ensureManagedRunId(loadedDraft, profile)
        if (draft != loadedDraft || restored == null && snapshot.draftOrNull() != null) saveDraft(draft, generation, profile)
        _uiState.update { state -> state.copy(applications = applications, deviceSnapshot = snapshot, draft = draft, selectedProfile = profile, validation = validateForUi(draft), operation = MutationOperation.Idle, draftNoticeShown = state.draftNoticeShown && draft != snapshot.draftOrNull()) }
    }
    fun selectTarget(target: TargetApplication) {
        if (!_uiState.value.canEdit) return
        val state = _uiState.value
        val draft = state.draft.copy(
            targetPackage = target.packageName,
            targetProcess = target.packageName,
            runId = state.draft.runId.takeIf(FusionConfigValidator::isValidRunId)
                ?: createRunId(target.packageName, state.selectedProfile),
        )
        saveDraft(draft, state.deviceSnapshot.generationOrNull() ?: 0L, state.selectedProfile)
        _uiState.update { it.withUserDraft(draft, it.selectedProfile) }
    }
    fun setDraft(draft: FusionConfigDraft) = updateDraft { draft }
    fun updateDraft(transform: (FusionConfigDraft) -> FusionConfigDraft) {
        if (_uiState.value.canEdit) _uiState.update { state ->
            val draft = transform(state.draft)
            saveDraft(draft, state.deviceSnapshot.generationOrNull() ?: 0L, ProfileId.CUSTOM)
            state.withUserDraft(draft, ProfileId.CUSTOM)
        }
    }
    fun applyProfile(profile: ProfileId) {
        if (_uiState.value.canEdit) _uiState.update { state ->
            val draft = ensureManagedRunId(ProfileRegistry.apply(profile, state.draft), profile)
            saveDraft(draft, state.deviceSnapshot.generationOrNull() ?: 0L, profile)
            state.withUserDraft(draft, profile)
        }
    }
    fun restoreDefaults() {
        if (_uiState.value.canEdit) _uiState.update { state ->
            val profile = ProfileId.DEFAULTS
            val draft = ensureManagedRunId(ProfileRegistry.apply(profile, state.draft), profile)
            saveDraft(draft, state.deviceSnapshot.generationOrNull() ?: 0L, profile)
            state.copy(draftNoticeShown = false).withUserDraft(draft, profile)
        }
    }
    fun restoreDefaultBudgets() = updateDraft(FusionConfigSchema::restoreDefaultBudgets)
    fun toggleCapturePoint(bit: Int) = updateDraft { it.copy(capturePointMask = CapturePointRegistry.toggle(it.capturePointMask, bit)) }
    fun setForeground(foreground: Boolean) { _uiState.update { it.copy(isForeground = foreground) } }
    fun consumeMessage(serial: Long) { _uiState.update { if (it.messageSerial == serial) it.copy(message = null) else it } }
    fun discardDraft() {
        savedStateHandle.remove<String>(DRAFT_KEY)
        savedStateHandle.remove<Long>(DRAFT_GENERATION_KEY)
        savedStateHandle.remove<String>(DRAFT_PROFILE_KEY)
        dependencies.clearPersistedDraft()
        refreshInternal(preserveDraft = false)
    }
    fun keepDraftForExit() {
        val state = _uiState.value
        dependencies.savePersistedDraft(state.draft, state.deviceSnapshot.generationOrNull() ?: 0L, state.selectedProfile)
    }
    fun publish() = mutate(MutationAction.PUBLISH, false)
    fun publishAndLaunch() = mutate(MutationAction.PUBLISH_AND_LAUNCH, true)
    fun disableNewProcesses() = disable(MutationAction.DISABLE, false)
    fun endSession() = disable(MutationAction.END_SESSION, true)
    fun recoverInterrupted() = execute(MutationAction.RECOVER) { require(_uiState.value.deviceSnapshot is FusionDeviceSnapshot.Interrupted); dependencies.recoverInterrupted() }
    fun exportLatest() {
        val state = _uiState.value
        val packageName = state.draft.targetPackage
        if (packageName.isBlank()) return failure(text(R.string.export_select_package))
        if (!_uiState.value.isOwnerUser) return failure(text(R.string.error_owner_only))
        if (!_uiState.value.isForeground) return failure(text(R.string.error_foreground_required))
        if (!inFlight.compareAndSet(false, true)) return
        _uiState.update { it.copy(operation = MutationOperation.Exporting, lastExport = null) }
        viewModelScope.launch(ioDispatcher) {
            runCatching { dependencies.exportLatest(packageName) }.onSuccess { result ->
                dependencies.appendAudit(
                    MutationAuditEntry(
                        System.currentTimeMillis(),
                        MutationAction.EXPORT,
                        packageName,
                        _uiState.value.deviceSnapshot.generationOrNull(),
                        true,
                    ),
                )
                _uiState.update {
                    it.copy(
                        operation = MutationOperation.Idle,
                        lastExport = result,
                    ).message(text(R.string.export_completed, result.archivePath))
                }
            }.onFailure { error ->
                dependencies.appendAudit(
                    MutationAuditEntry(
                        System.currentTimeMillis(),
                        MutationAction.EXPORT,
                        packageName,
                        _uiState.value.deviceSnapshot.generationOrNull(),
                        false,
                    ),
                )
                failure(text(R.string.export_failed, error.message ?: error.javaClass.simpleName))
            }
            inFlight.set(false)
        }
    }
    private fun mutate(action: MutationAction, launch: Boolean) = execute(action) {
        val state = _uiState.value
        val draft = state.draft.copy(
            enabled = launch,
            runId = if (launch || !FusionConfigValidator.isValidRunId(state.draft.runId)) {
                createRunId(state.draft.targetPackage, state.selectedProfile)
            } else {
                state.draft.runId
            },
        )
        require(FusionConfigValidator.validate(draft, launch).isValid) { text(R.string.error_validation) }
        val activeTarget = (state.deviceSnapshot as? FusionDeviceSnapshot.Enabled)?.draft?.targetPackage
        if (activeTarget != null) require(dependencies.forceStopAndWait(activeTarget).isSuccess) { text(R.string.error_stop_target) }
        if (launch && activeTarget != draft.targetPackage) require(dependencies.forceStopAndWait(draft.targetPackage).isSuccess) { text(R.string.error_stop_target) }
        val result = dependencies.publish(draft, state.deviceSnapshot.generationOrNull() ?: 0L)
        if (draft.enabled) dependencies.showActiveSession(draft.targetPackage, draft.runId) else dependencies.cancelActiveSession()
        if (launch) require(dependencies.launch(draft.targetPackage).isSuccess) { text(R.string.error_launch_target) }
        result
    }
    private fun disable(action: MutationAction, stop: Boolean) = execute(action) {
        val active = _uiState.value.deviceSnapshot as? FusionDeviceSnapshot.Enabled ?: error(text(R.string.error_no_active_session))
        if (stop) require(dependencies.forceStopAndWait(active.draft.targetPackage).isSuccess) { text(R.string.error_stop_target) }
        dependencies.disable(active.generation).also { dependencies.cancelActiveSession() }
    }
    private fun execute(action: MutationAction, work: suspend () -> FusionDeviceSnapshot) {
        if (!_uiState.value.isOwnerUser) return failure(text(R.string.error_owner_only))
        if (!_uiState.value.isForeground) return failure(text(R.string.error_foreground_required))
        if (!inFlight.compareAndSet(false, true)) return
        _uiState.update { it.copy(operation = MutationOperation.Publishing) }
        viewModelScope.launch(ioDispatcher) { runCatching { work() }.onSuccess { snapshot ->
            dependencies.appendAudit(MutationAuditEntry(System.currentTimeMillis(), action, snapshot.draftOrNull()?.targetPackage, snapshot.generationOrNull(), true))
            snapshot.draftOrNull()?.let { saveDraft(it, snapshot.generationOrNull() ?: 0L, _uiState.value.selectedProfile) }
            _uiState.update { it.copy(deviceSnapshot = snapshot, draft = snapshot.draftOrNull() ?: it.draft, operation = MutationOperation.Idle, draftNoticeShown = false).message(text(R.string.operation_completed)) }
        }.onFailure { error -> dependencies.appendAudit(MutationAuditEntry(System.currentTimeMillis(), action, _uiState.value.draft.targetPackage.ifBlank { null }, _uiState.value.deviceSnapshot.generationOrNull(), false)); failure(text(R.string.operation_failed, error.message ?: error.javaClass.simpleName)) }; inFlight.set(false) }
    }
    private fun failure(text: String) { _uiState.update { it.copy(operation = MutationOperation.Idle).message(text) } }

    private fun saveDraft(draft: FusionConfigDraft, generation: Long, profile: ProfileId) {
        savedStateHandle[DRAFT_KEY] = FusionConfigSchema.toJson(draft)
        savedStateHandle[DRAFT_GENERATION_KEY] = generation
        savedStateHandle[DRAFT_PROFILE_KEY] = profile.name
    }

    private fun restoredDraft(generation: Long): FusionConfigDraft? {
        val stateDraft = if (savedStateHandle.get<Long>(DRAFT_GENERATION_KEY) == generation) {
            savedStateHandle.get<String>(DRAFT_KEY)?.let { runCatching { FusionConfigSchema.fromJson(it) }.getOrNull() }
        } else {
            null
        }
        return stateDraft ?: dependencies.restorePersistedDraft(generation)?.draft
    }

    private fun restoredProfile(generation: Long): ProfileId {
        val stateProfile = if (savedStateHandle.get<Long>(DRAFT_GENERATION_KEY) == generation) {
            savedStateHandle.get<String>(DRAFT_PROFILE_KEY)
                ?.let { runCatching { ProfileId.valueOf(it) }.getOrNull() }
        } else {
            null
        }
        return stateProfile ?: dependencies.restorePersistedDraft(generation)?.profile
            ?: ProfileId.CUSTOM
    }

    private fun text(resId: Int, vararg args: Any): String = getApplication<Application>().getString(resId, *args)

    private fun createRunId(packageName: String, profile: ProfileId): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val tail = packageName.substringAfterLast('.').filter { it.isLetterOrDigit() }.take(20).ifBlank { "target" }
        val slug = profile.name.lowercase().replace('_', '-')
        return "$timestamp-$tail-$slug".take(64)
    }

    private fun ensureManagedRunId(draft: FusionConfigDraft, profile: ProfileId): FusionConfigDraft =
        if (draft.targetPackage.isNotBlank() && !FusionConfigValidator.isValidRunId(draft.runId)) {
            draft.copy(runId = createRunId(draft.targetPackage, profile))
        } else {
            draft
        }

    private companion object {
        const val DRAFT_KEY = "r0dump.draft.json"
        const val DRAFT_GENERATION_KEY = "r0dump.draft.generation"
        const val DRAFT_PROFILE_KEY = "r0dump.draft.profile"
    }
}
