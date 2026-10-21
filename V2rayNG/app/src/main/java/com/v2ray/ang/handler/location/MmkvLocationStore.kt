package com.v2ray.ang.handler.location

import com.v2ray.ang.handler.MmkvManager

/**
 * Production [LocationKeyValueStore]: delegates to `MmkvManager`, the project's single owner of
 * settings persistence, so no second storage path (SharedPreferences, files) is introduced.
 */
object MmkvLocationStore : LocationKeyValueStore {
    override fun getString(key: String): String? = MmkvManager.decodeSettingsString(key)

    // MMKV removes the key when a null String is encoded.
    override fun putString(key: String, value: String?) {
        MmkvManager.encodeSettings(key, value)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        MmkvManager.decodeSettingsBool(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        MmkvManager.encodeSettings(key, value)
    }
}
