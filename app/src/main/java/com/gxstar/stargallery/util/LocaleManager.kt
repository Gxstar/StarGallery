package com.gxstar.stargallery.util

import android.content.SharedPreferences
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocaleManager @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        const val KEY_APP_LANGUAGE = "app_language"
        const val LANG_SYSTEM = "system"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"
    }

    private val _currentLanguage = MutableStateFlow(getSavedLanguage())
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    val isChineseLocale: Boolean
        get() = getLocale().language == "zh"

    private fun getSavedLanguage(): String {
        return sharedPreferences.getString(KEY_APP_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun getLocale(): Locale {
        return when (getSavedLanguage()) {
            LANG_ZH -> Locale.SIMPLIFIED_CHINESE
            LANG_EN -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
    }

    /**
     * 切换语言：写 SP + 更新 JVM Locale + 调用 setApplicationLocales() 触发 Activity 重建
     */
    fun setLanguage(lang: String) {
        sharedPreferences.edit().putString(KEY_APP_LANGUAGE, lang).commit()
        _currentLanguage.value = lang
        applyLocaleInternal(lang)
    }

    /**
     * 冷启动时调用，通过 setApplicationLocales() 全局持久化语言设置
     */
    fun applyLocale() {
        applyLocaleInternal(getSavedLanguage())
    }

    private fun applyLocaleInternal(lang: String) {
        val localeList = when (lang) {
            LANG_ZH -> LocaleListCompat.forLanguageTags("zh-CN")
            LANG_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)

        when (lang) {
            LANG_ZH -> Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            LANG_EN -> Locale.setDefault(Locale.ENGLISH)
            else -> Locale.setDefault(Resources.getSystem().configuration.locales[0])
        }
    }
}
