// Several Material3 components used throughout this file (OutlinedTextField among them) are
// annotated @ExperimentalMaterial3Api in this ROM's Material3 1.1 snapshot, whereas the release the
// upstream code targets had already stabilised them.  Opting the whole file in keeps the upstream
// call sites unchanged; the API surface is the same, only the maturity marker differs.
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    com.android.r0dumpmanager.compat.ExperimentalMaterial3ExpressiveApi::class,
)

package com.android.r0dumpmanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.LocaleManager
import android.os.LocaleList
import android.widget.Toast
import com.android.r0dumpmanager.compat.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.android.r0dumpmanager.compat.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.android.r0dumpmanager.compat.HorizontalFloatingToolbar
import com.android.r0dumpmanager.compat.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import com.android.r0dumpmanager.compat.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.android.r0dumpmanager.compat.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import com.android.r0dumpmanager.compat.SegmentedButton
import com.android.r0dumpmanager.compat.SegmentedButtonDefaults
import com.android.r0dumpmanager.compat.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import com.android.r0dumpmanager.compat.TooltipBox
import com.android.r0dumpmanager.compat.TooltipDefaults
import com.android.r0dumpmanager.compat.rememberModalBottomSheetState
import com.android.r0dumpmanager.compat.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.scale
import com.android.r0dumpmanager.compat.WindowWidthSizeClass
import androidx.core.graphics.drawable.toBitmap
import com.android.r0dumpmanager.app.MutationOperation
import com.android.r0dumpmanager.R
import com.android.r0dumpmanager.app.R0dumpManagerViewModel
import com.android.r0dumpmanager.app.R0dumpUiState
import com.android.r0dumpmanager.data.property.FusionDeviceSnapshot
import com.android.r0dumpmanager.data.packageinfo.TargetApplication
import com.android.r0dumpmanager.data.ui.UiStateKey
import com.android.r0dumpmanager.data.ui.UiStateStore
import com.android.r0dumpmanager.domain.CapturePoint
import com.android.r0dumpmanager.domain.CapturePointRegistry
import com.android.r0dumpmanager.domain.FusionConfigDraft
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.FusionConfigValidator
import com.android.r0dumpmanager.domain.FusionValidationError
import com.android.r0dumpmanager.domain.FusionValidationErrorCode
import com.android.r0dumpmanager.domain.ProfileId
import com.android.r0dumpmanager.domain.WalkMode
import com.android.r0dumpmanager.domain.WalkStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

private enum class Destination { APPLICATION, CAPTURE, LIMITS, ADVANCED }
private enum class BudgetSection { CAPACITY, RECORDS }
private enum class AdvancedSection { MANIFEST, WALK, FORCE_ANR }
enum class ProcessMode { MAIN, ALL, CUSTOM }

