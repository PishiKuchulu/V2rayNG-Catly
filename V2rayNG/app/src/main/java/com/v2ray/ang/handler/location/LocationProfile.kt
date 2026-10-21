package com.v2ray.ang.handler.location

import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * A user-defined, *desired* location profile.
 *
 * Nothing in here is proof of anything: the country/region/city is what the user wants, the
 * GPS pair is what the user wants simulated, and [serverGuid] is only an association with an
 * existing server profile (it never alters that server). Whether any of it is actually in effect
 * is computed separately by [LocationStatusEvaluator] from observed signals.
 *
 * Ownership: lives in `handler/location` next to the code that persists and evaluates it. It is a
 * separate type from `ProfileItem` (a VPN server) on purpose - different lifetime and meaning.
 */
data class LocationProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String = "",
    /** ISO 3166-1 alpha-2, upper case. */
    val countryCode: String? = null,
    /** ISO 3166-2 code (for example `US-NY`), taken from the bundled catalog. */
    val regionCode: String? = null,
    /** GeoNames id of a catalog city, as a string. */
    val cityId: String? = null,
    /** GUID of an existing server profile. Association only; never used to modify that server. */
    val serverGuid: String? = null,
    /** How strictly an observed exit must match the request. Added to schema 1 as an optional field. */
    val matchStrictness: MatchStrictness = MatchStrictness.DEFAULT,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    /** IANA time zone id, for example `Europe/Berlin`. */
    val timeZoneId: String? = null,
    /** BCP-47 language tag for the application-scoped language preference. */
    val localeTag: String? = null,
) {
    val hasGps: Boolean get() = gpsLatitude != null && gpsLongitude != null

    /** The request as a [DesiredLocation], or null while no country is chosen. */
    fun toDesired(): DesiredLocation? =
        countryCode?.let { DesiredLocation(it, regionCode, cityId, matchStrictness) }

    companion object {
        /**
         * Version 1 is the first released schema; no earlier format exists to migrate from.
         * Add a branch to [LocationProfileCodec] when this number is raised.
         */
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_NAME_LENGTH = 64

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Per-profile aggregate state. Always derived, never stored. */
enum class ProfileState {
    NOT_CONFIGURED,
    CONFIGURED,
    WAITING_FOR_PERMISSION,
    APPLIED,
    PARTIALLY_APPLIED,
    UNSUPPORTED,
    VERIFICATION_FAILED,
}

enum class ProfileIssueCode {
    INVALID_COUNTRY,
    UNKNOWN_REGION,
    REGION_NOT_IN_COUNTRY,
    UNKNOWN_CITY,
    CITY_NOT_IN_COUNTRY,
    GPS_INCOMPLETE,
    GPS_OUT_OF_RANGE,
    INVALID_TIME_ZONE,
    INVALID_LOCALE,
    NAME_TOO_LONG,
}

data class ProfileIssue(val field: String, val code: ProfileIssueCode)

object LocationProfileValidator {

    private val isoCountries: Set<String> by lazy { Locale.getISOCountries().toSet() }
    private val zoneIds: Set<String> by lazy { ZoneId.getAvailableZoneIds() }

    /** Returns the canonical (upper-case) code, or null when [code] is not an ISO 3166-1 alpha-2 code. */
    fun normalizeCountry(code: String?): String? {
        val candidate = code?.trim()?.uppercase(Locale.ROOT) ?: return null
        return candidate.takeIf { it.length == 2 && it in isoCountries }
    }

    fun isValidCountry(code: String?): Boolean = normalizeCountry(code) != null

    fun isValidTimeZone(id: String?): Boolean = id != null && id in zoneIds

    fun isValidLocaleTag(tag: String?): Boolean {
        if (tag.isNullOrBlank() || tag.length > 35) return false
        // Locale.forLanguageTag() silently turns malformed input into "und", so reject that.
        val parsed = Locale.forLanguageTag(tag.trim())
        return parsed.language.isNotEmpty() && parsed.toLanguageTag() != "und"
    }

    fun isValidLatitude(value: Double?): Boolean = value != null && value.isFinite() && value in -90.0..90.0

    fun isValidLongitude(value: Double?): Boolean = value != null && value.isFinite() && value in -180.0..180.0

    /**
     * Validates [profile]. When [catalog] is null (catalog unavailable) region and city checks are
     * skipped rather than reported as invalid: unknown is not the same as wrong.
     */
    fun validate(profile: LocationProfile, catalog: GeoCatalog?): List<ProfileIssue> {
        val issues = mutableListOf<ProfileIssue>()
        if (profile.name.length > LocationProfile.MAX_NAME_LENGTH) {
            issues += ProfileIssue("name", ProfileIssueCode.NAME_TOO_LONG)
        }
        if (profile.countryCode != null && !isValidCountry(profile.countryCode)) {
            issues += ProfileIssue("countryCode", ProfileIssueCode.INVALID_COUNTRY)
        }
        if (catalog != null) {
            profile.regionCode?.let { code ->
                val region = catalog.region(code)
                when {
                    region == null -> issues += ProfileIssue("regionCode", ProfileIssueCode.UNKNOWN_REGION)
                    profile.countryCode != null && region.countryCode != profile.countryCode ->
                        issues += ProfileIssue("regionCode", ProfileIssueCode.REGION_NOT_IN_COUNTRY)
                }
            }
            profile.cityId?.let { id ->
                val city = catalog.city(id)
                when {
                    city == null -> issues += ProfileIssue("cityId", ProfileIssueCode.UNKNOWN_CITY)
                    profile.countryCode != null && city.countryCode != profile.countryCode ->
                        issues += ProfileIssue("cityId", ProfileIssueCode.CITY_NOT_IN_COUNTRY)
                }
            }
        }
        val lat = profile.gpsLatitude
        val lon = profile.gpsLongitude
        if ((lat == null) != (lon == null)) {
            issues += ProfileIssue("gps", ProfileIssueCode.GPS_INCOMPLETE)
        } else if (lat != null && (!isValidLatitude(lat) || !isValidLongitude(lon))) {
            issues += ProfileIssue("gps", ProfileIssueCode.GPS_OUT_OF_RANGE)
        }
        if (profile.timeZoneId != null && !isValidTimeZone(profile.timeZoneId)) {
            issues += ProfileIssue("timeZoneId", ProfileIssueCode.INVALID_TIME_ZONE)
        }
        if (profile.localeTag != null && !isValidLocaleTag(profile.localeTag)) {
            issues += ProfileIssue("localeTag", ProfileIssueCode.INVALID_LOCALE)
        }
        return issues
    }
}

/**
 * Pure editing operations. Each keeps the hierarchy consistent and returns the profile unchanged
 * for invalid input, so the UI can never persist a half-valid selection.
 */
object LocationProfileOps {

    fun withName(profile: LocationProfile, name: String): LocationProfile =
        profile.copy(name = name.trim().take(LocationProfile.MAX_NAME_LENGTH))

    /** Selecting a different country clears region and city, which belong to the old country. */
    fun withCountry(profile: LocationProfile, countryCode: String?): LocationProfile {
        if (countryCode == null) return profile.copy(countryCode = null, regionCode = null, cityId = null)
        val normalized = LocationProfileValidator.normalizeCountry(countryCode) ?: return profile
        if (normalized == profile.countryCode) return profile
        return profile.copy(countryCode = normalized, regionCode = null, cityId = null)
    }

    fun withRegion(profile: LocationProfile, regionCode: String?, catalog: GeoCatalog): LocationProfile {
        if (regionCode == null) return profile.copy(regionCode = null)
        val region = catalog.region(regionCode) ?: return profile
        if (profile.countryCode != region.countryCode) return profile
        // Keep the city only when it is known to belong to this region or has no region link.
        val city = profile.cityId?.let(catalog::city)
        val keepCity = city == null || city.regionCode == null || city.regionCode == region.code
        return profile.copy(regionCode = region.code, cityId = if (keepCity) profile.cityId else null)
    }

    /** A city fixes the country; its region is set only when the catalog actually links them. */
    fun withCity(profile: LocationProfile, cityId: String?, catalog: GeoCatalog): LocationProfile {
        if (cityId == null) return profile.copy(cityId = null)
        val city = catalog.city(cityId) ?: return profile
        val sameCountry = profile.countryCode == city.countryCode
        val keptRegion = if (sameCountry) profile.regionCode else null
        return profile.copy(
            countryCode = city.countryCode,
            regionCode = city.regionCode ?: keptRegion,
            cityId = city.id,
        )
    }

    fun withGps(profile: LocationProfile, latitude: Double?, longitude: Double?): LocationProfile {
        if (latitude == null && longitude == null) return profile.copy(gpsLatitude = null, gpsLongitude = null)
        if (!LocationProfileValidator.isValidLatitude(latitude) ||
            !LocationProfileValidator.isValidLongitude(longitude)
        ) return profile
        return profile.copy(gpsLatitude = latitude, gpsLongitude = longitude)
    }

    fun withTimeZone(profile: LocationProfile, timeZoneId: String?): LocationProfile {
        if (timeZoneId == null) return profile.copy(timeZoneId = null)
        val trimmed = timeZoneId.trim()
        return if (LocationProfileValidator.isValidTimeZone(trimmed)) profile.copy(timeZoneId = trimmed) else profile
    }

    fun withLocale(profile: LocationProfile, localeTag: String?): LocationProfile {
        if (localeTag == null) return profile.copy(localeTag = null)
        val trimmed = localeTag.trim()
        return if (LocationProfileValidator.isValidLocaleTag(trimmed)) profile.copy(localeTag = trimmed) else profile
    }

    fun withStrictness(profile: LocationProfile, strictness: MatchStrictness): LocationProfile =
        profile.copy(matchStrictness = strictness)

    fun withServer(profile: LocationProfile, serverGuid: String?): LocationProfile =
        profile.copy(serverGuid = serverGuid?.takeIf { it.isNotBlank() })
}
