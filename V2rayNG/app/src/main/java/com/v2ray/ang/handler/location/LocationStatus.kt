package com.v2ray.ang.handler.location

import java.util.Locale

/** The independent mechanisms. They are reported separately and never inferred from each other. */
enum class LocationCapability { EXIT_LOCATION, VIRTUAL_GPS, BROWSER_LOCATION, TIME_ZONE, LOCALE }

enum class CapabilityStatus {
    /** The user did not ask for this. */
    NOT_REQUESTED,

    /** Requested and confirmed by an observation (see each capability for what "confirmed" means). */
    SATISFIED,

    /** Requested, but not applied yet or not verified (including a stale or not-yet-run check). */
    PENDING,
    WAITING_FOR_PERMISSION,

    /** This app, build or Android cannot do it. */
    UNSUPPORTED,

    /** A needed signal could not be obtained (check failed, service not reachable, unreadable result). */
    UNAVAILABLE,

    /** Observed value contradicts the request. */
    MISMATCH,

    /** The country matches but a requested region/city is contradicted or could not be verified. */
    PARTIAL,

    /** An attempted operation failed. */
    FAILED,
}

enum class IpFamily {
    IPV4, IPV6, UNKNOWN;

    companion object {
        private val V4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
        private val V6_CHARS = Regex("^[0-9A-Fa-f:.%a-zA-Z0-9]+$")

        /** Classifies an IP *literal* without any DNS lookup. */
        fun of(ip: String?): IpFamily {
            val s = ip?.trim().orEmpty()
            return when {
                s.isEmpty() -> UNKNOWN
                V4.matches(s) -> IPV4
                s.contains(':') && V6_CHARS.matches(s) -> IPV6
                else -> UNKNOWN
            }
        }
    }
}

enum class ExitCheckFailure {
    /** The core is not running, so nothing was checked (and nothing was sent). */
    NOT_RUNNING,
    TIMEOUT,

    /** The connection itself failed (the delay test through the route failed). */
    CONNECTION_FAILED,

    /** The route works but the geolocation provider gave no usable country. */
    GEO_UNAVAILABLE,

    /** Whether the service is running could not be established, so nothing was attempted. */
    STATE_UNKNOWN,
}

sealed interface ExitCheckState {
    data object Idle : ExitCheckState
    data object Running : ExitCheckState
    data class Success(val observation: ObservedLocation) : ExitCheckState
    data class Failed(val reason: ExitCheckFailure) : ExitCheckState
}

object ExitObservationPolicy {
    /** Network changes (Wi-Fi/cellular, reconnects) can change the exit, so old results expire. */
    const val MAX_AGE_MILLIS = 10 * 60 * 1000L

    fun isFresh(observation: ObservedLocation, nowMillis: Long, selectedServerGuid: String?): Boolean =
        observation.serverGuid == selectedServerGuid &&
            nowMillis - observation.observedAtMillis in 0..MAX_AGE_MILLIS
}

enum class MockLocationState {
    NOT_CONFIGURED,

    /** The build does not declare ACCESS_MOCK_LOCATION, so Android will not list the app as a mock provider. */
    BUILD_UNSUPPORTED,

    /** The user must pick this app as the mock location app in Developer options. */
    WAITING_FOR_SELECTION,
    READY,
    DELIVERING,
    DELIVERY_FAILED,
}

data class MockLocationStatus(
    val state: MockLocationState = MockLocationState.NOT_CONFIGURED,
    val deliveredLatitude: Double? = null,
    val deliveredLongitude: Double? = null,
)

object MockLocationStateResolver {
    fun resolve(
        hasCoordinates: Boolean,
        permissionDeclared: Boolean,
        selectedAsMockApp: Boolean,
        delivering: Boolean,
        lastDeliveryFailed: Boolean,
    ): MockLocationState = when {
        !hasCoordinates -> MockLocationState.NOT_CONFIGURED
        !permissionDeclared -> MockLocationState.BUILD_UNSUPPORTED
        !selectedAsMockApp -> MockLocationState.WAITING_FOR_SELECTION
        lastDeliveryFailed -> MockLocationState.DELIVERY_FAILED
        delivering -> MockLocationState.DELIVERING
        else -> MockLocationState.READY
    }
}

data class RegionalEnvironment(
    val deviceTimeZoneId: String,
    /** Tag of the app language currently in effect, or null when following the system. */
    val currentAppLanguageTag: String?,
    /** Language tags this app ships translations for. */
    val supportedLocaleTags: Set<String>,
)

object RegionalMatcher {
    /**
     * Maps a requested tag to one of the app's supported tags: exact match first, otherwise a
     * language-only match that must be unambiguous (two supported variants of one language, such
     * as Simplified and Traditional Chinese, are not guessed between).
     */
    fun matchSupported(requested: String, supported: Set<String>): String? {
        val wanted = Locale.forLanguageTag(requested.trim())
        supported.firstOrNull { it.equals(requested.trim(), ignoreCase = true) }?.let { return it }
        val sameLanguage = supported.filter { Locale.forLanguageTag(it).language == wanted.language }
        return sameLanguage.singleOrNull()
    }
}

