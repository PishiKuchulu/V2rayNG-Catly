package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationStatusEvaluatorTest {

    private val now = 1_000_000_000L
    private val catalog = GeoCatalogParser.parse(
        """{"schema":1,"regions":{"US":[["US-NY","New York"]]},
            "cities":{"US":[["1","New York City","US-NY",40.7128,-74.006,8000000]]}}"""
    )
    private val supported = setOf("en", "zh-CN", "zh-TW", "ru", "fa")
    private val env = RegionalEnvironment("Europe/Berlin", null, supported)

    private fun obs(country: String?, ip: String? = "203.0.113.7", at: Long = now, guid: String? = "srv") =
        ExitCheckState.Success(ObservedLocation("provider.test", at, ip, country, serverGuid = guid))

    private fun eval(
        profile: LocationProfile,
        exit: ExitCheckState = ExitCheckState.Idle,
        selected: String? = "srv",
        mock: MockLocationStatus = MockLocationStatus(),
        regional: RegionalEnvironment = env,
        at: Long = now,
    ) = LocationStatusEvaluator.evaluate(EvaluationInput(profile, catalog, exit, selected, at, mock, regional))

    private fun p(block: LocationProfile.() -> LocationProfile = { this }) = LocationProfile(id = "p").block()

    // ---- aggregate state -----------------------------------------------------------------

    @Test
    fun emptyProfileIsNotConfigured() {
        val r = eval(p())
        assertEquals(ProfileState.NOT_CONFIGURED, r.state)
        assertTrue(r.capabilities.filter { it.requested }.isEmpty())
    }

    @Test
    fun selectingACountryAloneIsNeverReportedAsApplied() {
        for (state in listOf(ExitCheckState.Idle, ExitCheckState.Running)) {
            val r = eval(p { copy(countryCode = "US", regionCode = "US-NY", cityId = "1") }, exit = state)
            assertEquals(CapabilityStatus.PENDING, r.statusOf(LocationCapability.EXIT_LOCATION))
            assertEquals(ProfileState.CONFIGURED, r.state)
        }
    }

    @Test
    fun exitIsSatisfiedOnlyWhenObservedCountryMatches() {
        val r = eval(p { copy(countryCode = "US") }, exit = obs("US"))
        assertEquals(CapabilityStatus.SATISFIED, r.statusOf(LocationCapability.EXIT_LOCATION))
        assertEquals(ProfileState.APPLIED, r.state)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun differentObservedCountryIsAVerificationFailureWithWarning() {
        val r = eval(p { copy(countryCode = "US") }, exit = obs("de"))
        assertEquals(CapabilityStatus.MISMATCH, r.statusOf(LocationCapability.EXIT_LOCATION))
        assertEquals(ProfileState.VERIFICATION_FAILED, r.state)
        assertEquals(listOf<LocationWarning>(LocationWarning.ExitCountryMismatch("US", "DE")), r.warnings)
    }

    @Test
    fun unavailableGeographicExitIsReportedHonestlyNotAsSuccessOrMismatch() {
        // Tunnel down / service unreachable / timeout / empty result all mean "unknown".
        for (reason in ExitCheckFailure.entries) {
            val r = eval(p { copy(countryCode = "US") }, exit = ExitCheckState.Failed(reason))
            assertEquals(CapabilityStatus.UNAVAILABLE, r.statusOf(LocationCapability.EXIT_LOCATION), reason.name)
            assertEquals(ProfileState.CONFIGURED, r.state)
        }
        // Provider answered but the country cannot be interpreted as an ISO code.
        for (country in listOf(null, "United States", "", "USA")) {
            val r = eval(p { copy(countryCode = "US") }, exit = obs(country))
            assertEquals(CapabilityStatus.UNAVAILABLE, r.statusOf(LocationCapability.EXIT_LOCATION), country.toString())
        }
    }

    @Test
    fun observationExpiresAfterTheFreshnessWindowSoNetworkChangesAreNotMasked() {
        val old = obs("US", at = now)
        val later = now + ExitObservationPolicy.MAX_AGE_MILLIS + 1
        assertEquals(
            CapabilityStatus.PENDING,
            eval(p { copy(countryCode = "US") }, exit = old, at = later).statusOf(LocationCapability.EXIT_LOCATION)
        )
        // Reconnect: a new observation is fresh again.
        assertEquals(
            CapabilityStatus.SATISFIED,
            eval(p { copy(countryCode = "US") }, exit = obs("US", at = later), at = later)
                .statusOf(LocationCapability.EXIT_LOCATION)
        )
        // Clock going backwards is not trusted.
        assertEquals(
            CapabilityStatus.PENDING,
            eval(p { copy(countryCode = "US") }, exit = obs("US", at = now), at = now - 1)
                .statusOf(LocationCapability.EXIT_LOCATION)
        )
    }

    @Test
    fun observationIsStaleWhenTheSelectedServerChangedAfterTheCheck() {
        val r = eval(p { copy(countryCode = "US") }, exit = obs("US", guid = "srv"), selected = "other")
        assertEquals(CapabilityStatus.PENDING, r.statusOf(LocationCapability.EXIT_LOCATION))
    }

    // ---- server association -------------------------------------------------------------

    @Test
    fun associatedServerThatIsNotSelectedProducesAWarningAndNoSwitch() {
        val r = eval(p { copy(countryCode = "US", serverGuid = "assoc") }, selected = "srv")
        assertTrue(r.warnings.contains(LocationWarning.ServerNotSelected))
        val ok = eval(p { copy(countryCode = "US", serverGuid = "srv") }, selected = "srv")
        assertFalse(ok.warnings.contains(LocationWarning.ServerNotSelected))
        val none = eval(p { copy(countryCode = "US") }, selected = "srv")
        assertFalse(none.warnings.contains(LocationWarning.ServerNotSelected))
    }

    // ---- virtual GPS --------------------------------------------------------------------

    private val gpsProfile = p { copy(gpsLatitude = 40.0, gpsLongitude = -74.0) }

    @Test
    fun gpsStatesMapOntoDistinctStagesOfTheMockLocationWorkflow() {
        fun gps(state: MockLocationState, lat: Double? = null, lon: Double? = null) =
            eval(gpsProfile, mock = MockLocationStatus(state, lat, lon))

        assertEquals(ProfileState.UNSUPPORTED, gps(MockLocationState.BUILD_UNSUPPORTED).state)
        assertEquals(ProfileState.WAITING_FOR_PERMISSION, gps(MockLocationState.WAITING_FOR_SELECTION).state)
        assertEquals(ProfileState.CONFIGURED, gps(MockLocationState.READY).state)
        assertEquals(ProfileState.VERIFICATION_FAILED, gps(MockLocationState.DELIVERY_FAILED).state)
        assertEquals(ProfileState.APPLIED, gps(MockLocationState.DELIVERING, 40.0, -74.0).state)
        // Delivering something else (profile edited meanwhile) is not "this profile applied".
        assertEquals(
            CapabilityStatus.PENDING,
            gps(MockLocationState.DELIVERING, 1.0, 2.0).statusOf(LocationCapability.VIRTUAL_GPS)
        )
    }

    @Test
    fun mockLocationStateResolverFollowsTheDocumentedPrecedence() {
        fun r(c: Boolean, d: Boolean, s: Boolean, del: Boolean, f: Boolean) =
            MockLocationStateResolver.resolve(c, d, s, del, f)
        assertEquals(MockLocationState.NOT_CONFIGURED, r(false, true, true, true, false))
        assertEquals(MockLocationState.BUILD_UNSUPPORTED, r(true, false, true, true, false))
        assertEquals(MockLocationState.WAITING_FOR_SELECTION, r(true, true, false, true, false))
        assertEquals(MockLocationState.DELIVERY_FAILED, r(true, true, true, true, true))
        assertEquals(MockLocationState.DELIVERING, r(true, true, true, true, false))
        assertEquals(MockLocationState.READY, r(true, true, true, false, false))
    }

    @Test
    fun partialApplicationWhenOneRequestedCapabilityIsSatisfiedAndAnotherUnsupported() {
        val r = eval(
            p { copy(countryCode = "US", gpsLatitude = 1.0, gpsLongitude = 2.0) },
            exit = obs("US"),
            mock = MockLocationStatus(MockLocationState.BUILD_UNSUPPORTED)
        )
        assertEquals(ProfileState.PARTIALLY_APPLIED, r.state)
    }

    @Test
    fun gpsFarFromSelectedCityRaisesAConflictWarning() {
        val far = eval(p { copy(countryCode = "US", cityId = "1", gpsLatitude = 34.05, gpsLongitude = -118.24) })
        assertTrue(far.warnings.any { it is LocationWarning.GpsFarFromCity && it.distanceKm > 3000 })
        val near = eval(p { copy(countryCode = "US", cityId = "1", gpsLatitude = 40.73, gpsLongitude = -73.99) })
        assertTrue(near.warnings.none { it is LocationWarning.GpsFarFromCity })
        val noCity = eval(p { copy(countryCode = "US", gpsLatitude = 34.05, gpsLongitude = -118.24) })
        assertTrue(noCity.warnings.none { it is LocationWarning.GpsFarFromCity })
    }

    // ---- browser, time zone, locale ------------------------------------------------------

    @Test
    fun browserLocationIsAlwaysUnsupportedAndNeverAffectsTheProfileState() {
        val r = eval(p { copy(countryCode = "US") }, exit = obs("US"))
        val browser = r.capabilities.first { it.capability == LocationCapability.BROWSER_LOCATION }
        assertEquals(CapabilityStatus.UNSUPPORTED, browser.status)
        assertFalse(browser.requested)
        assertEquals(ProfileState.APPLIED, r.state)
    }

    @Test
    fun timeZoneIsOnlySatisfiedWhenTheDeviceAlreadyUsesIt() {
        val same = eval(p { copy(timeZoneId = "Europe/Berlin") })
        assertEquals(CapabilityStatus.SATISFIED, same.statusOf(LocationCapability.TIME_ZONE))
        assertTrue(same.warnings.none { it is LocationWarning.TimeZoneDiffers })
        val diff = eval(p { copy(timeZoneId = "Asia/Tokyo") })
        assertEquals(CapabilityStatus.UNSUPPORTED, diff.statusOf(LocationCapability.TIME_ZONE))
        assertEquals(ProfileState.UNSUPPORTED, diff.state)
        assertEquals(LocationWarning.TimeZoneDiffers("Asia/Tokyo", "Europe/Berlin"), diff.warnings.single())
    }

    @Test
    fun localeCapabilityDistinguishesUnsupportedPendingAndApplied() {
        assertEquals(
            CapabilityStatus.UNSUPPORTED,
            eval(p { copy(localeTag = "de-DE") }).statusOf(LocationCapability.LOCALE)
        )
        assertEquals(
            CapabilityStatus.PENDING,
            eval(p { copy(localeTag = "ru-RU") }).statusOf(LocationCapability.LOCALE)
        )
        assertEquals(
            CapabilityStatus.SATISFIED,
            eval(p { copy(localeTag = "ru") }, regional = env.copy(currentAppLanguageTag = "ru"))
                .statusOf(LocationCapability.LOCALE)
        )
        // Ambiguous: two Chinese variants are supported, so a bare/other-region tag is not guessed.
        assertEquals(
            CapabilityStatus.UNSUPPORTED,
            eval(p { copy(localeTag = "zh-HK") }).statusOf(LocationCapability.LOCALE)
        )
        assertEquals(
            CapabilityStatus.PENDING,
            eval(p { copy(localeTag = "zh-TW") }).statusOf(LocationCapability.LOCALE)
        )
    }

    // ---- exit observation helpers --------------------------------------------------------

    @Test
    fun ipFamilyIsClassifiedFromTheLiteralWithoutDns() {
        assertEquals(IpFamily.IPV4, IpFamily.of("203.0.113.7"))
        assertEquals(IpFamily.IPV6, IpFamily.of("2001:db8::1"))
        assertEquals(IpFamily.IPV6, IpFamily.of("::ffff:192.0.2.1"))
        assertEquals(IpFamily.UNKNOWN, IpFamily.of("example.com"))
        assertEquals(IpFamily.UNKNOWN, IpFamily.of("999.1.1.1"))
        assertEquals(IpFamily.UNKNOWN, IpFamily.of("1.2.3"))
        assertEquals(IpFamily.UNKNOWN, IpFamily.of(""))
        assertEquals(IpFamily.UNKNOWN, IpFamily.of(null))
    }

    @Test
    fun observationExposesIpv4OnlyAndIpv6Environments() {
        assertEquals(IpFamily.IPV4, ObservedLocation("p", now, "198.51.100.2", "US").ipFamily)
        assertEquals(IpFamily.IPV6, ObservedLocation("p", now, "2001:db8::2", "US").ipFamily)
        assertEquals(IpFamily.UNKNOWN, ObservedLocation("p", now, null, "US").ipFamily)
    }
}
