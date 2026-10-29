package com.android.r0dumpmanager.domain

/** Complete manager-owned representation of the Framework's strict JSON schema. */
data class FusionConfigDraft(
    val schema: Int = 1,
    val enabled: Boolean = false,
    val runId: String = "",
    val targetPackage: String = "",
    val targetProcess: String = "",
    val capturePointMask: ULong = CapturePointRegistry.defaultMask,
    // Whole-DEX captures across a multi-dex app plus its dynamically loaded shells
    // need headroom, so the baseline runs at the validator ceiling (4096/512).
    val maxRecords: Long = 4096,
    val maxArtifacts: Long = 512,
    val maxTotalBytes: Long = 1_073_741_824,
    val maxDexBytes: Long = 268_435_456,
    val bufferPoolBytes: Long = 536_870_912,
    val maxCodeItemBytes: Long = 4_194_304,
    val maxFrozenGenerations: Long = 4,
    val flushIdleMs: Long = 500,
    val manifestEnabled: Boolean = true,
    val manifestIncludeSplits: Boolean = true,
    val manifestMaxComponents: Long = 8192,
    val manifestMaxSplits: Long = 32,
    val manifestMaxEventsPerSource: Long = 16384,
    val manifestMaxTotalEvents: Long = 65536,
    val manifestMaxXmlDepth: Long = 64,
    val manifestMaxAttributeLength: Long = 1024,
    val walkEnabled: Boolean = true,
    val walkStart: WalkStart = WalkStart.AFTER_APPLICATION_ON_CREATE,
    // Walk every class named in the DEX, not only the already-loaded ones, so
    // force-invoke reaches lazily-restored second-generation methods.
    val walkMode: WalkMode = WalkMode.DEX_NAMES,
    val walkNamespacePrefix: String = "",
    val walkDelayMs: Long = 5000,
    val walkMaxClasses: Long = 16_384,
    val walkMaxMethods: Long = 65_536,
    val forceInvokeEnabled: Boolean = true,
    val forceClass: String = "",
    val forceMethod: String = "",
    val forceMethodDescriptor: String = "",
    val forceMaxCalls: Long = 1,
    val anrProtectionEnabled: Boolean = true,
)

enum class WalkStart(val propertyValue: String) {
    AFTER_APPLICATION_ON_CREATE("after_application_on_create"),
    AFTER_FIRST_ACTIVITY_ON_CREATE("after_first_activity_on_create"),
}

enum class WalkMode(val propertyValue: String) {
    LOADED_ONLY("loaded_only"),
    DEX_NAMES("dex_names"),
}
