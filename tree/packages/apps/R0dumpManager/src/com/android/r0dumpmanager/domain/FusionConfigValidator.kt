package com.android.r0dumpmanager.domain

enum class FusionValidationErrorCode {
    RANGE,
    UNSUPPORTED_SCHEMA,
    INVALID_RUN_ID,
    INVALID_TARGET_PACKAGE,
    INVALID_TARGET_PROCESS,
    CAPTURE_POINT_REQUIRED,
    UNDEFINED_CAPTURE_POINT,
    EXCEEDS_MAX_RECORDS,
    EXCEEDS_TOTAL_BYTES,
    BELOW_MAX_DEX_BYTES,
    EXCEEDS_BUFFER_POOL,
    BELOW_EVENTS_PER_SOURCE,
    FORCE_BIT_REQUIRED,
    FORCE_SCOPE_REQUIRED,
    INVALID_BINARY_CLASS,
    INVALID_NAMESPACE_PREFIX,
    INVALID_METHOD_NAME,
    INVALID_METHOD_DESCRIPTOR,
    PROPERTY_VALUE_TOO_LONG,
}

data class FusionValidationError(
    val code: FusionValidationErrorCode,
    val min: Long = 0,
    val max: Long = 0,
)

data class FusionValidationResult(val errors: Map<String, FusionValidationError>) {
    val isValid get() = errors.isEmpty()
}

object FusionConfigValidator {
    const val MAX_RECORDS = 4096L
    const val MAX_ARTIFACTS = 512L
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    const val MAX_DEX_BYTES = 256L * 1024 * 1024
    const val MAX_BUFFER_POOL_BYTES = 512L * 1024 * 1024
    const val MAX_CODE_ITEM_BYTES = 4L * 1024 * 1024
    const val MAX_FROZEN_GENERATIONS = 4L
    const val MAX_FLUSH_IDLE_MS = 60_000L
    private val runId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val binaryClass = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val namespacePrefix = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.")
    private val methodName = Regex("[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>")

    fun isValidRunId(value: String): Boolean = runId.matches(value)