fun processModeOf(draft: FusionConfigDraft): ProcessMode = when {
    draft.targetProcess.isBlank() -> ProcessMode.ALL
    draft.targetProcess == draft.targetPackage -> ProcessMode.MAIN
    else -> ProcessMode.CUSTOM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun R0dumpManagerScreen(
    viewModel: R0dumpManagerViewModel,
    windowWidthSizeClass: WindowWidthSizeClass,
    notificationsEnabled: Boolean = true,
    onRequestNotifications: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val uiStateStore = remember(context.applicationContext) { UiStateStore(context.applicationContext) }
    var destinationName by rememberPersistedUiString(
        uiStateStore,
        UiStateKey.DESTINATION,
        Destination.APPLICATION.name,
    )
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    val destination = Destination.values().firstOrNull { it.name == destinationName } ?: Destination.APPLICATION
    val snackbar = remember { SnackbarHostState() }
    val compact = windowWidthSizeClass == WindowWidthSizeClass.Compact
    val expanded = windowWidthSizeClass == WindowWidthSizeClass.Expanded
    val draftChangedMessage = stringResource(R.string.draft_changed)
    var consumedDraftNoticeSerial by remember { mutableStateOf(state.draftNoticeSerial) }

    LaunchedEffect(state.messageSerial) {
        state.message?.let { message ->
            if (state.messageSerial > 0) {
                snackbar.showSnackbar(message)
                viewModel.consumeMessage(state.messageSerial)
            }
        }
    }
    LaunchedEffect(state.draftNoticeSerial) {
        if (shouldShowDraftChangedToast(consumedDraftNoticeSerial, state.draftNoticeSerial)) {
            Toast.makeText(context, draftChangedMessage, Toast.LENGTH_SHORT).show()
        }
        consumedDraftNoticeSerial = state.draftNoticeSerial
    }
    // Keep this handler in the composition and gate it with state so callback ordering is stable.
    PredictiveBackHandler(enabled = state.isDirty) { progress ->
        try {
            progress.collect { }
            showExitDialog = true
        } catch (_: CancellationException) {
            // A cancelled gesture must not discard or publish the draft.
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
                LanguageMenu()
                ResetToolButton(state.canEdit, viewModel::restoreDefaults)
                if (shouldShowDraftPublishAction(state)) {
                    DraftPublishButton(viewModel::publish)
                }
                CaptureStateToggle(
                    state = state,
                    onPublishAndLaunch = viewModel::publishAndLaunch,
                    onEndSession = viewModel::endSession,
                    onRecoverInterrupted = viewModel::recoverInterrupted,
                    notificationsEnabled = notificationsEnabled,
                    onRequestNotifications = onRequestNotifications,
                )
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (compact) {
                Column {
                    if (shouldShowActionToolbar(state)) {
                        FusionActionToolbar(
                            state = state,
                        )
                    }
                    NavigationBar {
                        Destination.values().forEach { item ->
                            NavigationBarItem(selected = item == destination, onClick = { destinationName = item.name },
                                icon = { DestinationIcon(item) }, label = { Text(destinationTitle(item)) })
                        }
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (!compact) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    Destination.values().forEach { item ->
                        NavigationRailItem(selected = item == destination, onClick = { destinationName = item.name },
                            icon = { DestinationIcon(item) }, label = { Text(destinationTitle(item)) })
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (expanded) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        DestinationContent(
                            destination,
                            state,
                            viewModel,
                            Modifier.fillMaxHeight().widthIn(max = 720.dp),
                            compact,
                            uiStateStore,
                        )
                    }
                } else {
                    DestinationContent(destination, state, viewModel, Modifier.weight(1f), compact, uiStateStore)
                }
                if (!compact && shouldShowActionToolbar(state)) {
                    FusionActionToolbar(
                        state = state,
                        horizontal = true,
                    )
                }
            }
        }
    }
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.leave_with_changes)) },
            text = { Text(stringResource(R.string.leave_with_changes_detail)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = { viewModel.discardDraft(); showExitDialog = false; onExit() }) { Text(stringResource(R.string.discard_and_exit)) }
                    OutlinedButton(onClick = { viewModel.keepDraftForExit(); showExitDialog = false; onExit() }) { Text(stringResource(R.string.keep_draft)) }
                    androidx.compose.material3.TextButton(onClick = { showExitDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

@Composable
private fun LanguageMenu() {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = context.getString(R.string.change_language) },
        ) { Icon(Icons.Default.Language, null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                "" to stringResource(R.string.language_system),
                "zh-CN" to stringResource(R.string.language_chinese),
                "en" to stringResource(R.string.language_english),
            ).forEach { (tag, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        localeManager.applicationLocales = if (tag.isEmpty()) {
                            LocaleList.getEmptyLocaleList()
                        } else {
                            LocaleList.forLanguageTags(tag)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun destinationTitle(destination: Destination) = stringResource(
    when (destination) {
        Destination.APPLICATION -> R.string.nav_session
        Destination.CAPTURE -> R.string.nav_capture
        Destination.LIMITS -> R.string.nav_limits
        Destination.ADVANCED -> R.string.nav_advanced
    },
)

@Composable
private fun profileTitle(profile: ProfileId) = stringResource(
    when (profile) {
        ProfileId.DEFAULTS -> R.string.profile_defaults
        ProfileId.COOLAPK_COOKIE_WALK -> R.string.profile_coolapk_cookie_walk
        ProfileId.CUSTOM -> R.string.profile_custom
    },
)

@Composable
private fun profileSegmentTitle(profile: ProfileId) = stringResource(
    when (profile) {
        ProfileId.DEFAULTS -> R.string.profile_defaults
        ProfileId.COOLAPK_COOKIE_WALK -> R.string.profile_coolapk_cookie_walk_short
        ProfileId.CUSTOM -> R.string.profile_custom
    },
)

@Composable
private fun processModeTitle(mode: ProcessMode) = stringResource(
    when (mode) {
        ProcessMode.MAIN -> R.string.process_mode_main
        ProcessMode.ALL -> R.string.process_mode_all
        ProcessMode.CUSTOM -> R.string.process_mode_custom
    },
)

@Composable
private fun choiceTitle(choice: Enum<*>): String = when (choice) {
    WalkStart.AFTER_APPLICATION_ON_CREATE -> stringResource(R.string.walk_start_application)
    WalkStart.AFTER_FIRST_ACTIVITY_ON_CREATE -> stringResource(R.string.walk_start_activity)
    WalkMode.LOADED_ONLY -> stringResource(R.string.walk_mode_loaded_only)
    WalkMode.DEX_NAMES -> stringResource(R.string.walk_mode_dex_names)
    else -> choice.name
}

@Composable
private fun captureKind(kind: String): String = stringResource(
    when (kind) {
        "DEX snapshot" -> R.string.kind_dex_snapshot
        "raw buffer" -> R.string.kind_raw_buffer
        "class/Dex evidence" -> R.string.kind_class_dex_evidence
        "metadata" -> R.string.kind_metadata
        "CodeItem" -> R.string.kind_code_item
        "method evidence" -> R.string.kind_method_evidence
        "CodeItem/metadata" -> R.string.kind_code_item_metadata
        "DEX/reference" -> R.string.kind_dex_reference
        "DEX route" -> R.string.kind_dex_route
        "buffer route" -> R.string.kind_buffer_route
        "active DEX snapshot" -> R.string.kind_active_dex_snapshot
        "active method evidence" -> R.string.kind_active_method_evidence
        "active CodeItem" -> R.string.kind_active_code_item
        else -> R.string.kind_metadata
    },
)

@Composable
private fun validationMessage(error: FusionValidationError): String = when (error.code) {
    FusionValidationErrorCode.RANGE -> stringResource(R.string.validation_range, error.min, error.max)
    FusionValidationErrorCode.UNSUPPORTED_SCHEMA -> stringResource(R.string.validation_schema)
    FusionValidationErrorCode.INVALID_RUN_ID -> stringResource(R.string.validation_run_id)
    FusionValidationErrorCode.INVALID_TARGET_PACKAGE -> stringResource(R.string.validation_target_package)
    FusionValidationErrorCode.INVALID_TARGET_PROCESS -> stringResource(R.string.validation_target_process)
    FusionValidationErrorCode.CAPTURE_POINT_REQUIRED -> stringResource(R.string.validation_capture_required)
    FusionValidationErrorCode.UNDEFINED_CAPTURE_POINT -> stringResource(R.string.validation_capture_undefined)
    FusionValidationErrorCode.EXCEEDS_MAX_RECORDS -> stringResource(R.string.validation_max_records)
    FusionValidationErrorCode.EXCEEDS_TOTAL_BYTES -> stringResource(R.string.validation_total_bytes)
    FusionValidationErrorCode.BELOW_MAX_DEX_BYTES -> stringResource(R.string.validation_dex_bytes)
    FusionValidationErrorCode.EXCEEDS_BUFFER_POOL -> stringResource(R.string.validation_buffer_pool)
    FusionValidationErrorCode.BELOW_EVENTS_PER_SOURCE -> stringResource(R.string.validation_events_per_source)
    FusionValidationErrorCode.FORCE_BIT_REQUIRED -> stringResource(R.string.validation_force_bit)
    FusionValidationErrorCode.FORCE_SCOPE_REQUIRED -> stringResource(R.string.validation_force_scope)
    FusionValidationErrorCode.INVALID_BINARY_CLASS -> stringResource(R.string.validation_binary_class)
    FusionValidationErrorCode.INVALID_NAMESPACE_PREFIX -> stringResource(R.string.validation_namespace_prefix)
    FusionValidationErrorCode.INVALID_METHOD_NAME -> stringResource(R.string.validation_method_name)
    FusionValidationErrorCode.INVALID_METHOD_DESCRIPTOR -> stringResource(R.string.validation_method_descriptor)
    FusionValidationErrorCode.PROPERTY_VALUE_TOO_LONG -> stringResource(R.string.validation_property_too_long)
}

@Composable
private fun validationFieldLabel(field: String): String = stringResource(
    when (field) {
        "schema" -> R.string.configuration_schema
        "run_id" -> R.string.run_id
        "target_package" -> R.string.target_package
        "target_process" -> R.string.target_process
        "capture_point_mask" -> R.string.capture_points
        "max_records" -> R.string.max_records
        "max_artifacts" -> R.string.max_artifacts
        "max_total_bytes" -> R.string.max_total_bytes
        "max_dex_bytes" -> R.string.max_dex_bytes
        "buffer_pool_bytes" -> R.string.buffer_pool_bytes
        "max_code_item_bytes" -> R.string.max_code_item_bytes
        "max_frozen_generations" -> R.string.frozen_generations
        "flush_idle_ms" -> R.string.flush_idle_ms
        "manifest_max_components" -> R.string.max_components
        "manifest_max_splits" -> R.string.max_splits
        "manifest_max_events_per_source" -> R.string.events_per_source
        "manifest_max_total_events" -> R.string.total_events
        "manifest_max_xml_depth" -> R.string.xml_depth
        "manifest_max_attribute_length" -> R.string.attribute_length
        "walk_delay_ms" -> R.string.walk_delay_ms
        "walk_namespace_prefix" -> R.string.walk_namespace_prefix
        "walk_max_classes" -> R.string.walk_max_classes
        "walk_max_methods" -> R.string.walk_max_methods
        "force_class" -> R.string.force_class
        "force_method" -> R.string.force_method
        "force_method_descriptor" -> R.string.method_descriptor
        "force_max_calls" -> R.string.maximum_force_calls
        else -> R.string.unknown
    },
)

@Composable private fun DestinationIcon(destination: Destination) = Icon(when (destination) {
        Destination.APPLICATION -> Icons.Default.Apps
    Destination.CAPTURE -> Icons.Default.Checklist
    Destination.LIMITS -> Icons.Default.DataUsage
    Destination.ADVANCED -> Icons.Default.Settings
}, contentDescription = null)

@Composable
private fun DestinationContent(
    destination: Destination,
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
    modifier: Modifier,
    compact: Boolean,
    uiStateStore: UiStateStore,
) {
    when (destination) {
        Destination.APPLICATION -> ApplicationScreen(state, viewModel, modifier, compact)
        Destination.CAPTURE -> CaptureScreen(state, viewModel, modifier, uiStateStore)
        Destination.LIMITS -> LimitsScreen(state, viewModel, modifier, uiStateStore)
        Destination.ADVANCED -> AdvancedScreen(state, viewModel, modifier, uiStateStore)
    }
}

@Composable
private fun rememberPersistedUiString(
    store: UiStateStore,
    key: UiStateKey,
    defaultValue: String,
): MutableState<String> {
    val state = rememberSaveable(key.name) { mutableStateOf(store.read(key) ?: defaultValue) }
    LaunchedEffect(state.value) { store.write(key, state.value) }
    return state
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CaptureStateToggle(
    state: R0dumpUiState,
    onPublishAndLaunch: () -> Unit,
    onEndSession: () -> Unit,
    onRecoverInterrupted: () -> Unit,
    notificationsEnabled: Boolean,
    onRequestNotifications: () -> Unit,
) {
    var showNotificationDialog by remember { mutableStateOf(false) }
    val publishing = state.operation is MutationOperation.Publishing
    val active = state.deviceSnapshot is FusionDeviceSnapshot.Enabled
    val enabled = when {
        publishing || !state.isOwnerUser -> false
        !state.canMutate -> true
        state.isInterrupted || active -> true
        else -> state.validation.isValid
    }
    val description = stringResource(
        when {
            !state.canMutate -> R.string.capture_toggle_enable
            state.isInterrupted -> R.string.recover_disabled_state
            active -> R.string.capture_toggle_disable
            else -> R.string.capture_toggle_enable
        },
    )
    val tooltipState = rememberTooltipState()
    val continueTransition = {
        when {
            state.isInterrupted -> onRecoverInterrupted()
            active -> onEndSession()
            !notificationsEnabled -> showNotificationDialog = true
            else -> onPublishAndLaunch()
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = tooltipState,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .toggleable(
                    value = active,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = {
                        continueTransition()
                    },
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (publishing) {
                LoadingIndicator(Modifier.size(24.dp))
            } else {
                Switch(
                    checked = active,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.scale(0.82f),
                )
            }
        }
    }
    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationDialog = false },
            title = { Text(stringResource(R.string.notifications_disabled)) },
            text = { Text(stringResource(R.string.notification_permission_detail)) },
            confirmButton = {
                Button(onClick = { showNotificationDialog = false; onRequestNotifications() }) {
                    Text(stringResource(R.string.enable_notifications))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNotificationDialog = false; onPublishAndLaunch() }) {
                    Text(stringResource(R.string.continue_action))
                }
            },
        )
    }
}

@Composable private fun StatusSurface(title: String, detail: String, warning: Boolean) {
    Surface(color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (warning) Icons.Default.ErrorOutline else Icons.Default.Info, null)
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplicationScreen(
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
    modifier: Modifier,
    compact: Boolean,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    PredictiveBackHandler(enabled = searchExpanded) { progress ->
        try {
            progress.collect { }
            searchExpanded = false
            query = ""
        } catch (_: CancellationException) {
            // Keep the search query when the back gesture is cancelled.
        }
    }
    Box(modifier) {
        Page(Modifier.fillMaxSize()) {
            if (!state.isOwnerUser) StatusSurface(stringResource(R.string.owner_user_only), stringResource(R.string.owner_user_only_detail), true)
            Text(stringResource(R.string.select_application), style = MaterialTheme.typography.titleLarge)
            ApplicationSearchBar(
                query = query,
                onQueryChange = { query = it },
                expanded = false,
                onExpandedChange = { searchExpanded = it },
                applications = state.applications,
                selectedPackage = state.draft.targetPackage,
                enabled = state.canEdit,
                compact = compact,
                onRescan = { viewModel.rescanApplications() },
                onSelect = { target ->
                    viewModel.selectTarget(target)
                    query = ""
                    searchExpanded = false
                },
            )
            DraftText(stringResource(R.string.target_package), state.draft.targetPackage, state.validation.errors["target_package"], state, viewModel) { value ->
                val previousPackage = state.draft.targetPackage
                val process = state.draft.targetProcess
                state.draft.copy(
                    targetPackage = value,
                    targetProcess = when {
                        process == previousPackage -> value
                        process.isBlank() -> ""
                        previousPackage.isNotBlank() && process.startsWith("$previousPackage:") -> value + process.removePrefix(previousPackage)
                        else -> process
                    },
                )
            }
            TargetProcessField(state, viewModel)
            Text(stringResource(R.string.profile), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ProfileId.values().forEachIndexed { index, profile ->
                    SegmentedButton(
                        selected = state.selectedProfile == profile,
                        onClick = { viewModel.applyProfile(profile) },
                        shape = SegmentedButtonDefaults.itemShape(index, ProfileId.values().size),
                        modifier = Modifier.weight(1f),
                        enabled = state.canEdit,
                    ) {
                        Text(profileSegmentTitle(profile), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ArtifactExportSection(state, viewModel)
        }
        if (searchExpanded) {
            ApplicationSearchBar(
                query = query,
                onQueryChange = { query = it },
                expanded = true,
                onExpandedChange = {
                    searchExpanded = it
                    if (!it) query = ""
                },
                applications = state.applications,
                selectedPackage = state.draft.targetPackage,
                enabled = state.canEdit,
                compact = compact,
                modifier = if (compact) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.align(Alignment.TopCenter).padding(16.dp).widthIn(max = 720.dp)
                },
                onRescan = { viewModel.rescanApplications() },
                onSelect = { target ->
                    viewModel.selectTarget(target)
                    query = ""
                    searchExpanded = false
                },
            )
        }
    }
}

@Composable
private fun ArtifactExportSection(
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
) {
    val exporting = state.operation is MutationOperation.Exporting
    val startExport = { viewModel.exportLatest() }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(stringResource(R.string.artifact_export_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.artifact_export_detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = {
            startExport()
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.isOwnerUser &&
            state.draft.targetPackage.isNotBlank() && state.operation is MutationOperation.Idle,
    ) {
        if (exporting) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Archive, null)
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (exporting) R.string.artifact_exporting else R.string.artifact_export_action))
    }
    state.lastExport?.let { result ->
        Text(
            stringResource(
                R.string.artifact_export_summary,
                result.verified,
                result.repaired,
                result.partial,
                result.codeItemsBackfilled,
                result.usableOutputs,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            result.archivePath,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                if (result.usableOutputs > 0) {
                    R.string.artifact_export_jadx_ready
                } else {
                    R.string.artifact_export_jadx_unavailable
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplicationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    applications: List<TargetApplication>,
    selectedPackage: String,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onRescan: () -> Unit,
    onSelect: (TargetApplication) -> Unit,
) {
    val filtered = applications.filter {
        query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
    }
    val leadingIcon: @Composable () -> Unit = { Icon(Icons.Default.Search, null) }
    // With a query the trailing control clears it; otherwise it re-scans the
    // installed packages so an app installed after launch shows up without
    // restarting the manager.
    val trailingIcon: @Composable (() -> Unit) = if (query.isNotEmpty()) {
        {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Close, stringResource(R.string.clear_search))
            }
        }
    } else {
        {
            IconButton(onClick = onRescan, enabled = enabled) {
                Icon(Icons.Default.Refresh, stringResource(R.string.rescan_apps))
            }
        }
    }
    val results: @Composable () -> Unit = {
        when {
            applications.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
            filtered.isEmpty() -> Text(
                stringResource(R.string.no_app_results),
                Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(filtered.take(50), key = { it.packageName }) { target ->
                    ApplicationRow(
                        target = target,
                        selected = target.packageName == selectedPackage,
                        enabled = enabled,
                        onClick = { onSelect(target) },
                    )
                }
            }
        }
    }
    if (compact) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            active = expanded,
            onActiveChange = onExpandedChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.search_installed_apps)) },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            windowInsets = WindowInsets(0, 0, 0, 0),
            content = { results() },
        )
    } else {
        DockedSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            active = expanded,
            onActiveChange = onExpandedChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.search_installed_apps)) },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            content = { results() },
        )
    }
}