/** What to say about the connection that is active right now. Never "applied" without a fresh check. */
enum class CurrentExitState {
    NOT_CHECKED,
    CHECKING,
    NOT_CONNECTED,
    CHECK_FAILED,
    GEO_UNAVAILABLE,
    STALE,
    CONNECTED_VERIFIED,

    /** Connected; country matches but a finer level is unverified or contradicted. */
    CONNECTED_PARTIAL,
    CONNECTED_MISMATCH,
}

sealed interface LocationWarning {
    data class ExitCountryMismatch(val requested: String, val observed: String) : LocationWarning
    data class GpsFarFromCity(val distanceKm: Int) : LocationWarning
    data class TimeZoneDiffers(val requested: String, val device: String) : LocationWarning
    data object ServerNotSelected : LocationWarning
}

data class CapabilityReport(
    val capability: LocationCapability,
    val requested: Boolean,
    val status: CapabilityStatus,
)

data class ProfileStatusReport(
    val state: ProfileState,
    val capabilities: List<CapabilityReport>,
    val warnings: List<LocationWarning>,
) {
    fun statusOf(capability: LocationCapability): CapabilityStatus =
        capabilities.first { it.capability == capability }.status
}

data class EvaluationInput(
    val profile: LocationProfile,
    val catalog: GeoCatalog?,
    val exitCheck: ExitCheckState,
    val selectedServerGuid: String?,
    val nowMillis: Long,
    val mock: MockLocationStatus,
    val regional: RegionalEnvironment,
)

/**
 * Derives status from observations only. There is no code path that reports a capability as
 * satisfied because the user selected something: selection alone yields PENDING.
 */
object LocationStatusEvaluator {
    /** GPS farther than this from the selected city is flagged (metro-area scale, not precision). */
    const val GPS_CITY_WARNING_KM = 300.0

    fun evaluate(input: EvaluationInput): ProfileStatusReport {
        val p = input.profile
        val exit = evaluateExit(input)
        val gps = evaluateGps(input)
        val timeZone = evaluateTimeZone(input)
        val locale = evaluateLocale(input)

        val reports = listOf(
            CapabilityReport(LocationCapability.EXIT_LOCATION, p.countryCode != null, exit),
            CapabilityReport(LocationCapability.VIRTUAL_GPS, p.hasGps, gps),
            // No supported mechanism exists, and nothing can be requested for it.
            CapabilityReport(LocationCapability.BROWSER_LOCATION, false, CapabilityStatus.UNSUPPORTED),
            CapabilityReport(LocationCapability.TIME_ZONE, p.timeZoneId != null, timeZone),
            CapabilityReport(LocationCapability.LOCALE, p.localeTag != null, locale),
        )
        return ProfileStatusReport(aggregate(reports), reports, warnings(input, exit))
    }

    private fun matchOf(input: EvaluationInput): MatchResult? {
        val desired = input.profile.toDesired() ?: return null
        val check = input.exitCheck as? ExitCheckState.Success ?: return null
        if (!ExitObservationPolicy.isFresh(check.observation, input.nowMillis, input.selectedServerGuid)) return null
        return LocationMatcher.evaluate(desired, check.observation, input.catalog)
    }

    private fun evaluateExit(input: EvaluationInput): CapabilityStatus {
        if (input.profile.countryCode == null) return CapabilityStatus.NOT_REQUESTED
        return when (val check = input.exitCheck) {
            ExitCheckState.Idle, ExitCheckState.Running -> CapabilityStatus.PENDING
            is ExitCheckState.Failed -> CapabilityStatus.UNAVAILABLE
            is ExitCheckState.Success -> {
                val match = matchOf(input) ?: return CapabilityStatus.PENDING // stale observation
                when (match.verdict) {
                    MatchVerdict.VERIFIED_MATCH -> CapabilityStatus.SATISFIED
                    MatchVerdict.COUNTRY_MATCH_ONLY, MatchVerdict.REGION_CITY_UNVERIFIED -> CapabilityStatus.PARTIAL
                    MatchVerdict.NO_MATCH -> CapabilityStatus.MISMATCH
                    MatchVerdict.GEO_UNAVAILABLE -> CapabilityStatus.UNAVAILABLE
                }
            }
        }
    }

    /** State of the connection that is active now, for display next to the desired location. */
    fun currentExit(input: EvaluationInput): CurrentExitState {
        if (input.profile.countryCode == null) return CurrentExitState.NOT_CHECKED
        return when (val check = input.exitCheck) {
            ExitCheckState.Idle -> CurrentExitState.NOT_CHECKED
            ExitCheckState.Running -> CurrentExitState.CHECKING
            is ExitCheckState.Failed -> when (check.reason) {
                ExitCheckFailure.NOT_RUNNING -> CurrentExitState.NOT_CONNECTED
                ExitCheckFailure.GEO_UNAVAILABLE -> CurrentExitState.GEO_UNAVAILABLE
                else -> CurrentExitState.CHECK_FAILED
            }

            is ExitCheckState.Success -> {
                val match = matchOf(input) ?: return CurrentExitState.STALE
                when (match.verdict) {
                    MatchVerdict.VERIFIED_MATCH -> CurrentExitState.CONNECTED_VERIFIED
                    MatchVerdict.COUNTRY_MATCH_ONLY, MatchVerdict.REGION_CITY_UNVERIFIED -> CurrentExitState.CONNECTED_PARTIAL
                    MatchVerdict.NO_MATCH -> CurrentExitState.CONNECTED_MISMATCH
                    MatchVerdict.GEO_UNAVAILABLE -> CurrentExitState.GEO_UNAVAILABLE
                }
            }
        }
    }

