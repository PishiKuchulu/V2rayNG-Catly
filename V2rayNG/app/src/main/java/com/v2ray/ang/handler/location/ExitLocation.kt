package com.v2ray.ang.handler.location

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** How strictly a server's observed exit must match the requested location. */
enum class MatchStrictness(val wireValue: String) {
    /** Only the country must match. Region and city are shown as information, never required. */
    COUNTRY_ONLY("country"),

    /** The country must match. Region/city, if requested, rank a server higher but are not required. */
    PREFER_FINER("prefer_finer"),

    /** Every requested level (country, region, city) must be positively verified. */
    STRICT("strict");

    companion object {
        val DEFAULT = PREFER_FINER
        fun fromWire(value: String?): MatchStrictness? = entries.firstOrNull { it.wireValue == value }
    }
}

/** The user's preference. It is a request, never evidence about any server. */
data class DesiredLocation(
    val countryCode: String,
    val regionCode: String? = null,
    val cityId: String? = null,
    val strictness: MatchStrictness = MatchStrictness.DEFAULT,
)

/**
 * What the public IP seen through one actual route was geolocated to, at one moment. The values are
 * an external provider's estimate. Held in memory only; never persisted.
 */
data class ObservedLocation(
    /** Host name of the geolocation provider that produced this estimate. */
    val source: String,
    val observedAtMillis: Long,
    val ipAddress: String?,
    /** ISO 3166-1 alpha-2, already normalized; null when the provider gave nothing usable. */
    val countryCode: String?,
    /** The provider's own region code (often only the part after the dash, such as `NY`). */
    val regionCodeRaw: String? = null,
    val regionName: String? = null,
    val cityName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Server that was selected for the connection when this was observed. */
    val serverGuid: String? = null,
) {
    val ipFamily: IpFamily get() = IpFamily.of(ipAddress)

    /** The address is masked so that logging or printing an observation never leaks it. */
    override fun toString(): String =
        "ObservedLocation(source=$source, country=$countryCode, region=$regionName, city=$cityName, ip=${maskIp(ipAddress)})"

    companion object {
        fun maskIp(ip: String?): String = when (IpFamily.of(ip)) {
            IpFamily.IPV4 -> ip!!.substringBeforeLast('.') + ".x"
            IpFamily.IPV6 -> ip!!.substringBefore(':') + ":…"
            IpFamily.UNKNOWN -> if (ip.isNullOrBlank()) "none" else "?"
        }
    }
}

enum class FineMatch { NOT_REQUESTED, MATCH, MISMATCH, UNKNOWN }

enum class MatchVerdict {
    /** Every level the policy requires was positively verified. */
    VERIFIED_MATCH,

    /** Country matches; a requested finer level is contradicted (PREFER_FINER only). */
    COUNTRY_MATCH_ONLY,

    /** Country matches; a requested finer level could not be verified either way. */
    REGION_CITY_UNVERIFIED,
    NO_MATCH,

    /** No usable country was observed. This is not a mismatch. */
    GEO_UNAVAILABLE,
}

data class MatchResult(val verdict: MatchVerdict, val region: FineMatch, val city: FineMatch)

object LocationMatcher {
    /** A provider city within this distance of the requested city counts as the same place. */
    const val CITY_MATCH_KM = 40.0

    /** Farther than this is a contradiction; in between is unknown rather than a guess. */
    const val CITY_MISMATCH_KM = 150.0

    fun evaluate(desired: DesiredLocation, observed: ObservedLocation?, catalog: GeoCatalog?): MatchResult {
        val observedCountry = LocationProfileValidator.normalizeCountry(observed?.countryCode)
        if (observed == null || observedCountry == null) {
            return MatchResult(MatchVerdict.GEO_UNAVAILABLE, FineMatch.UNKNOWN, FineMatch.UNKNOWN)
        }
        val region = regionMatch(desired, observed, catalog)
        val city = cityMatch(desired, observed, catalog)
        if (observedCountry != desired.countryCode) return MatchResult(MatchVerdict.NO_MATCH, region, city)

        val verdict = when (desired.strictness) {
            MatchStrictness.COUNTRY_ONLY -> MatchVerdict.VERIFIED_MATCH
            MatchStrictness.PREFER_FINER -> when {
                region == FineMatch.MISMATCH || city == FineMatch.MISMATCH -> MatchVerdict.COUNTRY_MATCH_ONLY
                region == FineMatch.UNKNOWN || city == FineMatch.UNKNOWN -> MatchVerdict.REGION_CITY_UNVERIFIED
                else -> MatchVerdict.VERIFIED_MATCH
            }

            MatchStrictness.STRICT -> when {
                region == FineMatch.MISMATCH || city == FineMatch.MISMATCH -> MatchVerdict.NO_MATCH
                region == FineMatch.UNKNOWN || city == FineMatch.UNKNOWN -> MatchVerdict.REGION_CITY_UNVERIFIED
                else -> MatchVerdict.VERIFIED_MATCH
            }
        }
        return MatchResult(verdict, region, city)
    }

