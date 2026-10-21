package com.v2ray.ang.handler.location

/**
 * Minimal storage seam. The production implementation delegates to `MmkvManager` (the project's
 * owner of settings persistence); tests use an in-memory fake. It intentionally exposes nothing
 * about servers, subscriptions or routing, so this feature cannot alter them through storage.
 */
interface LocationKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

data class LoadedProfiles(
    val data: LocationProfileStoreData,
    val issues: List<String>,
)

/**
 * Owns the persisted shape of Custom Location. All profiles and the active id live in one key so a
 * write is atomic: it either fully lands or the previous value stays.
 */
class LocationProfileRepository(private val store: LocationKeyValueStore) {

    companion object {
        const val KEY_ENABLED = "pref_custom_location_enabled"
        const val KEY_DATA = "pref_custom_location_profiles"
        const val KEY_BACKUP = "pref_custom_location_profiles_backup"
        const val KEY_SCAN_JOURNAL = "pref_custom_location_scan_journal"

        /** Every key this feature may write; tests assert nothing else is touched. */
        val ALL_KEYS = setOf(KEY_ENABLED, KEY_DATA, KEY_BACKUP, KEY_SCAN_JOURNAL)
    }

    fun isEnabled(): Boolean = store.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) = store.putBoolean(KEY_ENABLED, enabled)

    /**
     * Loads profiles. If the payload was corrupt or from a newer app version, the raw text is kept
     * once under [KEY_BACKUP] before anything can overwrite it, so a downgrade or a bug never
     * silently destroys user data.
     */
    fun load(): LoadedProfiles {
        val raw = store.getString(KEY_DATA)
        val result = LocationProfileCodec.decode(raw)
        if (result.needsBackup && raw != null && store.getString(KEY_BACKUP) == null) {
            store.putString(KEY_BACKUP, raw)
        }
        return LoadedProfiles(result.data, result.issues)
    }

    fun save(data: LocationProfileStoreData) {
        store.putString(KEY_DATA, LocationProfileCodec.encode(data))
    }

    /**
     * Restores the documented default: feature off, no profiles, no backup. Servers are untouched.
     * The scan journal is intentionally kept: it is a safety record for undoing an interrupted scan,
     * not a preference.
     */
    fun reset() {
        store.putBoolean(KEY_ENABLED, false)
        store.putString(KEY_DATA, null)
        store.putString(KEY_BACKUP, null)
    }
}

/**
 * Persists "a scan changed the connection; this is how to put it back". Holds only the previously
 * selected server GUID and whether the core was running; no IP, location or credentials.
 */
class ScanJournalRepository(private val store: LocationKeyValueStore) : ScanJournal {

    override fun save(snapshot: ConnectionSnapshot) {
        val o = com.google.gson.JsonObject()
        snapshot.selectedGuid?.let { o.addProperty("guid", it) }
        o.addProperty("running", snapshot.wasRunning)
        store.putString(LocationProfileRepository.KEY_SCAN_JOURNAL, o.toString())
    }

    override fun clear() {
        store.putString(LocationProfileRepository.KEY_SCAN_JOURNAL, null)
    }

    // A present-but-unreadable record still counts as pending: it must be dismissed explicitly, not overwritten.
    override fun hasPending(): Boolean = store.getString(LocationProfileRepository.KEY_SCAN_JOURNAL) != null

    /** Null when no scan was interrupted, or when the stored record is unreadable. */
    fun load(): ConnectionSnapshot? {
        val raw = store.getString(LocationProfileRepository.KEY_SCAN_JOURNAL) ?: return null
        return try {
            val o = com.google.gson.JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val running = o.get("running")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                ?: return null
            val guid = o.get("guid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ConnectionSnapshot(guid?.takeIf { it.isNotBlank() }, running)
        } catch (e: Exception) {
            null
        }
    }
}
