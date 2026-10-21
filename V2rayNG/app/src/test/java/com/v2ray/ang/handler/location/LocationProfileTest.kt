package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationProfileTest {

    private val catalog = GeoCatalogParser.parse(
        """
        {"schema":1,"regions":{"US":[["US-NY","New York"],["US-CA","California"]],"DE":[["DE-BE","Berlin"]]},
         "cities":{"US":[["1","New York City","US-NY",40.7,-74.0,8000000],["2","Los Angeles","US-CA",34.0,-118.2,3900000],
                         ["5","Unlinked City","",30.0,-90.0,600000]],
                   "DE":[["3","Berlin","DE-BE",52.5,13.4,3400000]]}}
        """.trimIndent()
    )

    private fun blank() = LocationProfile(id = "p1")

    private fun codes(p: LocationProfile, c: GeoCatalog? = catalog) =
        LocationProfileValidator.validate(p, c).map { it.field to it.code }

    // ---- validation ---------------------------------------------------------------------

    @Test
    fun emptyProfileIsValid() {
        assertTrue(codes(blank()).isEmpty())
    }

    @Test
    fun invalidCountryIsReported() {
        assertEquals(listOf("countryCode" to ProfileIssueCode.INVALID_COUNTRY), codes(blank().copy(countryCode = "ZZ")))
    }

    @Test
    fun regionAndCityMustBelongToTheCountry() {
        assertEquals(
            listOf("regionCode" to ProfileIssueCode.REGION_NOT_IN_COUNTRY),
            codes(blank().copy(countryCode = "DE", regionCode = "US-NY"))
        )
        assertEquals(
            listOf("cityId" to ProfileIssueCode.CITY_NOT_IN_COUNTRY),
            codes(blank().copy(countryCode = "DE", cityId = "1"))
        )
        assertEquals(listOf("regionCode" to ProfileIssueCode.UNKNOWN_REGION), codes(blank().copy(regionCode = "XX-1")))
        assertEquals(listOf("cityId" to ProfileIssueCode.UNKNOWN_CITY), codes(blank().copy(cityId = "nope")))
    }

    @Test
    fun missingCatalogSkipsRegionAndCityChecksInsteadOfFailingThem() {
        assertTrue(codes(blank().copy(countryCode = "US", regionCode = "US-NY", cityId = "1"), null).isEmpty())
    }

    @Test
    fun gpsMustBeCompleteAndInRange() {
        assertEquals(listOf("gps" to ProfileIssueCode.GPS_INCOMPLETE), codes(blank().copy(gpsLatitude = 1.0)))
        assertEquals(listOf("gps" to ProfileIssueCode.GPS_OUT_OF_RANGE), codes(blank().copy(gpsLatitude = 91.0, gpsLongitude = 0.0)))
        assertEquals(listOf("gps" to ProfileIssueCode.GPS_OUT_OF_RANGE), codes(blank().copy(gpsLatitude = 0.0, gpsLongitude = 181.0)))
        assertEquals(listOf("gps" to ProfileIssueCode.GPS_OUT_OF_RANGE), codes(blank().copy(gpsLatitude = Double.NaN, gpsLongitude = 0.0)))
        assertTrue(codes(blank().copy(gpsLatitude = -90.0, gpsLongitude = 180.0)).isEmpty())
    }

    @Test
    fun timeZoneAndLocaleAreValidated() {
        assertTrue(LocationProfileValidator.isValidTimeZone("Europe/Berlin"))
        assertFalse(LocationProfileValidator.isValidTimeZone("Mars/Olympus"))
        assertFalse(LocationProfileValidator.isValidTimeZone(null))
        assertTrue(LocationProfileValidator.isValidLocaleTag("de-DE"))
        assertTrue(LocationProfileValidator.isValidLocaleTag("fa"))
        assertFalse(LocationProfileValidator.isValidLocaleTag("not a locale"))
        assertFalse(LocationProfileValidator.isValidLocaleTag(""))
        assertFalse(LocationProfileValidator.isValidLocaleTag("x".repeat(40)))
        assertEquals(
            listOf("timeZoneId" to ProfileIssueCode.INVALID_TIME_ZONE, "localeTag" to ProfileIssueCode.INVALID_LOCALE),
            codes(blank().copy(timeZoneId = "Nope/Nope", localeTag = "??"))
        )
    }

    // ---- editing operations -------------------------------------------------------------

    @Test
    fun changingCountryClearsRegionAndCity() {
        val p = blank().copy(countryCode = "US", regionCode = "US-NY", cityId = "1")
        val same = LocationProfileOps.withCountry(p, "us")
        assertEquals(p, same)
        val changed = LocationProfileOps.withCountry(p, "DE")
        assertEquals("DE", changed.countryCode)
        assertNull(changed.regionCode)
        assertNull(changed.cityId)
        assertEquals(p, LocationProfileOps.withCountry(p, "ZZ")) // invalid input ignored
        assertNull(LocationProfileOps.withCountry(p, null).countryCode)
    }

    @Test
    fun selectingACityFixesCountryAndLinkedRegion() {
        val p = LocationProfileOps.withCity(blank(), "1", catalog)
        assertEquals("US", p.countryCode)
        assertEquals("US-NY", p.regionCode)
        assertEquals("1", p.cityId)
        val unlinked = LocationProfileOps.withCity(blank().copy(countryCode = "US", regionCode = "US-NY"), "5", catalog)
        assertEquals("US-NY", unlinked.regionCode) // kept, not guessed from the city
        assertEquals(blank(), LocationProfileOps.withCity(blank(), "unknown", catalog))
        val crossCountry = LocationProfileOps.withCity(p, "3", catalog)
        assertEquals("DE", crossCountry.countryCode)
        assertEquals("DE-BE", crossCountry.regionCode)
    }

    @Test
    fun regionMustMatchCountryAndDropsConflictingCity() {
        val p = blank().copy(countryCode = "US", regionCode = "US-NY", cityId = "1")
        assertEquals(p, LocationProfileOps.withRegion(p, "DE-BE", catalog)) // wrong country: ignored
        val moved = LocationProfileOps.withRegion(p, "US-CA", catalog)
        assertEquals("US-CA", moved.regionCode)
        assertNull(moved.cityId) // New York City is linked to US-NY, so it cannot stay
        val keepsUnlinked = LocationProfileOps.withRegion(
            blank().copy(countryCode = "US", cityId = "5"), "US-CA", catalog
        )
        assertEquals("5", keepsUnlinked.cityId)
        assertNull(LocationProfileOps.withRegion(p, null, catalog).regionCode)
    }

    @Test
    fun gpsTimeZoneLocaleAndNameEditsRejectInvalidInput() {
        val p = blank()
        assertEquals(p, LocationProfileOps.withGps(p, 100.0, 0.0))
        assertEquals(p, LocationProfileOps.withGps(p, 10.0, null))
        assertEquals(12.5, LocationProfileOps.withGps(p, 12.5, -3.0).gpsLatitude)
        assertNull(LocationProfileOps.withGps(p.copy(gpsLatitude = 1.0, gpsLongitude = 2.0), null, null).gpsLatitude)
        assertEquals(p, LocationProfileOps.withTimeZone(p, "Bad/Zone"))
        assertEquals("Asia/Tokyo", LocationProfileOps.withTimeZone(p, " Asia/Tokyo ").timeZoneId)
        assertEquals(p, LocationProfileOps.withLocale(p, "??"))
        assertEquals("ja-JP", LocationProfileOps.withLocale(p, "ja-JP").localeTag)
        assertEquals(LocationProfile.MAX_NAME_LENGTH, LocationProfileOps.withName(p, "n".repeat(500)).name.length)
        assertNull(LocationProfileOps.withServer(p, "  ").serverGuid)
    }
}