@Composable
private fun TargetProcessField(state: R0dumpUiState, viewModel: R0dumpManagerViewModel) {
    var confirmAllProcesses by rememberSaveable { mutableStateOf(false) }
    val mode = processModeOf(state.draft)
    Text(stringResource(R.string.process_mode), style = MaterialTheme.typography.titleMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ProcessMode.values().forEachIndexed { index, processMode ->
            SegmentedButton(
                selected = mode == processMode,
                onClick = {
                    when (processMode) {
                        ProcessMode.MAIN -> viewModel.updateDraft { it.copy(targetProcess = it.targetPackage) }
                        ProcessMode.ALL -> confirmAllProcesses = true
                        ProcessMode.CUSTOM -> viewModel.updateDraft {
                            val customProcess = if (it.targetProcess.startsWith("${it.targetPackage}:") && it.targetProcess.length > it.targetPackage.length + 1) {
                                it.targetProcess
                            } else if (it.targetPackage.isBlank()) {
                                ""
                            } else {
                                "${it.targetPackage}:"
                            }
                            it.copy(targetProcess = customProcess)
                        }
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index, ProcessMode.values().size),
                modifier = Modifier.weight(1f),
                enabled = state.canEdit,
            ) {
                Text(processModeTitle(processMode), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (mode == ProcessMode.CUSTOM) {
        OutlinedTextField(
            value = state.draft.targetProcess,
            onValueChange = { value -> viewModel.updateDraft { it.copy(targetProcess = value) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canEdit,
            label = { Text(stringResource(R.string.custom_process)) },
            supportingText = state.validation.errors["target_process"]?.let { error -> { Text(validationMessage(error)) } },
            isError = state.validation.errors.containsKey("target_process"),
            singleLine = true,
        )
    }
    if (confirmAllProcesses) {
        ConfirmationSheet(
            title = stringResource(R.string.capture_all_processes),
            detail = stringResource(R.string.capture_all_processes_detail, state.draft.targetPackage),
            onConfirm = {
                confirmAllProcesses = false
                viewModel.updateDraft { it.copy(targetProcess = "") }
            },
            onDismiss = { confirmAllProcesses = false },
        )
    }
}

@Composable private fun ApplicationRow(target: TargetApplication, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val icon = remember(target.icon) { target.icon.toBitmap(width = 96, height = 96).asImageBitmap() }
    Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(icon, null, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(target.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (target.isSystem) stringResource(R.string.system_app) else stringResource(R.string.user_app), style = MaterialTheme.typography.labelSmall)
                Text(if (target.launchable) stringResource(R.string.launchable) else stringResource(R.string.no_launcher), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureScreen(
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
    modifier: Modifier,
    uiStateStore: UiStateStore,
) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipLabel = stringResource(R.string.capture_mask_clip_label)
    var expandedGroupIndexes by rememberPersistedUiString(
        uiStateStore,
        UiStateKey.CAPTURE_GROUPS,
        encodeGroupIndexes(defaultExpandedCaptureGroupIndexes()),
    )
    val expandedGroups = decodeGroupIndexes(expandedGroupIndexes)
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PageHeader(
                stringResource(R.string.capture_points),
                stringResource(
                    R.string.capture_selected_count,
                    CapturePointRegistry.points.count {
                        CapturePointRegistry.contains(state.draft.capturePointMask, it.bit)
                    },
                ),
            )
        }
        item {
            OutlinedButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, state.maskText)) }) {
                Text(state.maskText, fontFamily = FontFamily.Monospace)
            }
        }
        pointGroups.forEachIndexed { index, (titleRes, points) ->
            val expanded = index in expandedGroups
            val selectedCount = points.count {
                CapturePointRegistry.contains(state.draft.capturePointMask, it.bit)
            }
            stickyHeader(key = titleRes) {
                CapturePointGroupHeader(
                    title = stringResource(titleRes),
                    selectedCount = selectedCount,
                    totalCount = points.size,
                    expanded = expanded,
                    onToggle = {
                        expandedGroupIndexes = encodeGroupIndexes(
                            if (expanded) expandedGroups - index else expandedGroups + index,
                        )
                    },
                )
            }
            item(key = "capture_group_$titleRes") {
                ExpandCollapseContent(visible = expanded) {
                    Column {
                        points.forEach { point -> CapturePointRow(point, state, viewModel) }
                    }
                }
            }
        }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

internal val pointGroups = listOf(
    R.string.group_dex_establishment to CapturePointRegistry.points.filter { it.bit in 0..7 },
    R.string.group_class_linker to CapturePointRegistry.points.filter { it.bit in 8..15 },
    R.string.group_oat_vdex to CapturePointRegistry.points.filter { it.bit in 16..21 },
    R.string.group_execution_jit to CapturePointRegistry.points.filter { it.bit in 22..31 },
    R.string.group_java_route to CapturePointRegistry.points.filter { it.bit in 32..38 },
    R.string.group_active_observation to CapturePointRegistry.points.filter { it.bit in 39..42 },
)

fun defaultExpandedCaptureGroupIndexes(): Set<Int> = emptySet()

private fun encodeGroupIndexes(indexes: Set<Int>): String = indexes.sorted().joinToString(",")

private fun decodeGroupIndexes(encoded: String): Set<Int> = encoded
    .split(',')
    .mapNotNull(String::toIntOrNull)
    .toSet()

@Composable
fun CapturePointGroupHeader(
    title: String,
    selectedCount: Int,
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val description = stringResource(
        if (expanded) R.string.collapse_capture_group else R.string.expand_capture_group,
        title,
    )
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onToggle)
                .semantics { contentDescription = description }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.capture_group_selected_count, selectedCount, totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
    }
}

@Composable private fun CapturePointRow(point: CapturePoint, state: R0dumpUiState, viewModel: R0dumpManagerViewModel) {
    val checked = CapturePointRegistry.contains(state.draft.capturePointMask, point.bit)
    val description = stringResource(
        R.string.capture_point_description,
        point.bit,
        point.sourceLocation,
        point.name,
    )
    val highCost = point.kind.contains("snapshot", ignoreCase = true)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = state.canEdit, onClick = { viewModel.toggleCapturePoint(point.bit) }).semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = state.canEdit, onCheckedChange = { viewModel.toggleCapturePoint(point.bit) })
        Column(Modifier.weight(1f)) {
            Text("${point.bit.toString().padStart(2, '0')}  ${point.sourceLocation}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${point.name} · ${captureKind(point.kind)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (highCost) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WarningAmber,
                        stringResource(R.string.high_cost_capture_point_detail),
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        stringResource(R.string.high_cost_capture_point),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        if (point.bit >= 39) Text(stringResource(R.string.active), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitsScreen(
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
    modifier: Modifier,
    uiStateStore: UiStateStore,
) {
    var expandedSectionsText by rememberPersistedUiString(uiStateStore, UiStateKey.BUDGET_GROUPS, "")
    val expandedSections = expandedSectionsText
        .split(',')
        .mapNotNull { name -> BudgetSection.values().firstOrNull { it.name == name } }
        .toSet()
    val setSectionExpanded = { section: BudgetSection, expanded: Boolean ->
        expandedSectionsText = (if (expanded) expandedSections + section else expandedSections - section)
            .sortedBy(BudgetSection::ordinal)
            .joinToString(",", transform = BudgetSection::name)
    }
    val defaults = remember { FusionConfigSchema.defaults() }
    LaunchedEffect(state.validation.errors.keys) {
        val errorSections = buildSet {
            if (state.validation.errors.keys.any { it in budgetCapacityKeys }) add(BudgetSection.CAPACITY)
            if (state.validation.errors.keys.any { it in budgetRecordKeys }) add(BudgetSection.RECORDS)
        }
        if (errorSections.isNotEmpty()) {
            expandedSectionsText = (expandedSections + errorSections)
                .sortedBy(BudgetSection::ordinal)
                .joinToString(",", transform = BudgetSection::name)
        }
    }
    Page(
        modifier,
        stringResource(R.string.budgets_title),
        stringResource(R.string.budgets_subtitle),
        headerAction = {
            val resetDescription = stringResource(R.string.restore_default_budgets)
            val tooltipState = rememberTooltipState()
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(resetDescription) } },
                state = tooltipState,
            ) {
                IconButton(
                    onClick = viewModel::restoreDefaultBudgets,
                    enabled = state.canEdit,
                    modifier = Modifier.semantics {
                        contentDescription = resetDescription
                    },
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                }
            }
        },
    ) {
        SettingsGroupHeader(
            title = stringResource(R.string.budget_capacity_group),
            summary = stringResource(R.string.settings_count, budgetCapacityKeys.size),
            expanded = BudgetSection.CAPACITY in expandedSections,
            onToggle = {
                setSectionExpanded(BudgetSection.CAPACITY, BudgetSection.CAPACITY !in expandedSections)
            },
        )
        ExpandCollapseContent(visible = BudgetSection.CAPACITY in expandedSections) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ByteBudgetField(stringResource(R.string.max_total_bytes), "max_total_bytes", state.draft.maxTotalBytes, defaults.maxTotalBytes, FusionConfigValidator.MAX_TOTAL_BYTES, state, viewModel) { value -> state.draft.copy(maxTotalBytes = value) }
                ByteBudgetField(stringResource(R.string.max_dex_bytes), "max_dex_bytes", state.draft.maxDexBytes, defaults.maxDexBytes, FusionConfigValidator.MAX_DEX_BYTES, state, viewModel) { value -> state.draft.copy(maxDexBytes = value) }
                ByteBudgetField(stringResource(R.string.buffer_pool_bytes), "buffer_pool_bytes", state.draft.bufferPoolBytes, defaults.bufferPoolBytes, FusionConfigValidator.MAX_BUFFER_POOL_BYTES, state, viewModel) { value -> state.draft.copy(bufferPoolBytes = value) }
                ByteBudgetField(stringResource(R.string.max_code_item_bytes), "max_code_item_bytes", state.draft.maxCodeItemBytes, defaults.maxCodeItemBytes, FusionConfigValidator.MAX_CODE_ITEM_BYTES, state, viewModel) { value -> state.draft.copy(maxCodeItemBytes = value) }
            }
        }
        SettingsGroupHeader(
            title = stringResource(R.string.budget_records_group),
            summary = stringResource(R.string.settings_count, budgetRecordKeys.size),
            expanded = BudgetSection.RECORDS in expandedSections,
            onToggle = {
                setSectionExpanded(BudgetSection.RECORDS, BudgetSection.RECORDS !in expandedSections)
            },
        )
        ExpandCollapseContent(visible = BudgetSection.RECORDS in expandedSections) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(stringResource(R.string.max_artifacts), "max_artifacts", state.draft.maxArtifacts, state, viewModel, defaults.maxArtifacts, FusionConfigValidator.MAX_ARTIFACTS) { value -> state.draft.copy(maxArtifacts = value) }
                NumberField(stringResource(R.string.max_records), "max_records", state.draft.maxRecords, state, viewModel, defaults.maxRecords, FusionConfigValidator.MAX_RECORDS) { value -> state.draft.copy(maxRecords = value) }
                NumberField(stringResource(R.string.frozen_generations), "max_frozen_generations", state.draft.maxFrozenGenerations, state, viewModel, defaults.maxFrozenGenerations, FusionConfigValidator.MAX_FROZEN_GENERATIONS) { value -> state.draft.copy(maxFrozenGenerations = value) }
                NumberField(stringResource(R.string.flush_idle_ms), "flush_idle_ms", state.draft.flushIdleMs, state, viewModel, defaults.flushIdleMs, FusionConfigValidator.MAX_FLUSH_IDLE_MS) { value -> state.draft.copy(flushIdleMs = value) }
            }
        }
    }
}