    private fun evaluateGps(input: EvaluationInput): CapabilityStatus {
        val p = input.profile
        if (!p.hasGps) return CapabilityStatus.NOT_REQUESTED
        val mock = input.mock
        return when (mock.state) {
            MockLocationState.NOT_CONFIGURED -> CapabilityStatus.PENDING
            MockLocationState.BUILD_UNSUPPORTED -> CapabilityStatus.UNSUPPORTED
            MockLocationState.WAITING_FOR_SELECTION -> CapabilityStatus.WAITING_FOR_PERMISSION
            MockLocationState.READY -> CapabilityStatus.PENDING
            MockLocationState.DELIVERY_FAILED -> CapabilityStatus.FAILED
            MockLocationState.DELIVERING ->
                // "Satisfied" means: these exact coordinates were handed to Android's test provider.
                // Whether any app accepts them cannot be observed from here.
                if (mock.deliveredLatitude == p.gpsLatitude && mock.deliveredLongitude == p.gpsLongitude) {
                    CapabilityStatus.SATISFIED
                } else {
                    CapabilityStatus.PENDING
                }
        }
    }

    private fun evaluateTimeZone(input: EvaluationInput): CapabilityStatus {
        val requested = input.profile.timeZoneId ?: return CapabilityStatus.NOT_REQUESTED
        // Android gives ordinary apps no way to change the system time zone, so the only honest
        // outcomes are "device already uses it" or "this app cannot apply it".
        return if (requested == input.regional.deviceTimeZoneId) CapabilityStatus.SATISFIED
        else CapabilityStatus.UNSUPPORTED
    }

    private fun evaluateLocale(input: EvaluationInput): CapabilityStatus {
        val requested = input.profile.localeTag ?: return CapabilityStatus.NOT_REQUESTED
        val env = input.regional
        val match = RegionalMatcher.matchSupported(requested, env.supportedLocaleTags)
            ?: return CapabilityStatus.UNSUPPORTED
        return if (env.currentAppLanguageTag != null && env.currentAppLanguageTag.equals(match, ignoreCase = true)) {
            CapabilityStatus.SATISFIED
        } else {
            CapabilityStatus.PENDING
        }
    }

    private fun warnings(input: EvaluationInput, exit: CapabilityStatus): List<LocationWarning> {
        val p = input.profile
        val out = mutableListOf<LocationWarning>()
        val check = input.exitCheck
        if (exit == CapabilityStatus.MISMATCH && check is ExitCheckState.Success && p.countryCode != null) {
            val observed = LocationProfileValidator.normalizeCountry(check.observation.countryCode)
            if (observed != null && observed != p.countryCode) {
                out += LocationWarning.ExitCountryMismatch(p.countryCode, observed)
            }
        }
        if (p.serverGuid != null && p.serverGuid != input.selectedServerGuid) {
            out += LocationWarning.ServerNotSelected
        }
        val city = input.catalog?.city(p.cityId)
        if (city != null && p.gpsLatitude != null && p.gpsLongitude != null) {
            val km = GeoMath.distanceKm(p.gpsLatitude, p.gpsLongitude, city.latitude, city.longitude)
            if (km > GPS_CITY_WARNING_KM) out += LocationWarning.GpsFarFromCity(km.toInt())
        }
        val tz = p.timeZoneId
        if (tz != null && tz != input.regional.deviceTimeZoneId) {
            out += LocationWarning.TimeZoneDiffers(tz, input.regional.deviceTimeZoneId)
        }
        return out
    }

    internal fun aggregate(reports: List<CapabilityReport>): ProfileState {
        val requested = reports.filter { it.requested }.map { it.status }
        if (requested.isEmpty()) return ProfileState.NOT_CONFIGURED
        if (requested.any { it == CapabilityStatus.MISMATCH || it == CapabilityStatus.FAILED }) {
            return ProfileState.VERIFICATION_FAILED
        }
        if (requested.any { it == CapabilityStatus.WAITING_FOR_PERMISSION }) {
            return ProfileState.WAITING_FOR_PERMISSION
        }
        val satisfied = requested.count { it == CapabilityStatus.SATISFIED }
        val partial = requested.count { it == CapabilityStatus.PARTIAL }
        return when {
            satisfied == requested.size -> ProfileState.APPLIED
            satisfied > 0 || partial > 0 -> ProfileState.PARTIALLY_APPLIED
            requested.all { it == CapabilityStatus.UNSUPPORTED } -> ProfileState.UNSUPPORTED
            else -> ProfileState.CONFIGURED
        }
    }
}
