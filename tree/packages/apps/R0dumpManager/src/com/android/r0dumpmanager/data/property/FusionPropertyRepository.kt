package com.android.r0dumpmanager.data.property

import android.app.r0dump_FusionConfig
import android.os.SystemProperties
import com.android.r0dumpmanager.domain.FusionConfigDraft
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.FusionConfigValidator
import com.android.r0dumpmanager.domain.WalkMode
import com.android.r0dumpmanager.domain.WalkStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface FusionDeviceSnapshot {
    data object Unconfigured : FusionDeviceSnapshot
    data class Disabled(val draft: FusionConfigDraft, val generation: Long) : FusionDeviceSnapshot
    data class Enabled(val draft: FusionConfigDraft, val generation: Long) : FusionDeviceSnapshot
    data class Interrupted(val generation: Long) : FusionDeviceSnapshot
    data class Invalid(val reason: String) : FusionDeviceSnapshot
    data object ConcurrentChange : FusionDeviceSnapshot
}

class FusionPropertyRepository(
    private val properties: PropertyAccess = SystemPropertyAccess,
    private val writer: ManagerPropertyWriter = FrameworkManagerPropertyWriter,
) {
    private val mutex = Mutex()

    suspend fun read(): FusionDeviceSnapshot = mutex.withLock { readStable() }

    suspend fun publish(draft: FusionConfigDraft, expectedGeneration: Long): FusionDeviceSnapshot = mutex.withLock {
        val generation = writer.publish(FusionConfigSchema.toJson(draft), expectedGeneration)
        val snapshot = readStable()
        require(snapshot.generationOrNull() == generation && snapshot.draftOrNull() == draft) { "Published snapshot verification failed" }
        snapshot
    }

    suspend fun disable(expectedGeneration: Long): FusionDeviceSnapshot = mutex.withLock {
        val generation = writer.disable(expectedGeneration)
        val snapshot = readStable()
        require(snapshot is FusionDeviceSnapshot.Disabled && snapshot.generation == generation) { "Disable verification failed" }
        snapshot
    }

    suspend fun recoverInterrupted(): FusionDeviceSnapshot = mutex.withLock {
        val generation = writer.recoverDisabled()
        val snapshot = readStable()
        require(snapshot is FusionDeviceSnapshot.Disabled && snapshot.generation == generation) { "Recovery verification failed" }
        snapshot
    }

    private fun readStable(): FusionDeviceSnapshot {
        repeat(3) {
            val before = generation() ?: return FusionDeviceSnapshot.Invalid("generation")
            if ((before and 1L) != 0L) return FusionDeviceSnapshot.Interrupted(before)
            val draft = runCatching { readDraft() }.getOrElse { return FusionDeviceSnapshot.Invalid(it.message ?: "properties") }
            val after = generation() ?: return FusionDeviceSnapshot.Invalid("generation")
            if (before == after) {
                if (before == 0L && draft.targetPackage.isEmpty() && draft.runId.isEmpty()) return FusionDeviceSnapshot.Unconfigured
                val validation = FusionConfigValidator.validate(draft, draft.enabled)
                if (!validation.isValid) {
                    return FusionDeviceSnapshot.Invalid(
                        "validation:" + validation.errors.keys.sorted().joinToString(","),
                    )
                }
                return if (draft.enabled) FusionDeviceSnapshot.Enabled(draft, before) else FusionDeviceSnapshot.Disabled(draft, before)
            }
        }
        return FusionDeviceSnapshot.ConcurrentChange
    }

    private fun generation(): Long? = properties.get("${FusionConfigSchema.PROPERTY_PREFIX}generation", "0").toLongOrNull()?.takeIf { it >= 0L }
    private fun readDraft(): FusionConfigDraft = FusionConfigDraft(
        schema = int("schema", "1"), enabled = bool("enabled", "0"), runId = string("run_id", ""),
        targetPackage = string("target_package", ""), targetProcess = string("target_process", ""), capturePointMask = mask("capture_point_mask", "0x0000020000000028"),
        maxRecords = long("max_records", "512"), maxArtifacts = long("max_artifacts", "128"), maxTotalBytes = long("max_total_bytes", "1073741824"),
        maxDexBytes = long("max_dex_bytes", "268435456"), bufferPoolBytes = long("buffer_pool_bytes", "536870912"), maxCodeItemBytes = long("max_code_item_bytes", "4194304"),
        maxFrozenGenerations = long("max_frozen_generations", "4"), flushIdleMs = long("flush_idle_ms", "500"), manifestEnabled = bool("manifest_enabled", "1"), manifestIncludeSplits = bool("manifest_include_splits", "1"),
        manifestMaxComponents = long("manifest_max_components", "8192"), manifestMaxSplits = long("manifest_max_splits", "32"), manifestMaxEventsPerSource = long("manifest_max_events_per_source", "16384"), manifestMaxTotalEvents = long("manifest_max_total_events", "65536"),
        manifestMaxXmlDepth = long("manifest_max_xml_depth", "64"), manifestMaxAttributeLength = long("manifest_max_attribute_length", "1024"), walkEnabled = bool("walk_enabled", "1"),
        walkStart = WalkStart.values().first { it.propertyValue == string("walk_start", "after_application_on_create") }, walkMode = WalkMode.values().first { it.propertyValue == string("walk_mode", "loaded_only") }, walkNamespacePrefix = string("walk_namespace_prefix", ""),
        walkDelayMs = long("walk_delay_ms", "5000"), walkMaxClasses = long("walk_max_classes", "16384"), walkMaxMethods = long("walk_max_methods", "65536"), forceInvokeEnabled = bool("force_invoke_enabled", "1"),
        forceClass = string("force_class", ""), forceMethod = string("force_method", ""), forceMethodDescriptor = string("force_method_descriptor", ""), forceMaxCalls = long("force_max_calls", "1"), anrProtectionEnabled = bool("anr_protection_enabled", "1"),
    )
    private fun string(field: String, default: String) = properties.get(FusionConfigSchema.PROPERTY_PREFIX + field, default)
    private fun long(field: String, default: String) = string(field, default).toLong()
    private fun int(field: String, default: String): Int {
        val value = long(field, default)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid integer $field" }
        return value.toInt()
    }
    private fun bool(field: String, default: String) = when (string(field, default)) { "1" -> true; "0" -> false; else -> error("Invalid boolean $field") }
    private fun mask(field: String, default: String): ULong { val value = string(field, default); require(Regex("0x[0-9a-f]{16}").matches(value)); return value.substring(2).toULong(16) }
}

