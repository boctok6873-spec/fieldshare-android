package com.youngsu.fieldshare

import android.content.Context

enum class HomeDocumentDisplayMode {
    RECENT_ONLY,
    ALL,
    HIDDEN
}

/** Persists local presentation preferences independently from Firebase document data. */
class AppSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getHomeDisplayMode(): HomeDocumentDisplayMode = when (preferences.getString(KEY_HOME_DISPLAY_MODE, null)) {
        VALUE_ALL -> HomeDocumentDisplayMode.ALL
        VALUE_HIDDEN -> HomeDocumentDisplayMode.HIDDEN
        else -> HomeDocumentDisplayMode.RECENT_ONLY
    }

    fun setHomeDisplayMode(mode: HomeDocumentDisplayMode) {
        preferences.edit()
            .putString(KEY_HOME_DISPLAY_MODE, mode.persistedValue)
            .apply()
    }

    private val HomeDocumentDisplayMode.persistedValue: String
        get() = when (this) {
            HomeDocumentDisplayMode.ALL -> VALUE_ALL
            HomeDocumentDisplayMode.HIDDEN -> VALUE_HIDDEN
            HomeDocumentDisplayMode.RECENT_ONLY -> VALUE_RECENT_ONLY
        }

    private companion object {
        const val PREFERENCES_NAME = "fieldshare_settings"
        const val KEY_HOME_DISPLAY_MODE = "home_display_mode"
        const val VALUE_ALL = "all"
        const val VALUE_RECENT_ONLY = "recent_only"
        const val VALUE_HIDDEN = "hidden"
    }
}
