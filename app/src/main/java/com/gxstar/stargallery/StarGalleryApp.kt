package com.gxstar.stargallery

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StarGalleryApp : Application() {

    companion object {
        const val PREFS_NAME = "stargallery_prefs"
        const val KEY_THEME_MODE = "theme_mode"
        const val DEFAULT_THEME_MODE = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    override fun onCreate() {
        super.onCreate()
        applyThemeFromPreferences()
    }

    private fun applyThemeFromPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_THEME_MODE, DEFAULT_THEME_MODE)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
