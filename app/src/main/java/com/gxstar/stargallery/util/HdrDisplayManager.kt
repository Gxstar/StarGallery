package com.gxstar.stargallery.util

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HdrDisplayManager @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    val isHdrDisplayEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_HDR_DISPLAY_ENABLED, true)

    fun setHdrDisplayEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_HDR_DISPLAY_ENABLED, enabled).apply()
    }

    companion object {
        private const val KEY_HDR_DISPLAY_ENABLED = "hdr_display_enabled"
    }
}
