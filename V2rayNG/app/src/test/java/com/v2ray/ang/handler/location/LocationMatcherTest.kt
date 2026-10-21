package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationMatcherTest {

    private val catalog = GeoCatalogParser.parse(
        """{"schema":1,
            "regions":{"US":[["US-NY","New York"],["US-CA","California"]],"DE":[["DE-BY","Bayern"],["DE-BE","Berlin"]]},
            "cities":{"US":[["1","New York City","US-NY",40.7128,-74.006,8000000],["2","Los Angeles","US-CA",34.05,-118.24,3900000]],
                      "DE":[["3","Munich","DE-BY",48.137,11.575,1400000]]}}"""
    )

    private fun obs(
        country: String? = "US", regionRaw: String? = null, regionName: String? = null,
        city: String? = null, lat: Double? = null, lon: Double? = null,
    ) = ObservedLocation("p.test", 1L, "203.0.113.9", country, regionRaw, regionName, city, lat, lon, "srv")

    private fun want(
        country: String = "US", region: String? = null, city: String? = null,
        s: MatchStrictness = MatchStrictness.PREFER_FINER,
    ) = DesiredLocation(country, region, city, s)

    private fun verdict(d: DesiredLocation, o: ObservedLocation?, c: GeoCatalog? = catalog) =
        LocationMatcher.evaluate(d, o, c).verdict

    // ---- country level -------------------------------------------------------------------

    @Test
    fun countryMatchIsVerifiedForCountryOnlyRequests() {
        for (s in MatchStrictness.entries) assertEquals(MatchVerdict.VERIFIED_MATCH, verdict(want(s = s), obs()), s.name)
    }

    @Test
    fun differentCountryIsNoMatchForEveryPolicy() {
        for (s in MatchStrictness.entries) {
            assertEquals(MatchVerdict.NO_MATCH, verdict(want(country = "DE", s = s), obs("US")), s.name)
        }
    }

    @Test
    fun missingOrUnusableCountryIsUnavailableNotMismatch() {
        assertEquals(MatchVerdict.GEO_UNAVAILABLE, verdict(want(), null))
        assertEquals(MatchVerdict.GEO_UNAVAILABLE, verdict(want(), obs(country = null)))
        assertEquals(MatchVerdict.GEO_UNAVAILABLE, verdict(want(), obs(country = "United States")))
        assertEquals(MatchVerdict.VERIFIED_MATCH, verdict(want(), obs(country = "us"))) // normalized defensively
    }

    // ---- region ---------------------------------------------------------------------------

    @Test
    fun regionMatchesByIsoCodeWithAndWithoutCountryPrefix() {
        val d = want(region = "US-NY", s = MatchStrictness.STRICT)
        assertEquals(FineMatch.MATCH, LocationMatcher.evaluate(d, obs(regionRaw = "NY"), catalog).region)
        assertEquals(FineMatch.MATCH, LocationMatcher.evaluate(d, obs(regionRaw = "us-ny"), catalog).region)
        assertEquals(MatchVerdict.VERIFIED_MATCH, verdict(d, obs(regionRaw = "NY")))
    }

    @Test
    fun differentValidIsoRegionIsAContradiction() {
        val d = want(region = "US-NY")
        val o = obs(regionRaw = "CA")
        assertEquals(FineMatch.MISMATCH, LocationMatcher.evaluate(d, o, catalog).region)
        assertEquals(MatchVerdict.COUNTRY_MATCH_ONLY, verdict(d, o))
        assertEquals(MatchVerdict.NO_MATCH, verdict(d.copy(strictness = MatchStrictness.STRICT), o))
        assertEquals(MatchVerdict.VERIFIED_MATCH, verdict(d.copy(strictness = MatchStrictness.COUNTRY_ONLY), o))
    }

    @Test
    fun regionNameEqualityMatchesButDifferenceProvesNothing() {
        val d = want(country = "DE", region = "DE-BY")
        assertEquals(FineMatch.MATCH, LocationMatcher.evaluate(d, obs("DE", regionName = "BAYERN"), catalog).region)
        // "Bavaria" is just another name for Bayern: it must not be treated as a contradiction.
        val unknown = LocationMatcher.evaluate(d, obs("DE", regionName = "Bavaria"), catalog)
        assertEquals(FineMatch.UNKNOWN, unknown.region)
        assertEquals(MatchVerdict.REGION_CITY_UNVERIFIED, unknown.verdict)
    }

    @Test
    fun providerCodeThatIsNotARealIsoRegionFallsBackToUnknown() {
        val d = want(region = "US-NY")
        assertEquals(FineMatch.UNKNOWN, LocationMatcher.evaluate(d, obs(regionRaw = "ZZ9"), catalog).region)
    }

    @Test
    fun providerWithoutRegionLeavesItUnverifiedAndStrictDoesNotPass() {
        val d = want(region = "US-NY")
        assertEquals(MatchVerdict.REGION_CITY_UNVERIFIED, verdict(d, obs()))
        assertEquals(MatchVerdict.REGION_CITY_UNVERIFIED, verdict(d.copy(strictness = MatchStrictness.STRICT), obs()))
        assertEquals(MatchVerdict.VERIFIED_MATCH, verdict(d.copy(strictness = MatchStrictness.COUNTRY_ONLY), obs()))
    }

    // ---- city -----------------------------------------------------------------------------

    @Test
    fun cityMatchesByCoordinatesWithinTheNearThreshold() {
        val d = want(city = "1", s = MatchStrictness.STRICT)
        val near = obs(city = "New York", lat = 40.73, lon = -73.99)
        assertEquals(FineMatch.MATCH, LocationMatcher.evaluate(d, near, catalog).city)
        assertEquals(MatchVerdict.VERIFIED_MATCH, verdict(d, near))
    }

    @Test
    fun cityFarAwayIsAContradictionAndInBetweenIsUnknown() {
        val d = want(city = "1")
        assertEquals(FineMatch.MISMATCH, LocationMatcher.evaluate(d, obs(lat = 34.05, lon = -118.24), catalog).city)
        // Philadelphia is roughly 130 km from New York City: neither near nor clearly far.
        assertEquals(FineMatch.UNKNOWN, LocationMatcher.evaluate(d, obs(lat = 39.95, lon = -75.16), catalog).city)
    }

    @Test
    fun cityFallsBackToNameEqualityWithoutCoordinates() {
        val d = want(city = "1")
        assertEquals(FineMatch.MATCH, LocationMatcher.evaluate(d, obs(city = "new york city"), catalog).city)
        assertEquals(FineMatch.UNKNOWN, LocationMatcher.evaluate(d, obs(city = "Newark"), catalog).city)
        assertEquals(FineMatch.UNKNOWN, LocationMatcher.evaluate(d, obs(), catalog).city)
    }

    @Test
    fun cityIsUnknownWhenTheCatalogIsUnavailableOrDoesNotKnowTheCity() {
        val d = want(city = "1")
        assertEquals(FineMatch.UNKNOWN, LocationMatcher.evaluate(d, obs(lat = 40.7, lon = -74.0), null).city)
        assertEquals(FineMatch.UNKNOWN, LocationMatcher.evaluate(want(city = "999"), obs(), catalog).city)
    }

    @Test
    fun notRequestedLevelsAreReportedAsSuch() {
        val r = LocationMatcher.evaluate(want(), obs(), catalog)
        assertEquals(FineMatch.NOT_REQUESTED, r.region)
        assertEquals(FineMatch.NOT_REQUESTED, r.city)
    }

    @Test
    fun regionAndCityBothMustHoldForAVerifiedFineMatch() {
        val d = want(region = "US-NY", city = "1", s = MatchStrictness.STRICT)
        assertEquals(MatchVerdict.VERIFIED_MATCH, verdict(d, obs(regionRaw = "NY", lat = 40.71, lon = -74.0)))
        assertEquals(MatchVerdict.NO_MATCH, verdict(d, obs(regionRaw = "NY", lat = 34.05, lon = -118.24)))
        assertEquals(MatchVerdict.REGION_CITY_UNVERIFIED, verdict(d, obs(regionRaw = "NY")))
    }

    // ---- privacy ---------------------------------------------------------------------------

    @Test
    fun observationNeverPrintsTheFullIpAddress() {
        val text = obs().toString()
        assertFalse(text.contains("203.0.113.9"), text)
        assertTrue(text.contains("203.0.113.x"), text)
        val v6 = ObservedLocation("p", 1L, "2001:db8::1", "US").toString()
        assertFalse(v6.contains("db8"), v6)
        assertEquals("none", ObservedLocation.maskIp(null))
        assertEquals("?", ObservedLocation.maskIp("not-an-ip"))
    }

    @Test
    fun strictnessWireValuesRoundTripAndUnknownIsRejected() {
        for (s in MatchStrictness.entries) assertEquals(s, MatchStrictness.fromWire(s.wireValue))
        assertEquals(null, MatchStrictness.fromWire("bogus"))
        assertEquals(null, MatchStrictness.fromWire(null))
    }
}
