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

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Minimal stand-in for `androidx.compose.material3.windowsizeclass`.
 *
 * The upstream Manager targets a Compose release that ships the window-size-class artifact as a
 * separate module.  This tree's prebuilt AndroidX snapshot is older (material3 1.1.0-alpha06), so
 * that artifact simply does not exist here and cannot be pulled in without replacing the whole
 * Compose snapshot.
 *
 * Only two facts are needed from it: whether the window is compact (phone portrait-ish) and whether
 * it is expanded (tablet/desktop-ish).  Both are derived directly from the current
 * [Configuration.screenWidthDp], using the same thresholds the Material3 artifact uses, so the
 * layout decisions in the UI code stay identical to upstream.
 */
enum class WindowWidthSizeClass {
    Compact,
    Medium,
    Expanded,
}

/** Width-in-dp breakpoints, matching the Material3 window size class definitions. */
private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840

@Composable
fun calculateWindowWidthSizeClass(): WindowWidthSizeClass {
    val configuration = LocalConfiguration.current
    return windowWidthSizeClassFor(configuration)
}

/** Pure mapping from a configuration to the width class, kept separate so it is testable. */
fun windowWidthSizeClassFor(configuration: Configuration): WindowWidthSizeClass =
    when {
        configuration.screenWidthDp >= EXPANDED_WIDTH_DP -> WindowWidthSizeClass.Expanded
        configuration.screenWidthDp >= MEDIUM_WIDTH_DP -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Compact
    }
