package com.android.r0dumpmanager.data.audit

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class MutationAction { PUBLISH, PUBLISH_AND_LAUNCH, DISABLE, END_SESSION, RECOVER, EXPORT }
data class MutationAuditEntry(val timestampMillis: Long, val action: MutationAction, val targetPackage: String?, val generation: Long?, val succeeded: Boolean)

/** Bounded, private audit log. Deliberately excludes the force selector and property values. */
class MutationAuditLog(context: Context) {
    private val file = File(context.filesDir, "r0dump_mutations.jsonl")
    @Synchronized fun append(entry: MutationAuditEntry) {
        file.parentFile?.mkdirs()
        file.appendText(JSONObject().put("time", entry.timestampMillis).put("action", entry.action.name)
            .put("target", entry.targetPackage).put("generation", entry.generation).put("success", entry.succeeded).toString() + "\n")
        val lines = file.readLines().takeLast(200)
        if (lines.size == 200) file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
