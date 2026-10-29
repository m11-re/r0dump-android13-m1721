package com.android.r0dumpmanager.data.ui

import android.content.Context

enum class UiStateKey {
    DESTINATION,
    CAPTURE_GROUPS,
    BUDGET_GROUPS,
    ADVANCED_GROUPS,
}

class UiStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(key: UiStateKey): String? = preferences.getString(key.name, null)

    fun write(key: UiStateKey, value: String) {
        preferences.edit().putString(key.name, value).apply()
    }

    fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "r0dump_ui_state"
    }
}
