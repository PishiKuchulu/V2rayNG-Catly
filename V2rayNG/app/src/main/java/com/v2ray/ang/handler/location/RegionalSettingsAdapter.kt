package com.v2ray.ang.handler.location

import android.content.Intent
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.Language
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import java.util.TimeZone

/**
 * Regional preferences, kept strictly apart from exit IP and GPS.
 *
 * Language: app-scoped only. It changes this app's UI language, never the device's.
 * Time zone: Android offers ordinary apps no API to change the system time zone, so this adapter only
 * reads it and offers a shortcut to the system screen. It deliberately does not call
 * `TimeZone.setDefault`, which would affect only this process and look like a change that never
 * happened.
 */
object RegionalSettingsAdapter {

    private val shippedLanguages: List<Language> get() = Language.entries.filter { it.languageTag.isNotEmpty() }

    fun environment(): RegionalEnvironment {
        val stored = Language.fromCode(MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, "auto") ?: "auto")
        return RegionalEnvironment(
            deviceTimeZoneId = TimeZone.getDefault().id,
            currentAppLanguageTag = stored.languageTag.takeIf { it.isNotEmpty() },
            supportedLocaleTags = shippedLanguages.map { it.languageTag }.toSet(),
        )
    }

    /**
     * Applies the app language for [requestedTag] when the app ships that language (or an
     * unambiguous variant). Returns false, without changing anything, otherwise.
     */
    fun applyAppLanguage(requestedTag: String): Boolean {
        val match = RegionalMatcher.matchSupported(requestedTag, environment().supportedLocaleTags) ?: return false
        val language = shippedLanguages.firstOrNull { it.languageTag == match } ?: return false
        AppLocaleManager.setApplicationLanguage(language.code)
        return true
    }

    fun dateSettingsIntent(): Intent = Intent(Settings.ACTION_DATE_SETTINGS)

    fun developerSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
}
