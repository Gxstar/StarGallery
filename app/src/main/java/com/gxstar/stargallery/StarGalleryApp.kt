package com.gxstar.stargallery

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.gxstar.stargallery.util.LocaleManager
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StarGalleryApp : Application() {

    @Inject
    lateinit var localeManager: LocaleManager

    companion object {
        const val PREFS_NAME = "stargallery_prefs"
        const val KEY_THEME_MODE = "theme_mode"
        const val DEFAULT_THEME_MODE = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    override fun onCreate() {
        super.onCreate()
        localeManager.applyLocale()
        applyThemeFromPreferences()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    private fun applyThemeFromPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_THEME_MODE, DEFAULT_THEME_MODE)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
