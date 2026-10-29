/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.r0dumpmanager.compat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Source-compatibility shims for Compose/Material3 APIs that this tree's AndroidX snapshot does
 * not ship.
 *
 * The upstream Manager is built against Material3 1.3+, which added several widgets and helpers.
 * This ROM's prebuilt AndroidX snapshot is Material3 1.1.0-alpha06 / Compose 1.4.0-beta01, and
 * replacing that snapshot would force a Compose upgrade across SystemUI as well. Instead, the few
 * missing entry points used by the Manager are reimplemented here with the same names and the same
 * observable behaviour, so the upstream UI code stays intact.
 */

/**
 * Replaces `androidx.compose.runtime.mutableIntStateOf`.
 *
 * The newer overload exists to avoid boxing primitive Int state. Behaviourally it is identical to
 * `mutableStateOf(0)`, which is what this delegates to.
 */
@Composable
fun compatMutableIntStateOf(value: Int = 0): MutableState<Int> =
    remember { mutableStateOf(value) }

/**
 * Replaces `androidx.compose.material3.windowsizeclass`'s opt-in marker.
 *
 * Nothing in the reimplementation is experimental, so the annotation is an inert marker kept only
 * so upstream `@OptIn(...)` sites compile unchanged.
 */
@RequiresOptIn(
    message = "Window size class support is provided by this app's compat layer.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalWindowSizeClassApi

/**
 * Replaces `androidx.compose.material3.ExperimentalMaterial3ExpressiveApi`.
 *
 * Upstream marks the Material 3 Expressive widgets with this; the equivalents implemented in this
 * package are plain stable composables.
 */
@RequiresOptIn(
    message = "Material3 Expressive widgets are provided by this app's compat layer.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalMaterial3ExpressiveApi

/**
 * Replaces `androidx.compose.material3.HorizontalDivider`.
 *
 * The 1.1 API spells this `Divider`. Drawing a one-pixel line with the outline colour is what both
 * implementations do.
 */
@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: androidx.compose.ui.unit.Dp = 1.dp,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outlineVariant,
) {
    androidx.compose.material3.Divider(modifier = modifier, thickness = thickness, color = color)
}

/**
 * Replaces `androidx.compose.material3.LoadingIndicator`.
 *
 * 1.3 draws an animated shape; the closest stable widget in 1.1 is the circular progress
 * indicator, which conveys the same "work in progress" state.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    androidx.compose.material3.CircularProgressIndicator(modifier = modifier)
}

/**
 * Replaces `androidx.compose.material3.PlainTooltip`.
 *
 * Material3 1.1 has no tooltip composable at all. This renders the reference text in a small
 * elevated surface styled like the newer tooltip, positioned by the caller.
 */
@Composable
fun PlainTooltip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 2.dp,
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides
                    MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                content()
            }
        }
    }
}

/**
 * Replaces `androidx.compose.material3.TooltipBox` for the single-tooltip case the Manager uses.
 *
 * Material3 1.1 keeps `TooltipBox` private, so it cannot be called. This renders the anchor content
 * and places `tooltip` beneath it when the state is visible; `TooltipState` here is the local
 * minimal equivalent, so the call sites keep their shape.
 */
@Composable
fun TooltipBox(
    positionProvider: androidx.compose.ui.window.PopupPositionProvider,
    tooltip: @Composable () -> Unit,
    state: TooltipState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        if (state.isVisible) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { state.dismiss() },
            ) {
                tooltip()
            }
        }
    }
}

/**
 * Minimal replacement for `androidx.compose.material3.TooltipState`.
 *
 * Only the surface the Manager touches is modelled: a visibility flag that can be dismissed.
 */
class TooltipState internal constructor(initialVisible: Boolean = false) {
    var isVisible: Boolean by mutableStateOf(initialVisible)
        private set

    fun show() {
        isVisible = true
    }

    fun dismiss() {
        isVisible = false
    }
}

/** Replaces `androidx.compose.material3.rememberTooltipState`. */
@Composable
fun rememberTooltipState(): TooltipState = remember { TooltipState() }

