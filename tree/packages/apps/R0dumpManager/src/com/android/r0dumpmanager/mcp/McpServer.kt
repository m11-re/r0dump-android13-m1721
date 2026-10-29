package com.android.r0dumpmanager.mcp

import com.android.r0dumpmanager.data.export.ArtifactExportException
import com.android.r0dumpmanager.data.export.ArtifactExportRepository
import com.android.r0dumpmanager.data.export.ArtifactExportResult
import com.android.r0dumpmanager.data.packageinfo.PackageRepository
import com.android.r0dumpmanager.data.property.FusionDeviceSnapshot
import com.android.r0dumpmanager.data.property.FusionPropertyRepository
import com.android.r0dumpmanager.domain.FusionConfigDraft
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.FusionConfigValidator
import com.android.r0dumpmanager.domain.ProfileId
import com.android.r0dumpmanager.domain.ProfileRegistry
import com.android.r0dumpmanager.domain.WalkMode
import com.android.r0dumpmanager.domain.WalkStart
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal MCP (JSON-RPC 2.0) server exposing the r0dump pipeline as tools.
 *
 * Transport is newline-delimited JSON handled by [McpBridgeService]; this class
 * is transport-agnostic and turns one request message into one response string
 * (or null for notifications). It reuses the very repositories the UI uses, so a
 * config published over MCP goes through the same validation, generation-sandwich
 * write and run-id management as the on-screen flow.
 */