    private fun regionMatch(desired: DesiredLocation, observed: ObservedLocation, catalog: GeoCatalog?): FineMatch {
        val wanted = desired.regionCode ?: return FineMatch.NOT_REQUESTED
        // A code comparison is only trusted when the provider's code is a real ISO 3166-2 code of
        // the observed country. Providers use differing subdivision levels, so otherwise it is unknown.
        val raw = observed.regionCodeRaw?.trim()?.uppercase(Locale.ROOT)
        val country = LocationProfileValidator.normalizeCountry(observed.countryCode)
        if (!raw.isNullOrEmpty() && catalog != null && country != null) {
            val candidate = if (raw.contains('-')) raw else "$country-$raw"
            if (catalog.region(candidate) != null) {
                return if (candidate == wanted) FineMatch.MATCH else FineMatch.MISMATCH
            }
        }
        // Names differ between providers and languages ("Bayern"/"Bavaria"), so a name that is
        // equal proves a match but a name that differs proves nothing.
        val wantedName = catalog?.region(wanted)?.name
        val observedName = observed.regionName
        if (wantedName != null && observedName != null &&
            CountryCatalog.fold(wantedName) == CountryCatalog.fold(observedName)
        ) return FineMatch.MATCH
        return FineMatch.UNKNOWN
    }

    private fun cityMatch(desired: DesiredLocation, observed: ObservedLocation, catalog: GeoCatalog?): FineMatch {
        val cityId = desired.cityId ?: return FineMatch.NOT_REQUESTED
        val city = catalog?.city(cityId) ?: return FineMatch.UNKNOWN
        val lat = observed.latitude
        val lon = observed.longitude
        if (lat != null && lon != null) {
            val km = GeoMath.distanceKm(city.latitude, city.longitude, lat, lon)
            return when {
                km <= CITY_MATCH_KM -> FineMatch.MATCH
                km > CITY_MISMATCH_KM -> FineMatch.MISMATCH
                else -> FineMatch.UNKNOWN
            }
        }
        val name = observed.cityName
        if (name != null && CountryCatalog.fold(name) == CountryCatalog.fold(city.name)) return FineMatch.MATCH
        return FineMatch.UNKNOWN
    }
}

/** Per-candidate states shown in the list. */
enum class CandidateState {
    NOT_CHECKED,
    CHECKING,
    VERIFIED_MATCH,
    COUNTRY_MATCH_ONLY,
    REGION_CITY_UNVERIFIED,
    NO_MATCH,
    CONNECTION_FAILED,
    GEO_UNAVAILABLE,
    STALE;

    /** The user may choose to use this server. NO_MATCH, failures and unknowns are never offered. */
    val isSelectable: Boolean
        get() = this == VERIFIED_MATCH || this == REGION_CITY_UNVERIFIED || this == COUNTRY_MATCH_ONLY
}

data class CandidateResult(
    val guid: String,
    val label: String,
    val state: CandidateState = CandidateState.NOT_CHECKED,
    val observed: ObservedLocation? = null,
    val match: MatchResult? = null,
    val checkedAtMillis: Long? = null,
    /**
     * Salted, per-scan fingerprint of the observed exit address, so servers that reported the *same*
     * address can be recognised without keeping the address itself. Comparable only within one scan.
     */
    val exitFingerprint: String? = null,
) {
    /** A result older than [ttlMillis] is shown as stale and cannot be selected. */
    fun effectiveState(nowMillis: Long, ttlMillis: Long = RESULT_TTL_MILLIS): CandidateState {
        val checked = checkedAtMillis ?: return state
        val settled = state != CandidateState.NOT_CHECKED && state != CandidateState.CHECKING
        return if (settled && (nowMillis - checked > ttlMillis || nowMillis < checked)) CandidateState.STALE else state
    }

    companion object {
        /** Exit assignments and networks change; a scan result is a snapshot, not a standing fact. */
        const val RESULT_TTL_MILLIS = 30 * 60 * 1000L
    }
}

