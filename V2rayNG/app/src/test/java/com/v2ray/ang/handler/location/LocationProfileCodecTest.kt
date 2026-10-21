package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationProfileCodecTest {

    private val full = LocationProfile(
        id = "a", name = "Work", countryCode = "US", regionCode = "US-NY", cityId = "1",
        serverGuid = "guid-1", gpsLatitude = 40.7, gpsLongitude = -74.0,
        timeZoneId = "America/New_York", localeTag = "en-US",
    )

    @Test
    fun roundTripPreservesEveryField() {
        val data = LocationProfileStoreData(listOf(full, LocationProfile(id = "b", name = "Empty")), "a")
        val decoded = LocationProfileCodec.decode(LocationProfileCodec.encode(data))
        assertEquals(data, decoded.data)
        assertTrue(decoded.issues.isEmpty())
        assertFalse(decoded.needsBackup)
    }

    @Test
    fun nothingStoredIsNotAnError() {
        for (raw in listOf(null, "", "   ")) {
            val r = LocationProfileCodec.decode(raw)
            assertTrue(r.data.profiles.isEmpty())
            assertFalse(r.needsBackup)
        }
    }

    @Test
    fun corruptPayloadsNeverThrowAndAskForBackup() {
        for (raw in listOf("{{{", "garbage", "[1,2]", "42", "\"str\"", """{"profiles":"x"}""", "{\"schema\":")) {
            val r = LocationProfileCodec.decode(raw)
            assertTrue(r.data.profiles.isEmpty(), raw)
            assertTrue(r.needsBackup, raw)
        }
    }

    @Test
    fun newerSchemaIsNotInterpretedAndIsBackedUp() {
        val r = LocationProfileCodec.decode("""{"schema":2,"profiles":[{"id":"x","countryCode":"US"}]}""")
        assertTrue(r.data.profiles.isEmpty())
        assertTrue(r.needsBackup)
        assertTrue(r.issues.single().contains("newer schema"))
    }

    @Test
    fun profileMissingSchemaVersionIsTreatedAsCurrent() {
        val r = LocationProfileCodec.decode("""{"schema":1,"profiles":[{"id":"x","name":"n","countryCode":"de"}]}""")
        assertEquals("DE", r.data.profiles.single().countryCode)
        assertEquals(LocationProfile.CURRENT_SCHEMA_VERSION, r.data.profiles.single().schemaVersion)
    }

    @Test
    fun profileWithUnsupportedVersionOrNoIdIsDroppedOthersSurvive() {
        val r = LocationProfileCodec.decode(
            """{"schema":1,"profiles":[
                {"id":"future","schemaVersion":9},
                {"id":"zero","schemaVersion":0},
                {"name":"no id"},
                {"id":"  "},
                "not an object",
                {"id":"ok","name":"kept"}]}"""
        )
        assertEquals(listOf("ok"), r.data.profiles.map { it.id })
        assertTrue(r.needsBackup)
    }

    @Test
    fun wrongTypesAndInvalidValuesAreDroppedFieldByFieldWithoutCrashing() {
        val r = LocationProfileCodec.decode(
            """{"schema":1,"profiles":[{"id":"p","name":123,"countryCode":"ZZ","regionCode":7,
                "gpsLatitude":"abc","gpsLongitude":5,"timeZoneId":"Nope/Zone","localeTag":["x"],
                "serverGuid":true,"extra":{"unknown":"field"}}]}"""
        )
        val p = r.data.profiles.single()
        assertEquals("", p.name)
        assertNull(p.countryCode)
        assertNull(p.regionCode)
        assertNull(p.gpsLatitude)
        assertNull(p.gpsLongitude) // half a GPS pair is dropped as a pair
        assertNull(p.timeZoneId)
        assertNull(p.localeTag)
        assertNull(p.serverGuid)
        assertTrue(r.issues.isNotEmpty())
    }

    @Test
    fun outOfRangeGpsIsDropped() {
        val p = LocationProfileCodec.decode(
            """{"schema":1,"profiles":[{"id":"p","gpsLatitude":123.4,"gpsLongitude":10}]}"""
        ).data.profiles.single()
        assertNull(p.gpsLatitude)
        assertNull(p.gpsLongitude)
    }

    @Test
    fun duplicateIdsAndOverlongListsAreHandled() {
        val dup = LocationProfileCodec.decode(
            """{"schema":1,"profiles":[{"id":"a","name":"first"},{"id":"a","name":"second"}]}"""
        )
        assertEquals("first", dup.data.profiles.single().name)
        assertTrue(dup.needsBackup)

        val many = (1..LocationProfileCodec.MAX_PROFILES + 5).joinToString(",") { """{"id":"id$it"}""" }
        val r = LocationProfileCodec.decode("""{"schema":1,"profiles":[$many]}""")
        assertEquals(LocationProfileCodec.MAX_PROFILES, r.data.profiles.size)
        assertTrue(r.needsBackup)
    }

    @Test
    fun activeIdMustReferToAnExistingProfile() {
        val r = LocationProfileCodec.decode("""{"schema":1,"active":"ghost","profiles":[{"id":"a"}]}""")
        assertNull(r.data.activeProfileId)
        val ok = LocationProfileCodec.decode("""{"schema":1,"active":"a","profiles":[{"id":"a"}]}""")
        assertEquals("a", ok.data.activeProfileId)
    }

    @Test
    fun overlongNamesAreTruncated() {
        val long = "n".repeat(500)
        val p = LocationProfileCodec.decode("""{"schema":1,"profiles":[{"id":"a","name":"$long"}]}""").data.profiles.single()
        assertEquals(LocationProfile.MAX_NAME_LENGTH, p.name.length)
    }

    @Test
    fun matchStrictnessRoundTripsAndOlderDataWithoutItGetsTheDefault() {
        for (m in MatchStrictness.entries) {
            val data = LocationProfileStoreData(listOf(full.copy(matchStrictness = m)), "a")
            assertEquals(m, LocationProfileCodec.decode(LocationProfileCodec.encode(data)).data.profiles.single().matchStrictness)
        }
        // Data written before the field existed (pre-existing schema-1 payload) must still load.
        val old = LocationProfileCodec.decode("""{"schema":1,"profiles":[{"id":"x","countryCode":"DE"}]}""")
        assertEquals(MatchStrictness.DEFAULT, old.data.profiles.single().matchStrictness)
        assertFalse(old.needsBackup)
        val bogus = LocationProfileCodec.decode("""{"schema":1,"profiles":[{"id":"x","matchStrictness":"nope"}]}""")
        assertEquals(MatchStrictness.DEFAULT, bogus.data.profiles.single().matchStrictness)
    }

    @Test
    fun observationsAreNeverPersistedWithProfiles() {
        val json = LocationProfileCodec.encode(LocationProfileStoreData(listOf(full), "a"))
        assertFalse(json.contains("observed"))
        assertFalse(json.contains("\"ip"))
        assertFalse(json.contains("203."))
    }
}
