package com.android.r0dumpmanager.domain

enum class ProfileId { DEFAULTS, COOLAPK_COOKIE_WALK, CUSTOM }

object ProfileRegistry {
    fun apply(id: ProfileId, source: FusionConfigDraft = FusionConfigSchema.defaults()): FusionConfigDraft = when (id) {
        ProfileId.DEFAULTS -> FusionConfigSchema.defaults().copy(
            runId = source.runId,
            targetPackage = source.targetPackage,
            targetProcess = source.targetProcess,
        )
        ProfileId.COOLAPK_COOKIE_WALK -> source.copy(
            enabled = true, capturePointMask = 1uL shl 39, walkEnabled = true,
            walkMode = WalkMode.LOADED_ONLY, walkDelayMs = 5000, walkMaxClasses = 1, walkMaxMethods = 1,
            maxRecords = 4096, maxArtifacts = 512, maxTotalBytes = 536_870_912, maxDexBytes = 134_217_728,
            bufferPoolBytes = 134_217_728, forceInvokeEnabled = false, forceClass = "", forceMethod = "",
            forceMethodDescriptor = "", anrProtectionEnabled = false,
        )
        ProfileId.CUSTOM -> source
    }
}