    fun validate(draft: FusionConfigDraft, requireEnabled: Boolean = draft.enabled): FusionValidationResult {
        val e = linkedMapOf<String, FusionValidationError>()
        fun error(field: String, code: FusionValidationErrorCode) {
            e[field] = FusionValidationError(code)
        }
        fun range(field: String, value: Long, min: Long, max: Long) {
            if (value !in min..max) e[field] = FusionValidationError(FusionValidationErrorCode.RANGE, min, max)
        }
        if (draft.schema != FusionConfigSchema.SCHEMA_VERSION) error("schema", FusionValidationErrorCode.UNSUPPORTED_SCHEMA)
        if (!isValidRunId(draft.runId)) error("run_id", FusionValidationErrorCode.INVALID_RUN_ID)
        if (!packageName.matches(draft.targetPackage)) error("target_package", FusionValidationErrorCode.INVALID_TARGET_PACKAGE)
        if (draft.targetProcess.isNotEmpty() && draft.targetProcess != draft.targetPackage &&
            !Regex("${Regex.escape(draft.targetPackage)}:[A-Za-z_][A-Za-z0-9_]*").matches(draft.targetProcess)) {
            error("target_process", FusionValidationErrorCode.INVALID_TARGET_PROCESS)
        }
        if (requireEnabled && draft.capturePointMask == 0uL) error("capture_point_mask", FusionValidationErrorCode.CAPTURE_POINT_REQUIRED)
        if ((draft.capturePointMask and CapturePointRegistry.undefinedMask) != 0uL) error("capture_point_mask", FusionValidationErrorCode.UNDEFINED_CAPTURE_POINT)
        range("max_records", draft.maxRecords, 1, MAX_RECORDS)
        range("max_artifacts", draft.maxArtifacts, 1, MAX_ARTIFACTS)
        range("max_total_bytes", draft.maxTotalBytes, 1, MAX_TOTAL_BYTES)
        range("max_dex_bytes", draft.maxDexBytes, 1, MAX_DEX_BYTES)
        range("buffer_pool_bytes", draft.bufferPoolBytes, 1, MAX_BUFFER_POOL_BYTES)
        range("max_code_item_bytes", draft.maxCodeItemBytes, 1, MAX_CODE_ITEM_BYTES)
        range("max_frozen_generations", draft.maxFrozenGenerations, 1, MAX_FROZEN_GENERATIONS)
        range("flush_idle_ms", draft.flushIdleMs, 1, MAX_FLUSH_IDLE_MS)
        range("manifest_max_components", draft.manifestMaxComponents, 1, 16384)
        range("manifest_max_splits", draft.manifestMaxSplits, 0, 32)
        range("manifest_max_events_per_source", draft.manifestMaxEventsPerSource, 1, 262144)
        range("manifest_max_total_events", draft.manifestMaxTotalEvents, 1, 1048576)
        range("manifest_max_xml_depth", draft.manifestMaxXmlDepth, 1, 256)
        range("manifest_max_attribute_length", draft.manifestMaxAttributeLength, 1, 4096)
        range("walk_delay_ms", draft.walkDelayMs, 0, 0xffff_ffffL)
        range("walk_max_classes", draft.walkMaxClasses, 1, 1_000_000)
        range("walk_max_methods", draft.walkMaxMethods, 1, 10_000_000)
        range("force_max_calls", draft.forceMaxCalls, 1, 10_000_000)
        if (draft.walkNamespacePrefix.isNotEmpty() && !namespacePrefix.matches(draft.walkNamespacePrefix)) {
            error("walk_namespace_prefix", FusionValidationErrorCode.INVALID_NAMESPACE_PREFIX)
        }
        if (draft.maxArtifacts > draft.maxRecords) error("max_artifacts", FusionValidationErrorCode.EXCEEDS_MAX_RECORDS)
        if (draft.maxDexBytes > draft.maxTotalBytes) {
            error("max_total_bytes", FusionValidationErrorCode.EXCEEDS_TOTAL_BYTES)
            error("max_dex_bytes", FusionValidationErrorCode.EXCEEDS_TOTAL_BYTES)
        }
        if (draft.bufferPoolBytes < draft.maxDexBytes) {
            error("max_dex_bytes", FusionValidationErrorCode.BELOW_MAX_DEX_BYTES)
            error("buffer_pool_bytes", FusionValidationErrorCode.BELOW_MAX_DEX_BYTES)
        }
        if (draft.maxCodeItemBytes > draft.bufferPoolBytes) {
            error("buffer_pool_bytes", FusionValidationErrorCode.EXCEEDS_BUFFER_POOL)
            error("max_code_item_bytes", FusionValidationErrorCode.EXCEEDS_BUFFER_POOL)
        }
        if (draft.manifestMaxTotalEvents < draft.manifestMaxEventsPerSource) error("manifest_max_total_events", FusionValidationErrorCode.BELOW_EVENTS_PER_SOURCE)
        if (draft.forceInvokeEnabled) {
            if ((draft.capturePointMask and CapturePointRegistry.forceNoExecuteMask) == 0uL) error("capture_point_mask", FusionValidationErrorCode.FORCE_BIT_REQUIRED)
            val selectorEmpty = draft.forceClass.isEmpty() && draft.forceMethod.isEmpty() && draft.forceMethodDescriptor.isEmpty()
            if (selectorEmpty) {
                if (!draft.manifestEnabled) error("force_class", FusionValidationErrorCode.FORCE_SCOPE_REQUIRED)
            } else {
                if (!binaryClass.matches(draft.forceClass)) error("force_class", FusionValidationErrorCode.INVALID_BINARY_CLASS)
                if (!methodName.matches(draft.forceMethod)) error("force_method", FusionValidationErrorCode.INVALID_METHOD_NAME)
                if (!isMethodDescriptor(draft.forceMethodDescriptor)) error("force_method_descriptor", FusionValidationErrorCode.INVALID_METHOD_DESCRIPTOR)
            }
        }
        listOf("run_id" to draft.runId, "target_package" to draft.targetPackage, "target_process" to draft.targetProcess,
            "force_class" to draft.forceClass, "force_method" to draft.forceMethod, "force_method_descriptor" to draft.forceMethodDescriptor,
            "walk_namespace_prefix" to draft.walkNamespacePrefix)
            .filter { it.second.toByteArray(Charsets.UTF_8).size > FusionConfigSchema.PROPERTY_VALUE_MAX_UTF8_BYTES }
            .forEach { error(it.first, FusionValidationErrorCode.PROPERTY_VALUE_TOO_LONG) }
        return FusionValidationResult(e)
    }

    /** Matches the descriptor grammar accepted by ART: (field*) field-or-void. */
    private fun isMethodDescriptor(value: String): Boolean {
        if (!value.startsWith('(')) return false
        val end = value.indexOf(')')
        if (end < 1 || end == value.lastIndex) return false
        fun consumeField(start: Int, allowVoid: Boolean): Int {
            var index = start
            while (index < value.length && value[index] == '[') index++
            if (index >= value.length) return -1
            return when (value[index]) {
                'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> index + 1
                'V' -> if (allowVoid && index == start) index + 1 else -1
                'L' -> {
                    val semi = value.indexOf(';', index + 1)
                    if (semi <= index + 1 || value.substring(index + 1, semi).any { it == '.' || it == '[' || it == ';' }) -1 else semi + 1
                }
                else -> -1
            }
        }
        var index = 1
        while (index < end) {
            index = consumeField(index, allowVoid = false)
            if (index < 0 || index > end) return false
        }
        val returnEnd = consumeField(end + 1, allowVoid = true)
        return returnEnd == value.length
    }
}