private fun FusionDeviceSnapshot.generationOrNull(): Long? = when (this) { is FusionDeviceSnapshot.Enabled -> generation; is FusionDeviceSnapshot.Disabled -> generation; else -> null }
private fun FusionDeviceSnapshot.draftOrNull(): FusionConfigDraft? = when (this) { is FusionDeviceSnapshot.Enabled -> draft; is FusionDeviceSnapshot.Disabled -> draft; else -> null }

interface PropertyAccess { fun get(key: String, default: String): String }
private object SystemPropertyAccess : PropertyAccess { override fun get(key: String, default: String) = SystemProperties.get(key, default) }
interface ManagerPropertyWriter {
    fun publish(rawConfig: String, expectedGeneration: Long): Long
    fun disable(expectedGeneration: Long): Long
    fun recoverDisabled(): Long
}
private object FrameworkManagerPropertyWriter : ManagerPropertyWriter {
    override fun publish(rawConfig: String, expectedGeneration: Long) = r0dump_FusionConfig.r0dump_writePersistedForManager(rawConfig, expectedGeneration)
    override fun disable(expectedGeneration: Long) = r0dump_FusionConfig.r0dump_disablePersistedForManager(expectedGeneration)
    override fun recoverDisabled() = r0dump_FusionConfig.r0dump_recoverDisabledPersistedForManager()
}