private val budgetCapacityKeys = setOf(
    "max_total_bytes",
    "max_dex_bytes",
    "buffer_pool_bytes",
    "max_code_item_bytes",
)

private val budgetRecordKeys = setOf(
    "max_artifacts",
    "max_records",
    "max_frozen_generations",
    "flush_idle_ms",
)

@Composable
private fun AdvancedScreen(
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
    modifier: Modifier,
    uiStateStore: UiStateStore,
) = Page(
    modifier,
    stringResource(R.string.advanced_title),
    stringResource(R.string.advanced_subtitle),
) {
    var pendingRisk by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedSectionsText by rememberPersistedUiString(uiStateStore, UiStateKey.ADVANCED_GROUPS, "")
    val expandedSections = expandedSectionsText
        .split(',')
        .mapNotNull { name -> AdvancedSection.values().firstOrNull { it.name == name } }
        .toSet()
    val setSectionExpanded = { section: AdvancedSection, expanded: Boolean ->
        expandedSectionsText = (if (expanded) expandedSections + section else expandedSections - section)
            .sortedBy(AdvancedSection::ordinal)
            .joinToString(",", transform = AdvancedSection::name)
    }

    LaunchedEffect(state.validation.errors.keys) {
        val errorSections = buildSet {
            if (state.validation.errors.keys.any { it.startsWith("manifest_") }) add(AdvancedSection.MANIFEST)
            if (state.validation.errors.keys.any { it.startsWith("walk_") }) add(AdvancedSection.WALK)
            if (state.validation.errors.keys.any { it.startsWith("force_") }) add(AdvancedSection.FORCE_ANR)
        }
        if (errorSections.isNotEmpty()) {
            expandedSectionsText = (expandedSections + errorSections)
                .sortedBy(AdvancedSection::ordinal)
                .joinToString(",", transform = AdvancedSection::name)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.mcp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.mcpEnabled,
                enabled = state.isOwnerUser,
                onCheckedChange = { viewModel.setMcpEnabled(it) },
            )
        }
        if (state.mcpEnabled) {
            Text(
                stringResource(R.string.mcp_connect_hint),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SettingsGroupHeader(
        title = stringResource(R.string.manifest),
        summary = stringResource(if (state.draft.manifestEnabled) R.string.setting_enabled else R.string.setting_disabled),
        expanded = AdvancedSection.MANIFEST in expandedSections,
        onToggle = {
            setSectionExpanded(
                AdvancedSection.MANIFEST,
                AdvancedSection.MANIFEST !in expandedSections,
            )
        },
    )
    ExpandCollapseContent(visible = AdvancedSection.MANIFEST in expandedSections) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DraftSwitch(stringResource(R.string.enable_manifest), state.draft.manifestEnabled, state, viewModel) { value -> state.draft.copy(manifestEnabled = value) }
            DraftSwitch(stringResource(R.string.include_splits), state.draft.manifestIncludeSplits, state, viewModel) { value -> state.draft.copy(manifestIncludeSplits = value) }
            NumberField(stringResource(R.string.max_components), "manifest_max_components", state.draft.manifestMaxComponents, state, viewModel) { value -> state.draft.copy(manifestMaxComponents = value) }
            NumberField(stringResource(R.string.max_splits), "manifest_max_splits", state.draft.manifestMaxSplits, state, viewModel) { value -> state.draft.copy(manifestMaxSplits = value) }
            NumberField(stringResource(R.string.events_per_source), "manifest_max_events_per_source", state.draft.manifestMaxEventsPerSource, state, viewModel) { value -> state.draft.copy(manifestMaxEventsPerSource = value) }
            NumberField(stringResource(R.string.total_events), "manifest_max_total_events", state.draft.manifestMaxTotalEvents, state, viewModel) { value -> state.draft.copy(manifestMaxTotalEvents = value) }
            NumberField(stringResource(R.string.xml_depth), "manifest_max_xml_depth", state.draft.manifestMaxXmlDepth, state, viewModel) { value -> state.draft.copy(manifestMaxXmlDepth = value) }
            NumberField(stringResource(R.string.attribute_length), "manifest_max_attribute_length", state.draft.manifestMaxAttributeLength, state, viewModel) { value -> state.draft.copy(manifestMaxAttributeLength = value) }
        }
    }

    SettingsGroupHeader(
        title = stringResource(R.string.walk),
        summary = stringResource(if (state.draft.walkEnabled) R.string.setting_enabled else R.string.setting_disabled),
        expanded = AdvancedSection.WALK in expandedSections,
        onToggle = {
            setSectionExpanded(
                AdvancedSection.WALK,
                AdvancedSection.WALK !in expandedSections,
            )
        },
    )
    ExpandCollapseContent(visible = AdvancedSection.WALK in expandedSections) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DraftSwitch(stringResource(R.string.enable_walk), state.draft.walkEnabled, state, viewModel) { value -> state.draft.copy(walkEnabled = value) }
            ExpandCollapseContent(visible = state.draft.walkEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EnumDropdownSelector(stringResource(R.string.walk_start), WalkStart.values().toList(), state.draft.walkStart, state.canEdit) { viewModel.updateDraft { draft -> draft.copy(walkStart = it) } }
                    EnumDropdownSelector(stringResource(R.string.walk_mode), WalkMode.values().toList(), state.draft.walkMode, state.canEdit) { viewModel.updateDraft { draft -> draft.copy(walkMode = it) } }
                    DraftText(stringResource(R.string.walk_namespace_prefix), state.draft.walkNamespacePrefix, state.validation.errors["walk_namespace_prefix"], state, viewModel) { state.draft.copy(walkNamespacePrefix = it) }
                    NumberField(stringResource(R.string.walk_delay_ms), "walk_delay_ms", state.draft.walkDelayMs, state, viewModel) { value -> state.draft.copy(walkDelayMs = value) }
                    NumberField(stringResource(R.string.walk_max_classes), "walk_max_classes", state.draft.walkMaxClasses, state, viewModel) { value -> state.draft.copy(walkMaxClasses = value) }
                    NumberField(stringResource(R.string.walk_max_methods), "walk_max_methods", state.draft.walkMaxMethods, state, viewModel) { value -> state.draft.copy(walkMaxMethods = value) }
                }
            }
        }
    }

    val enabledProtectionCount = listOf(state.draft.forceInvokeEnabled, state.draft.anrProtectionEnabled).count { it }
    SettingsGroupHeader(
        title = stringResource(R.string.force_anr),
        summary = stringResource(R.string.advanced_group_enabled_count, enabledProtectionCount, 2),
        expanded = AdvancedSection.FORCE_ANR in expandedSections,
        onToggle = {
            setSectionExpanded(
                AdvancedSection.FORCE_ANR,
                AdvancedSection.FORCE_ANR !in expandedSections,
            )
        },
    )
    ExpandCollapseContent(visible = AdvancedSection.FORCE_ANR in expandedSections) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RiskSwitch(stringResource(R.string.enable_anr_protection), state.draft.anrProtectionEnabled, state.canEdit, onEnable = { pendingRisk = "anr" }, onDisable = { viewModel.updateDraft { it.copy(anrProtectionEnabled = false) } })
            RiskSwitch(stringResource(R.string.enable_force_invoke), state.draft.forceInvokeEnabled, state.canEdit, onEnable = { pendingRisk = "force" }, onDisable = { viewModel.updateDraft { it.copy(forceInvokeEnabled = false) } })
            DraftText(stringResource(R.string.force_class), state.draft.forceClass, state.validation.errors["force_class"], state, viewModel) { state.draft.copy(forceClass = it) }
            DraftText(stringResource(R.string.force_method), state.draft.forceMethod, state.validation.errors["force_method"], state, viewModel) { state.draft.copy(forceMethod = it) }
            DraftText(stringResource(R.string.method_descriptor), state.draft.forceMethodDescriptor, state.validation.errors["force_method_descriptor"], state, viewModel) { state.draft.copy(forceMethodDescriptor = it) }
            NumberField(stringResource(R.string.maximum_force_calls), "force_max_calls", state.draft.forceMaxCalls, state, viewModel) { value -> state.draft.copy(forceMaxCalls = value) }
        }
    }
    when (pendingRisk) {
        "force" -> ConfirmationSheet(
            title = stringResource(R.string.confirm_force_invoke),
            detail = stringResource(R.string.confirm_force_invoke_detail),
            onConfirm = {
                pendingRisk = null
                viewModel.updateDraft { it.copy(forceInvokeEnabled = true, capturePointMask = it.capturePointMask or CapturePointRegistry.forceNoExecuteMask) }
            },
            onDismiss = { pendingRisk = null },
        )
        "anr" -> ConfirmationSheet(
            title = stringResource(R.string.confirm_anr_protection),
            detail = stringResource(R.string.confirm_anr_protection_detail),
            onConfirm = {
                pendingRisk = null
                viewModel.updateDraft { it.copy(anrProtectionEnabled = true) }
            },
            onDismiss = { pendingRisk = null },
        )
    }
}

