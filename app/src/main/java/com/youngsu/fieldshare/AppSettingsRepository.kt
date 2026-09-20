package com.youngsu.fieldshare

import android.content.Context

enum class HomeDocumentDisplayMode {
    RECENT_ONLY,
    ALL,
    HIDDEN
}

/** The commit callback must persist ALL and its one-time marker in one transaction. */
internal fun migrateHomeDisplayOnce(completed: Boolean, commitAllAndMarker: () -> Unit) {
    if (!completed) commitAllAndMarker()
}

/** Persists local presentation preferences independently from Firebase document data. */
class AppSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(AppSettingsRepository::class.java) {
            migrateHomeDisplayOnce(preferences.getBoolean("drive_home_all_migration_v1", false)) {
                check(preferences.edit().putString(KEY_HOME_DISPLAY_MODE, VALUE_ALL)
                    .putBoolean("drive_home_all_migration_v1", true).commit()) {
                    "홈 표시 설정을 저장하지 못했습니다."
                }
            }
        }
    }

    fun getHomeDisplayMode(): HomeDocumentDisplayMode = when (preferences.getString(KEY_HOME_DISPLAY_MODE, null)) {
        VALUE_ALL -> HomeDocumentDisplayMode.ALL
        VALUE_HIDDEN -> HomeDocumentDisplayMode.HIDDEN
        VALUE_RECENT_ONLY -> HomeDocumentDisplayMode.RECENT_ONLY
        else -> HomeDocumentDisplayMode.ALL
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