class McpServer(
    private val properties: FusionPropertyRepository,
    private val packages: PackageRepository,
    private val exports: ArtifactExportRepository,
) {
    /** Handle one JSON-RPC message. Returns the response JSON, or null for a notification. */
    suspend fun handle(message: String): String? {
        val request =
            try {
                JSONObject(message)
            } catch (_: Exception) {
                return error(JSONObject.NULL, PARSE_ERROR, "parse error").toString()
            }
        if (!request.has("id") || request.isNull("id")) {
            return null // Notification (e.g. notifications/initialized): no reply.
        }
        val id = request.get("id")
        val method = request.optString("method")
        return try {
            when (method) {
                "initialize" -> success(id, initializeResult())
                "ping" -> success(id, JSONObject())
                "tools/list" -> success(id, JSONObject().put("tools", toolsList()))
                "tools/call" -> success(id, callTool(request.optJSONObject("params") ?: JSONObject()))
                else -> error(id, METHOD_NOT_FOUND, "method not found: $method")
            }.toString()
        } catch (e: Exception) {
            error(id, INTERNAL_ERROR, e.message ?: "internal error").toString()
        }
    }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", "r0dump-manager").put("version", "1"))

    private fun toolsList(): JSONArray {
        fun tool(name: String, description: String, properties: JSONObject, required: JSONArray) =
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", properties)
                        .put("required", required),
                )
        val none = JSONObject()
        val empty = JSONArray()
        return JSONArray()
            .put(tool("read_status", "Read the device fusion config, generation, enabled state and validation.", none, empty))
            .put(
                tool(
                    "list_apps",
                    "List installed applications (rescans package manager each call). Optional case-insensitive 'query' filters by label or package.",
                    JSONObject().put("query", strProp("substring to filter apps by label or package")),
                    empty,
                ),
            )
            .put(
                tool(
                    "set_config",
                    "Build and publish a fusion config. 'profile' (defaults|coolapk|custom) seeds the draft; " +
                        "then ANY of the 35 schema fields (by name, e.g. force_class, walk_delay_ms, " +
                        "manifest_max_components) overrides it. Listed fields below are common shortcuts.",
                    JSONObject()
                        .put("package", strProp("target package name"))
                        .put("process", strProp("'main', 'all', or an explicit process name"))
                        .put("profile", strProp("defaults | coolapk | custom (default: custom, keep current)"))
                        .put("capture_mask", strProp("capture_point_mask as 0x-hex or decimal"))
                        .put("max_records", intProp("record budget"))
                        .put("max_artifacts", intProp("artifact budget"))
                        .put("walk_enabled", boolProp("enable the class-loader walk"))
                        .put("walk_mode", strProp("loaded_only (walk loaded classes) | dex_names"))
                        .put("walk_start", strProp("app (after Application.onCreate) | activity (after first Activity)"))
                        .put("walk_delay_ms", intProp("delay before the walk, so a packer finishes loading its classes"))
                        .put("walk_max_classes", intProp("max classes the walk visits"))
                        .put("walk_max_methods", intProp("max methods actively invoked"))
                        .put("force_invoke", boolProp("actively invoke methods (no-execute) to materialize hidden code"))
                        .put("force_max_calls", intProp("max force-invoke calls"))
                        .put("anr_protection", boolProp("keep ANR protection on during heavy active invocation"))
                        .put("enabled", boolProp("publish enabled (default: keep current)")),
                    JSONArray().put("package"),
                ),
            )
            .put(tool("enable", "Publish the current draft with capture enabled.", none, empty))
            .put(tool("disable", "Disable capture on the device.", none, empty))
            .put(
                tool(
                    "launch_app",
                    "Cold-start the target app (so its shell decrypts/loads and capture fires).",
                    JSONObject().put("package", strProp("package to launch (default: current target)")),
                    empty,
                ),
            )
            .put(
                tool(
                    "stop_app",
                    "Force-stop the target app and wait for it to die.",
                    JSONObject().put("package", strProp("package to stop (default: current target)")),
                    empty,
                ),
            )
            .put(tool("recover", "Recover a config left in the interrupted (odd-generation) state.", none, empty))
            .put(
                tool(
                    "repair_export",
                    "Repair and export the configured run; returns verified/usable counts and the archive path.",
                    JSONObject().put("package", strProp("package to export (default: current target)")),
                    empty,
                ),
            )
    }

    private suspend fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val payload = when (name) {
                "read_status" -> readStatus()
                "list_apps", "rescan_apps" -> listApps(args.optString("query"))
                "set_config" -> setConfig(args)
                "enable" -> enable()
                "disable" -> disable()
                "launch_app" -> launchApp(args)
                "stop_app" -> stopApp(args)
                "recover" -> recover()
                "repair_export" -> repairExport(args)
                else -> return toolError("unknown tool: $name")
            }
            toolResult(payload, isError = false)
        } catch (e: ArtifactExportException) {
            toolError("export failed: status=${e.status} detail=${e.detail}")
        } catch (e: Exception) {
            toolError(e.message ?: "tool error")
        }
    }

    private suspend fun readStatus(): JSONObject = snapshotJson(properties.read())

    private fun listApps(query: String?): JSONObject {
        val needle = query?.trim().orEmpty()
        val apps = packages.loadInstalledApplications()
            .filter {
                needle.isEmpty() || it.label.contains(needle, true) || it.packageName.contains(needle, true)
            }
        val array = JSONArray()
        for (app in apps) {
            array.put(
                JSONObject()
                    .put("package", app.packageName)
                    .put("label", app.label)
                    .put("launchable", app.launchable)
                    .put("system", app.isSystem),
            )
        }
        return JSONObject().put("count", array.length()).put("apps", array)
    }

    private suspend fun setConfig(args: JSONObject): JSONObject {
        if (!args.has("package")) throw IllegalArgumentException("package is required")
        val snapshot = properties.read()
        val generation = snapshot.generationOrNull() ?: 0L
        val current = snapshot.draftOrNull() ?: FusionConfigSchema.defaults()
        val profile = when (args.optString("profile", "custom").lowercase()) {
            "defaults", "default" -> ProfileId.DEFAULTS
            "coolapk", "coolapk_cookie_walk", "cookie" -> ProfileId.COOLAPK_COOKIE_WALK
            else -> ProfileId.CUSTOM
        }
        val seeded = if (profile == ProfileId.CUSTOM) current else ProfileRegistry.apply(profile, current)
        val pkg = args.getString("package")

        // Every one of the 35 schema fields is settable: start from the seeded
        // draft's full JSON and overlay any field the caller passed by its schema
        // name (e.g. walk_delay_ms, force_class, manifest_max_components), plus a
        // few friendly aliases. This keeps the MCP surface complete and future-proof.
        val merged = JSONObject(FusionConfigSchema.toJson(seeded))
        merged.put("target_package", pkg)
        if (pkg != seeded.targetPackage) {
            merged.put("target_process", pkg) // sensible default (main) for a new target
        }
        for (field in FusionConfigSchema.fields) {
            if (field != "schema" && args.has(field)) merged.put(field, args.get(field))
        }
        if (args.has("process")) {
            merged.put(
                "target_process",
                when (args.getString("process").lowercase()) {
                    "all" -> ""
                    "main" -> pkg
                    else -> args.getString("process")
                },
            )
        }
        if (args.has("walk_mode")) {
            merged.put(
                "walk_mode",
                when (args.getString("walk_mode").lowercase()) {
                    "dex_names", "dex", "names" -> WalkMode.DEX_NAMES.propertyValue
                    else -> WalkMode.LOADED_ONLY.propertyValue
                },
            )
        }
        if (args.has("walk_start")) {
            merged.put(
                "walk_start",
                when (args.getString("walk_start").lowercase()) {
                    "activity", "after_first_activity_on_create", "first_activity" ->
                        WalkStart.AFTER_FIRST_ACTIVITY_ON_CREATE.propertyValue
                    else -> WalkStart.AFTER_APPLICATION_ON_CREATE.propertyValue
                },
            )
        }
        if (args.has("force_invoke")) merged.put("force_invoke_enabled", args.getBoolean("force_invoke"))
        if (args.has("anr_protection")) merged.put("anr_protection_enabled", args.getBoolean("anr_protection"))
        if (args.has("capture_mask")) {
            merged.put("capture_point_mask", FusionConfigSchema.maskText(parseMask(args.getString("capture_mask"))))
        }

        var draft = FusionConfigSchema.fromJson(merged.toString())
        if (!FusionConfigValidator.isValidRunId(draft.runId)) {
            draft = draft.copy(runId = createRunId(pkg, profile))
        }
        val validation = FusionConfigValidator.validate(draft, requireEnabled = draft.enabled)
        if (!validation.isValid) {
            return JSONObject()
                .put("ok", false)
                .put("reason", "validation")
                .put("errors", JSONArray(validation.errors.keys.sorted()))
        }
        return snapshotJson(properties.publish(draft, generation)).put("ok", true)
    }

    private suspend fun launchApp(args: JSONObject): JSONObject {
        val pkg = targetPackage(args)
        packages.launch(pkg).getOrThrow()
        return JSONObject().put("ok", true).put("launched", pkg)
    }

    private suspend fun stopApp(args: JSONObject): JSONObject {
        val pkg = targetPackage(args)
        packages.forceStopAndWait(pkg).getOrThrow()
        return JSONObject().put("ok", true).put("stopped", pkg)
    }

    private suspend fun recover(): JSONObject =
        snapshotJson(properties.recoverInterrupted()).put("ok", true)

    private suspend fun targetPackage(args: JSONObject): String {
        val pkg = args.optString("package").ifBlank {
            properties.read().draftOrNull()?.targetPackage.orEmpty()
        }
        if (pkg.isBlank()) throw IllegalArgumentException("package is required (no current target)")
        return pkg
    }

    private suspend fun enable(): JSONObject {
        val snapshot = properties.read()
        val draft = snapshot.draftOrNull()
            ?: throw IllegalStateException("no config to enable; call set_config first")
        val generation = snapshot.generationOrNull() ?: 0L
        return snapshotJson(properties.publish(draft.copy(enabled = true), generation)).put("ok", true)
    }

    private suspend fun disable(): JSONObject {
        val generation = properties.read().generationOrNull() ?: 0L
        return snapshotJson(properties.disable(generation)).put("ok", true)
    }

    private suspend fun repairExport(args: JSONObject): JSONObject {
        val pkg = args.optString("package").ifBlank {
            properties.read().draftOrNull()?.targetPackage.orEmpty()
        }
        if (pkg.isBlank()) throw IllegalArgumentException("package is required (no current target)")
        return exportJson(exports.exportLatest(pkg))
    }

    private fun snapshotJson(snapshot: FusionDeviceSnapshot): JSONObject {
        val json = JSONObject()
        when (snapshot) {
            is FusionDeviceSnapshot.Enabled ->
                json.put("state", "enabled").put("generation", snapshot.generation)
                    .put("enabled", true).put("config", JSONObject(FusionConfigSchema.toJson(snapshot.draft)))
            is FusionDeviceSnapshot.Disabled ->
                json.put("state", "disabled").put("generation", snapshot.generation)
                    .put("enabled", false).put("config", JSONObject(FusionConfigSchema.toJson(snapshot.draft)))
            is FusionDeviceSnapshot.Interrupted ->
                json.put("state", "interrupted").put("generation", snapshot.generation)
            is FusionDeviceSnapshot.Invalid -> json.put("state", "invalid").put("reason", snapshot.reason)
            FusionDeviceSnapshot.Unconfigured -> json.put("state", "unconfigured")
            FusionDeviceSnapshot.ConcurrentChange -> json.put("state", "concurrent_change")
        }
        return json
    }

    private fun exportJson(result: ArtifactExportResult): JSONObject = JSONObject()
        .put("ok", true)
        .put("runId", result.runId)
        .put("archivePath", result.archivePath)
        .put("sha256", result.sha256)
        .put("remoteArtifactCount", result.remoteArtifactCount)
        .put("recordCount", result.recordCount)
        .put("verified", result.verified)
        .put("repaired", result.repaired)
        .put("partial", result.partial)
        .put("usableOutputs", result.usableOutputs)
        .put("codeItemsBackfilled", result.codeItemsBackfilled)

    private fun parseMask(raw: String): ULong {
        val text = raw.trim()
        return if (text.startsWith("0x") || text.startsWith("0X")) {
            text.substring(2).toULong(16)
        } else {
            text.toULong()
        }
    }

    private fun createRunId(packageName: String, profile: ProfileId): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val tail = packageName.substringAfterLast('.').filter { it.isLetterOrDigit() }.take(20).ifBlank { "target" }
        val slug = profile.name.lowercase().replace('_', '-')
        return "$timestamp-$tail-$slug".take(64)
    }

    private fun toolResult(payload: JSONObject, isError: Boolean): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", payload.toString())))
            .put("isError", isError)

    private fun toolError(message: String): JSONObject =
        toolResult(JSONObject().put("ok", false).put("error", message), isError = true)

    private fun success(id: Any?, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun strProp(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun intProp(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun boolProp(description: String) = JSONObject().put("type", "boolean").put("description", description)

    private fun FusionDeviceSnapshot.generationOrNull(): Long? = when (this) {
        is FusionDeviceSnapshot.Enabled -> generation
        is FusionDeviceSnapshot.Disabled -> generation
        is FusionDeviceSnapshot.Interrupted -> generation
        else -> null
    }

    private fun FusionDeviceSnapshot.draftOrNull(): FusionConfigDraft? = when (this) {
        is FusionDeviceSnapshot.Enabled -> draft
        is FusionDeviceSnapshot.Disabled -> draft
        else -> null
    }

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val PARSE_ERROR = -32700
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
    }
}