object CandidateRanking {
    private fun rank(state: CandidateState): Int = when (state) {
        CandidateState.VERIFIED_MATCH -> 0
        CandidateState.REGION_CITY_UNVERIFIED -> 1
        CandidateState.COUNTRY_MATCH_ONLY -> 2
        CandidateState.NOT_CHECKED -> 3
        CandidateState.CHECKING -> 3
        CandidateState.STALE -> 4
        CandidateState.GEO_UNAVAILABLE -> 5
        CandidateState.CONNECTION_FAILED -> 6
        CandidateState.NO_MATCH -> 7
    }

    /** Best verified first; ties keep a stable order by label then guid. */
    fun sorted(results: List<CandidateResult>, nowMillis: Long): List<CandidateResult> =
        results.sortedWith(
            compareBy<CandidateResult> { rank(it.effectiveState(nowMillis)) }
                .thenBy { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.guid }
        )
}

/** Fields a provider response yielded, after validation. */
data class ParsedGeoIp(
    val ip: String?,
    val countryCode: String?,
    val regionCodeRaw: String?,
    val regionName: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
)

/**
 * Tolerant parser for the JSON shapes of common IP-geolocation providers (ip.sb, ipinfo, ipapi.co,
 * ip-api.com, Cloudflare meta). It is deterministic: for each field the keys are tried in a fixed order.
 * It never throws, bounds input size, and strips control characters from text.
 */
object GeoIpResponseParser {
    const val MAX_INPUT_CHARS = 64 * 1024
    private const val MAX_TEXT = 100

    private val englishNameToCode: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy(
            { CountryCatalog.fold(Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH)) },
            { it }
        )
    }

    fun parse(json: String?): ParsedGeoIp? {
        if (json.isNullOrBlank() || json.length > MAX_INPUT_CHARS) return null
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val o = root.asJsonObject

        val ip = text(o, "ip", "clientIp", "ip_addr", "query")
        val country = countryCode(o)

        // ip-api.com: `region` is a code and `regionName` the name. Elsewhere `region` is the name.
        val regionNameKey = text(o, "regionName", "region_name")
        val regionPlain = text(o, "region")
        val regionCodeKey = text(o, "region_code", "regionCode")
        val (regionCode, regionName) = when {
            regionNameKey != null -> (regionCodeKey ?: regionPlain?.takeIf { looksLikeCode(it) }) to regionNameKey
            else -> regionCodeKey to regionPlain
        }
        val (lat, lon) = coordinates(o)
        return ParsedGeoIp(ip, country, regionCode, regionName, text(o, "city"), lat, lon)
    }

    private fun looksLikeCode(s: String) = s.length in 1..3 && s.all { it.isLetterOrDigit() } && s == s.uppercase(Locale.ROOT)

    private fun countryCode(o: JsonObject): String? {
        val codeKeys = listOf("country_code", "countryCode")
        for (k in codeKeys) text(o, k)?.let { LocationProfileValidator.normalizeCountry(it) }?.let { return it }
        o.get("location")?.takeIf { it.isJsonObject }?.asJsonObject?.let { loc ->
            text(loc, "country_code")?.let { LocationProfileValidator.normalizeCountry(it) }?.let { return it }
        }
        for (k in listOf("country", "country_name")) {
            val v = text(o, k) ?: continue
            LocationProfileValidator.normalizeCountry(v)?.let { return it }
            englishNameToCode[CountryCatalog.fold(v)]?.let { return it }
        }
        return null
    }

    private fun coordinates(o: JsonObject): Pair<Double?, Double?> {
        var lat = number(o, "latitude", "lat")
        var lon = number(o, "longitude", "lon", "lng")
        if (lat == null || lon == null) {
            val loc = text(o, "loc")?.split(',')
            if (loc != null && loc.size == 2) {
                lat = loc[0].trim().toDoubleOrNull()
                lon = loc[1].trim().toDoubleOrNull()
            }
        }
        val valid = LocationProfileValidator.isValidLatitude(lat) && LocationProfileValidator.isValidLongitude(lon)
        return if (valid) lat to lon else null to null
    }

    private fun text(o: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val e: JsonElement = o.get(k) ?: continue
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) continue
            val cleaned = e.asString.filter { !it.isISOControl() }.trim().take(MAX_TEXT)
            if (cleaned.isNotEmpty()) return cleaned
        }
        return null
    }

    private fun number(o: JsonObject, vararg keys: String): Double? {
        for (k in keys) {
            val e = o.get(k) ?: continue
            if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) return e.asDouble
            if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString.toDoubleOrNull()?.let { return it }
        }
        return null
    }
}