/**
 * Replaces `androidx.compose.material3.SegmentedButton` and friends.
 *
 * Material3 1.1 has no segmented button. This renders a single-select row of outlined buttons that
 * mirrors the newer widget: the selected item is filled, the others are outlined, and the row is
 * split evenly across the available width.
 */
@Composable
fun SingleChoiceSegmentedButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    // The upstream scope type extends RowScope, so the content lambda is given RowScope here as
    // well -- that is what makes `Modifier.weight(1f)` inside the button call sites resolve.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    if (selected) {
        androidx.compose.material3.Button(
            onClick = onClick,
            shape = shape,
            modifier = modifier,
            enabled = enabled,
            content = { label() },
        )
    } else {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            shape = shape,
            modifier = modifier,
            enabled = enabled,
            content = { label() },
        )
    }
}

/** Replaces `androidx.compose.material3.SegmentedButtonDefaults`. */
object SegmentedButtonDefaults {
    /** The newer API computes per-item corner rounding; a uniform small shape is equivalent here. */
    @Composable
    fun itemShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape =
        MaterialTheme.shapes.small

    @Composable
    fun baseShape(): androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small
}

/**
 * Replaces `androidx.compose.material3.HorizontalFloatingToolbar`.
 *
 * The 1.3 widget is a floating action bar. This renders an elevated surface with the same
 * arrangement so the Manager's toolbar slot keeps its visual role.
 */
@Composable
fun HorizontalFloatingToolbar(
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    leadingContent: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // leadingContent sits before the main content, matching the newer widget's slot order.
            if (leadingContent != null) {
                leadingContent()
            }
            content()
        }
    }
}

/**
 * Replaces `androidx.compose.material3.ExposedDropdownMenuAnchorType`.
 *
 * In 1.1 the anchor behaviour was expressed by the zero-argument `Modifier.menuAnchor()`; the newer
 * API turned it into a parameter. The constants exist so the upstream call sites can pass one, and
 * the mapped modifier below ignores the distinction because 1.1 has a single behaviour.
 */
enum class ExposedDropdownMenuAnchorType {
    PrimaryEditable,
    PrimaryNotEditable,
    SecondaryEditable,
}

/**
 * `Modifier.menuAnchor(anchorType, enabled)` for the 1.1 API.
 *
 * In 1.1 the anchor modifier is a member of [androidx.compose.material3.ExposedDropdownMenuBoxScope]
 * and takes no arguments (the enabled flag comes from the text field itself).  The newer API made
 * the anchor type an explicit parameter, so this adapter accepts it inside the scope and delegates
 * to the 1.1 behaviour, which is a single implementation for all anchor types.
 */


/** Replaces the 1.3 `TooltipDefaults.rememberTooltipPositionProvider`. */
object TooltipDefaults {
    /**
     * The newer helper picks above/below the anchor based on available space. This tree's Material3
     * only ships the colour/shape accessors, so the position provider is supplied directly here;
     * placing the tooltip below the anchor is the behaviour the Manager relies on.
     */
    @Composable
    fun rememberTooltipPositionProvider(): androidx.compose.ui.window.PopupPositionProvider =
        remember { BelowAnchorPositionProvider }

    private object BelowAnchorPositionProvider :
        androidx.compose.ui.window.PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: androidx.compose.ui.unit.IntRect,
            windowSize: androidx.compose.ui.unit.IntSize,
            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
            popupContentSize: androidx.compose.ui.unit.IntSize,
        ): androidx.compose.ui.unit.IntOffset =
            androidx.compose.ui.unit.IntOffset(
                x = anchorBounds.left,
                y = anchorBounds.bottom,
            )
    }
}

/**
 * Replaces `androidx.compose.material3.MaterialExpressiveTheme`.
 *
 * The 1.3 Expressive theme is a MaterialTheme preset that additionally installs a motion scheme.
 * Neither the extra parameter nor the motion system exists in 1.1, so this is MaterialTheme with
 * the same colour/typography arguments.
 */