@Composable
fun SettingsGroupHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val description = stringResource(
        if (expanded) R.string.collapse_settings_group else R.string.expand_settings_group,
        title,
    )
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onToggle)
                .semantics { contentDescription = description }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
    }
}

@Composable
private fun RiskSwitch(label: String, checked: Boolean, enabled: Boolean, onEnable: () -> Unit, onDisable: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = { if (it) onEnable() else onDisable() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmationSheet(title: String, detail: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Enum<T>> EnumDropdownSelector(
    title: String,
    choices: List<T>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = choiceTitle(selected),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                // 1.1 has no anchor-type parameter: the anchor role is implied by the field, and
                // the field's own `enabled` controls interactivity, so the arguments upstream
                // passes have no separate equivalent here.
                .menuAnchor()
                .fillMaxWidth(),
            enabled = enabled,
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choiceTitle(choice)) },
                    onClick = {
                        expanded = false
                        onSelected(choice)
                    },
                    leadingIcon = if (choice == selected) {
                        { Icon(Icons.Default.Check, null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ExpandCollapseContent(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)) +
            expandVertically(animationSpec = tween(220), expandFrom = Alignment.Top),
        exit = fadeOut(animationSpec = tween(150)) +
            shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Top),
    ) {
        content()
    }
}

@Composable private fun DraftSwitch(label: String, checked: Boolean, state: R0dumpUiState, viewModel: R0dumpManagerViewModel, transform: (Boolean) -> FusionConfigDraft) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked = checked, enabled = state.canEdit, onCheckedChange = { viewModel.setDraft(transform(it)) }) }
}

