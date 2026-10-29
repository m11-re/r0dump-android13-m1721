package com.android.r0dumpmanager.domain

import org.json.JSONObject

object FusionConfigSchema {
    const val SCHEMA_VERSION = 1
    const val PROPERTY_PREFIX = "persist.sys.r0dump.fusion."
    const val PROPERTY_VALUE_MAX_UTF8_BYTES = 91
    const val FIELD_COUNT = 35

    val fields = listOf(
        "schema", "enabled", "run_id", "target_package", "target_process", "capture_point_mask",
        "max_records", "max_artifacts", "max_total_bytes", "max_dex_bytes", "buffer_pool_bytes",
        "max_code_item_bytes", "max_frozen_generations", "flush_idle_ms", "manifest_enabled",
        "manifest_include_splits", "manifest_max_components", "manifest_max_splits",
        "manifest_max_events_per_source", "manifest_max_total_events", "manifest_max_xml_depth",
        "manifest_max_attribute_length", "walk_enabled", "walk_start", "walk_mode", "walk_namespace_prefix", "walk_delay_ms",
        "walk_max_classes", "walk_max_methods", "force_invoke_enabled", "force_class", "force_method",
        "force_method_descriptor", "force_max_calls", "anr_protection_enabled",
    )

    fun defaults() = FusionConfigDraft()

    fun restoreDefaultBudgets(draft: FusionConfigDraft): FusionConfigDraft {
        val defaults = defaults()
        return draft.copy(
            maxRecords = defaults.maxRecords,
            maxArtifacts = defaults.maxArtifacts,
            maxTotalBytes = defaults.maxTotalBytes,
            maxDexBytes = defaults.maxDexBytes,
            bufferPoolBytes = defaults.bufferPoolBytes,
            maxCodeItemBytes = defaults.maxCodeItemBytes,
            maxFrozenGenerations = defaults.maxFrozenGenerations,
            flushIdleMs = defaults.flushIdleMs,
        )
    }

    fun maskText(mask: ULong): String = "0x" + mask.toString(16).padStart(16, '0')

    /** Restores only the exhaustive draft representation kept in SavedStateHandle. */
    fun fromJson(raw: String): FusionConfigDraft {
        val json = JSONObject(raw)
        require(json.length() == FIELD_COUNT)
        require(json.keys().asSequence().toSet() == fields.toSet())
        return FusionConfigDraft(
            schema = json.getInt("schema"), enabled = json.getBoolean("enabled"),
            runId = json.getString("run_id"), targetPackage = json.getString("target_package"),
            targetProcess = json.getString("target_process"),
            capturePointMask = json.getString("capture_point_mask").removePrefix("0x").toULong(16),
            maxRecords = json.getLong("max_records"), maxArtifacts = json.getLong("max_artifacts"),
            maxTotalBytes = json.getLong("max_total_bytes"), maxDexBytes = json.getLong("max_dex_bytes"),
            bufferPoolBytes = json.getLong("buffer_pool_bytes"), maxCodeItemBytes = json.getLong("max_code_item_bytes"),
            maxFrozenGenerations = json.getLong("max_frozen_generations"), flushIdleMs = json.getLong("flush_idle_ms"),
            manifestEnabled = json.getBoolean("manifest_enabled"), manifestIncludeSplits = json.getBoolean("manifest_include_splits"),
            manifestMaxComponents = json.getLong("manifest_max_components"), manifestMaxSplits = json.getLong("manifest_max_splits"),
            manifestMaxEventsPerSource = json.getLong("manifest_max_events_per_source"), manifestMaxTotalEvents = json.getLong("manifest_max_total_events"),
            manifestMaxXmlDepth = json.getLong("manifest_max_xml_depth"), manifestMaxAttributeLength = json.getLong("manifest_max_attribute_length"),
            walkEnabled = json.getBoolean("walk_enabled"),
            walkStart = WalkStart.values().first { it.propertyValue == json.getString("walk_start") },
            walkMode = WalkMode.values().first { it.propertyValue == json.getString("walk_mode") },
            walkNamespacePrefix = json.getString("walk_namespace_prefix"),
            walkDelayMs = json.getLong("walk_delay_ms"), walkMaxClasses = json.getLong("walk_max_classes"),
            walkMaxMethods = json.getLong("walk_max_methods"), forceInvokeEnabled = json.getBoolean("force_invoke_enabled"),
            forceClass = json.getString("force_class"), forceMethod = json.getString("force_method"),
            forceMethodDescriptor = json.getString("force_method_descriptor"), forceMaxCalls = json.getLong("force_max_calls"),
            anrProtectionEnabled = json.getBoolean("anr_protection_enabled"),
        )
    }

    /** Framework rejects omitted/unknown fields, so serialization is deliberately exhaustive. */
    fun toJson(draft: FusionConfigDraft): String = JSONObject().apply {
        put("schema", draft.schema); put("enabled", draft.enabled); put("run_id", draft.runId)
        put("target_package", draft.targetPackage); put("target_process", draft.targetProcess)
        put("capture_point_mask", maskText(draft.capturePointMask)); put("max_records", draft.maxRecords)
        put("max_artifacts", draft.maxArtifacts); put("max_total_bytes", draft.maxTotalBytes)
        put("max_dex_bytes", draft.maxDexBytes); put("buffer_pool_bytes", draft.bufferPoolBytes)
        put("max_code_item_bytes", draft.maxCodeItemBytes); put("max_frozen_generations", draft.maxFrozenGenerations)
        put("flush_idle_ms", draft.flushIdleMs); put("manifest_enabled", draft.manifestEnabled)
        put("manifest_include_splits", draft.manifestIncludeSplits); put("manifest_max_components", draft.manifestMaxComponents)
        put("manifest_max_splits", draft.manifestMaxSplits); put("manifest_max_events_per_source", draft.manifestMaxEventsPerSource)
        put("manifest_max_total_events", draft.manifestMaxTotalEvents); put("manifest_max_xml_depth", draft.manifestMaxXmlDepth)
        put("manifest_max_attribute_length", draft.manifestMaxAttributeLength); put("walk_enabled", draft.walkEnabled)
        put("walk_start", draft.walkStart.propertyValue); put("walk_mode", draft.walkMode.propertyValue)
        put("walk_namespace_prefix", draft.walkNamespacePrefix)
        put("walk_delay_ms", draft.walkDelayMs); put("walk_max_classes", draft.walkMaxClasses)
        put("walk_max_methods", draft.walkMaxMethods); put("force_invoke_enabled", draft.forceInvokeEnabled)
        put("force_class", draft.forceClass); put("force_method", draft.forceMethod)
        put("force_method_descriptor", draft.forceMethodDescriptor); put("force_max_calls", draft.forceMaxCalls)
        put("anr_protection_enabled", draft.anrProtectionEnabled)
    }.toString()
}