@Composable
fun MaterialExpressiveTheme(
    colorScheme: androidx.compose.material3.ColorScheme = androidx.compose.material3
        .MaterialTheme.colorScheme,
    motionScheme: Any? = null,
    shapes: androidx.compose.material3.Shapes = androidx.compose.material3.MaterialTheme.shapes,
    typography: androidx.compose.material3.Typography =
        androidx.compose.material3.MaterialTheme.typography,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = typography,
        content = content,
    )
}

/**
 * Replaces `androidx.compose.material3.MotionScheme`.
 *
 * Motion specs are consumed by newer component internals only; the Manager just passes one in, so
 * an inert holder is enough for the call site to compile and behave as plain Material3.
 */
class MotionScheme private constructor() {
    companion object {
        fun standard(): MotionScheme = MotionScheme()
        fun expressive(): MotionScheme = MotionScheme()
    }
}

/**
 * Replaces `androidx.activity.compose.PredictiveBackHandler`.
 *
 * The 1.8 activity-compose API runs a suspend block while the user swipes back, reporting progress.
 * This tree's activity-compose predates it. The fallback registers a plain `BackHandler`, which
 * preserves the important behaviour -- back is routed to the app instead of finishing the activity
 * -- and reports a completed gesture immediately, skipping the animated progress reporting.
 */
@Composable
fun PredictiveBackHandler(
    enabled: Boolean = true,
    onBack: suspend (progress: kotlinx.coroutines.flow.Flow<BackEventCompat>) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = enabled) {
        scope.launch {
            // Upstream receives a stream of gesture events and collects them, so the shim emits a
            // single completed event and closes the flow.  Code that awaits the first element or
            // collects to completion behaves identically.
            onBack(kotlinx.coroutines.flow.flowOf(BackEventCompat()))
        }
    }
}

/**
 * Minimal stand-in for `androidx.activity.BackEventCompat`.
 *
 * It only exists from activity 1.8; this tree ships 1.5.0-alpha03. The Manager reads the progress
 * value to drive its own gesture animation, so the value is modelled as a completed gesture (1.0),
 * which is the state a non-predictive back callback corresponds to.
 */
class BackEventCompat(
    val progress: Float = 1.0f,
    val swipeEdge: Int = 0,
    val touchX: Float = 0.0f,
    val touchY: Float = 0.0f,
) {
    companion object {
        const val EDGE_LEFT = 0
        const val EDGE_RIGHT = 1
    }
}

/**
 * Replaces `androidx.activity.enableEdgeToEdge`.
 *
 * The activity 1.8 helper configures a transparent system bar / window insets combination that the
 * Manager's layout relies on. This tree ships activity 1.5.0-alpha03, so the equivalent window flags
 * are set directly: the content draws behind the system bars and the bars themselves are made
 * transparent, which is what the newer helper does on API 33.
 */
fun androidx.activity.ComponentActivity.enableEdgeToEdge() {
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
}

/**
 * Replaces `androidx.compose.material3.rememberModalBottomSheetState`.
 *
 * Material3 1.1 has no remembering helper: `ModalBottomSheet` takes a `SheetState` and callers
 * construct it themselves. The newer helper's `skipPartiallyExpanded` flag is what the Manager
 * relies on, so it is accepted here and used to pick the initial sheet value.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun rememberModalBottomSheetState(
    skipPartiallyExpanded: Boolean = false,
    confirmValueChange: (androidx.compose.material3.SheetValue) -> Boolean = { true },
): androidx.compose.material3.SheetState {
    // Material3 1.1 names the first parameter skipCollapsed (it controls whether the sheet can
    // stop at the partially-expanded anchor); later releases renamed it to skipPartiallyExpanded.
    // The meanings line up, so the flag passes straight through.
    return remember(skipPartiallyExpanded) {
        androidx.compose.material3.SheetState(
            skipCollapsed = skipPartiallyExpanded,
            confirmValueChange = confirmValueChange,
        )
    }
}