@Composable
private fun ByteBudgetField(
    label: String,
    key: String,
    value: Long,
    defaultValue: Long,
    maxValue: Long,
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
    transform: (Long) -> FusionConfigDraft,
) {
    var input by rememberSaveable(key) { mutableStateOf(mibTextOf(value)) }
    LaunchedEffect(value) {
        val parsed = bytesFromMiBInput(input)
        if (parsed != value && !(parsed == null && value == 0L)) input = mibTextOf(value)
    }
    val error = state.validation.errors[key]
    val metadata = stringResource(
        R.string.byte_budget_supporting,
        mibTextOf(defaultValue),
        mibTextOf(maxValue),
        value,
    )
    OutlinedTextField(
        value = input,
        onValueChange = { raw ->
            input = raw
            viewModel.setDraft(transform(bytesFromMiBInput(raw) ?: 0L))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canEdit,
        label = { Text(label) },
        suffix = { Text(stringResource(R.string.unit_mib)) },
        supportingText = { Text(listOfNotNull(error?.let { validationMessage(it) }, metadata).joinToString("\n")) },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

@Composable
private fun NumberField(
    label: String,
    key: String,
    value: Long,
    state: R0dumpUiState,
    viewModel: R0dumpManagerViewModel,
    defaultValue: Long? = null,
    maxValue: Long? = null,
    transform: (Long) -> FusionConfigDraft,
) {
    var input by rememberSaveable(key) { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        val parsed = input.toLongOrNull()
        if (parsed != value && !(parsed == null && value == 0L)) input = value.toString()
    }
    val error = state.validation.errors[key]
    val metadata = if (defaultValue != null && maxValue != null) {
        stringResource(R.string.number_budget_supporting, defaultValue, maxValue)
    } else {
        null
    }
    OutlinedTextField(
        value = input,
        onValueChange = { raw ->
            input = raw
            viewModel.setDraft(transform(raw.toLongOrNull() ?: 0L))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canEdit,
        label = { Text(label) },
        supportingText = listOfNotNull(error?.let { validationMessage(it) }, metadata)
            .takeIf { it.isNotEmpty() }
            ?.let { messages -> { Text(messages.joinToString("\n")) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable private fun DraftText(label: String, value: String, error: FusionValidationError?, state: R0dumpUiState, viewModel: R0dumpManagerViewModel, transform: (String) -> FusionConfigDraft) {
    OutlinedTextField(value, { viewModel.setDraft(transform(it)) }, Modifier.fillMaxWidth(), enabled = state.canEdit, label = { Text(label) }, supportingText = error?.let { { Text(validationMessage(it)) } }, isError = error != null, singleLine = true)
}

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            action?.invoke()
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun Page(
    modifier: Modifier,
    title: String? = null,
    subtitle: String? = null,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null && subtitle != null) {
            item { PageHeader(title, subtitle, headerAction) }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

fun shouldShowActionToolbar(state: R0dumpUiState): Boolean =
    state.isInterrupted || !state.validation.isValid

fun shouldShowDraftChangedToast(consumedSerial: Long, currentSerial: Long): Boolean =
    currentSerial > 0L && currentSerial != consumedSerial

fun shouldShowDraftPublishAction(state: R0dumpUiState): Boolean =
    state.isDirty && state.validation.isValid && state.canMutate && !state.isInterrupted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftPublishButton(onPublish: () -> Unit) {
    val description = stringResource(R.string.publish)
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = tooltipState,
    ) {
        IconButton(onClick = onPublish) {
            Icon(Icons.Filled.PlaylistAddCheck, contentDescription = description)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FusionActionToolbar(
    state: R0dumpUiState,
    horizontal: Boolean = false,
) {
    val status = when {
        state.isInterrupted -> stringResource(R.string.recovery_required)
        else -> stringResource(R.string.validation_error_count, state.validation.errors.size)
    }
    val issueTexts = mutableListOf<String>()
    if (!state.isInterrupted) {
        for ((field, error) in state.validation.errors.entries.take(MAX_VISIBLE_VALIDATION_ISSUES)) {
            issueTexts += stringResource(
                R.string.validation_issue_format,
                validationFieldLabel(field),
                validationMessage(error),
            )
        }
    }
    val hiddenIssueCount = if (state.isInterrupted) 0 else state.validation.errors.size - issueTexts.size

    @Composable
    fun ValidationContent(modifier: Modifier) {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(status, style = MaterialTheme.typography.labelLarge)
                issueTexts.forEach { issue ->
                    Text(
                        "• $issue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (hiddenIssueCount > 0) {
                    Text(
                        stringResource(R.string.validation_more_issues, hiddenIssueCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (horizontal) {
        Surface(tonalElevation = 3.dp) {
            ValidationContent(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    } else {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            leadingContent = {},
        ) {
            ValidationContent(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp))
        }
    }
}

private const val MAX_VISIBLE_VALIDATION_ISSUES = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResetToolButton(enabled: Boolean, onReset: () -> Unit) {
    val tooltipState = rememberTooltipState()
    val description = stringResource(R.string.restore_default_settings)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = tooltipState,
    ) {
        IconButton(onClick = onReset, enabled = enabled) {
            Icon(Icons.Default.Restore, description)
        }
    }
}
