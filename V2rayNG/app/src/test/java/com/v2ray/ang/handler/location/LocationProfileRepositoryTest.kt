package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeStore : LocationKeyValueStore {
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()
    val writtenKeys = mutableSetOf<String>()

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        writtenKeys += key
        if (value == null) strings.remove(key) else strings[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        writtenKeys += key
        booleans[key] = value
    }
}

class LocationProfileRepositoryTest {

    private val profile = LocationProfile(id = "a", name = "Work", countryCode = "DE", serverGuid = "srv-1")

    @Test
    fun defaultsToDisabledAndEmpty() {
        val repo = LocationProfileRepository(FakeStore())
        assertFalse(repo.isEnabled())
        assertTrue(repo.load().data.profiles.isEmpty())
    }

    @Test
    fun persistsAcrossRepositoryInstancesLikeAnAppRestart() {
        val store = FakeStore()
        LocationProfileRepository(store).apply {
            setEnabled(true)
            save(LocationProfileStoreData(listOf(profile), "a"))
        }
        val restarted = LocationProfileRepository(store)
        assertTrue(restarted.isEnabled())
        val loaded = restarted.load()
        assertEquals(listOf(profile), loaded.data.profiles)
        assertEquals("a", loaded.data.activeProfileId)
    }

    @Test
    fun corruptDataIsBackedUpOnceAndNeverCrashes() {
        val store = FakeStore()
        store.strings[LocationProfileRepository.KEY_DATA] = "{{ corrupt"
        val repo = LocationProfileRepository(store)
        assertTrue(repo.load().data.profiles.isEmpty())
        assertEquals("{{ corrupt", store.strings[LocationProfileRepository.KEY_BACKUP])

        // A later save replaces the live key but must not overwrite the preserved original.
        repo.save(LocationProfileStoreData(listOf(profile), "a"))
        store.strings[LocationProfileRepository.KEY_DATA] = "second corruption"
        repo.load()
        assertEquals("{{ corrupt", store.strings[LocationProfileRepository.KEY_BACKUP])
    }

    @Test
    fun newerSchemaDataIsPreservedInBackupBeforeAnyOverwrite() {
        val store = FakeStore()
        val future = """{"schema":7,"profiles":[{"id":"x"}]}"""
        store.strings[LocationProfileRepository.KEY_DATA] = future
        LocationProfileRepository(store).load()
        assertEquals(future, store.strings[LocationProfileRepository.KEY_BACKUP])
    }

    @Test
    fun cleanDataCreatesNoBackup() {
        val store = FakeStore()
        val repo = LocationProfileRepository(store)
        repo.save(LocationProfileStoreData(listOf(profile), "a"))
        repo.load()
        assertNull(store.strings[LocationProfileRepository.KEY_BACKUP])
    }

    @Test
    fun resetRestoresDefaultsAndRemovesAllStoredData() {
        val store = FakeStore()
        val repo = LocationProfileRepository(store)
        repo.setEnabled(true)
        repo.save(LocationProfileStoreData(listOf(profile), "a"))
        store.strings[LocationProfileRepository.KEY_BACKUP] = "x"
        repo.reset()
        assertFalse(repo.isEnabled())
        assertTrue(repo.load().data.profiles.isEmpty())
        assertTrue(store.strings.isEmpty())
    }

    @Test
    fun switchingProfilesWritesOnlyLocationKeysAndNeverTouchesServers() {
        val store = FakeStore()
        val repo = LocationProfileRepository(store)
        val a = profile
        val b = LocationProfile(id = "b", name = "Other", countryCode = "US", serverGuid = "srv-2")
        repo.setEnabled(true)
        repo.save(LocationProfileStoreData(listOf(a, b), "a"))
        repo.save(LocationProfileStoreData(listOf(a, b), "b")) // "switch profile"
        repo.reset()
        assertTrue(
            LocationProfileRepository.ALL_KEYS.containsAll(store.writtenKeys),
            "unexpected keys written: ${store.writtenKeys - LocationProfileRepository.ALL_KEYS}"
        )
        // The store seam has no server API at all, so a selected server cannot be changed through it.
    }

    @Test
    fun allProfilesAndActiveIdAreWrittenInASingleKeyForAtomicity() {
        val store = FakeStore()
        val repo = LocationProfileRepository(store)
        repo.save(LocationProfileStoreData(listOf(profile, profile.copy(id = "b")), "b"))
        assertEquals(setOf(LocationProfileRepository.KEY_DATA), store.writtenKeys)
    }
}

class ScanJournalRepositoryTest {
    private val store = FakeStore()
    private val journal = ScanJournalRepository(store)

    @Test
    fun roundTripsSelectionAndRunningState() {
        journal.save(ConnectionSnapshot("guid-1", true))
        assertEquals(ConnectionSnapshot("guid-1", true), journal.load())
        journal.save(ConnectionSnapshot(null, false))
        assertEquals(ConnectionSnapshot(null, false), journal.load())
    }

    @Test
    fun clearRemovesTheRecordAndNothingIsStoredByDefault() {
        assertNull(journal.load())
        journal.save(ConnectionSnapshot("g", true))
        journal.clear()
        assertNull(journal.load())
    }

    @Test
    fun malformedRecordsAreIgnoredNotCrashes() {
        for (raw in listOf("{{", "[]", "\"x\"", "{}", """{"running":"yes"}""", "")) {
            store.strings[LocationProfileRepository.KEY_SCAN_JOURNAL] = raw
            assertNull(journal.load(), raw)
        }
    }

    @Test
    fun journalContainsNoIpOrLocationData() {
        journal.save(ConnectionSnapshot("guid-1", true))
        val raw = store.strings[LocationProfileRepository.KEY_SCAN_JOURNAL]!!
        assertEquals(setOf("guid", "running"), com.google.gson.JsonParser.parseString(raw).asJsonObject.keySet())
    }

    @Test
    fun resetKeepsTheJournalBecauseItIsASafetyRecordNotAPreference() {
        val repo = LocationProfileRepository(store)
        journal.save(ConnectionSnapshot("g", true))
        repo.reset()
        assertEquals(ConnectionSnapshot("g", true), journal.load())
        assertTrue(LocationProfileRepository.ALL_KEYS.containsAll(store.writtenKeys))
    }

    @Test
    fun pendingIsTrueForAnyStoredRecordEvenAnUnreadableOneUntilItIsCleared() {
        assertFalse(journal.hasPending())
        store.strings[LocationProfileRepository.KEY_SCAN_JOURNAL] = "{{ garbage"
        assertNull(journal.load())
        assertTrue(journal.hasPending()) // unreadable != resolved: it must be dismissed, not overwritten
        journal.clear()
        assertFalse(journal.hasPending())
        journal.save(ConnectionSnapshot("g", false))
        assertTrue(journal.hasPending())
    }
}
