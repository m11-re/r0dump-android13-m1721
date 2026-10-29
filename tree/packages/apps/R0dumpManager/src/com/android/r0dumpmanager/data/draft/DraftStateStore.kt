package com.android.r0dumpmanager.data.draft

import android.content.Context
import com.android.r0dumpmanager.domain.FusionConfigDraft
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.ProfileId

data class SavedFusionDraft(
    val draft: FusionConfigDraft,
    val generation: Long,
    val profile: ProfileId,
)

/** Persists only the editable draft. Authentication state remains process-only. */
class DraftStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(draft: FusionConfigDraft, generation: Long, profile: ProfileId) {
        preferences.edit()
            .putString(DRAFT_KEY, FusionConfigSchema.toJson(draft))
            .putLong(GENERATION_KEY, generation)
            .putString(PROFILE_KEY, profile.name)
            .apply()
    }

    fun restore(expectedGeneration: Long): SavedFusionDraft? {
        if (preferences.getLong(GENERATION_KEY, Long.MIN_VALUE) != expectedGeneration) {
            clear()
            return null
        }
        val raw = preferences.getString(DRAFT_KEY, null) ?: return null
        return runCatching {
            SavedFusionDraft(
                draft = FusionConfigSchema.fromJson(raw),
                generation = expectedGeneration,
                profile = ProfileId.valueOf(preferences.getString(PROFILE_KEY, ProfileId.CUSTOM.name)!!),
            )
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "r0dump_draft"
        const val DRAFT_KEY = "draft_json"
        const val GENERATION_KEY = "generation"
        const val PROFILE_KEY = "profile"
    }
}
