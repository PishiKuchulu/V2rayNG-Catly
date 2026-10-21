package com.v2ray.ang.handler.location

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class LocationProfileStoreData(
    val profiles: List<LocationProfile> = emptyList(),
    val activeProfileId: String? = null,
)

data class DecodeResult(
    val data: LocationProfileStoreData,
    /** Non-fatal problems found while decoding, for logging. Contains no profile contents. */
    val issues: List<String>,
    /** True when the stored payload could not be fully understood and the caller should back it up. */
    val needsBackup: Boolean,
)

/**
 * Reads and writes the profile envelope as JSON.
 *
 * Deliberately hand-written instead of reflection-based: Gson bypasses Kotlin default values and
 * non-null types for missing or wrongly typed fields, which is exactly the malformed-data case
 * this must survive. Every field is type-checked and invalid values are dropped individually.
 */
object LocationProfileCodec {
    const val MAX_PROFILES = 20

    fun encode(data: LocationProfileStoreData): String {
        val root = JsonObject()
        root.addProperty("schema", LocationProfile.CURRENT_SCHEMA_VERSION)
        data.activeProfileId?.let { root.addProperty("active", it) }
        val array = JsonArray()
        data.profiles.take(MAX_PROFILES).forEach { array.add(encodeProfile(it)) }
        root.add("profiles", array)
        return root.toString()
    }

    private fun encodeProfile(p: LocationProfile): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", p.schemaVersion)
        addProperty("id", p.id)
        addProperty("name", p.name)
        p.countryCode?.let { addProperty("countryCode", it) }
        p.regionCode?.let { addProperty("regionCode", it) }
        p.cityId?.let { addProperty("cityId", it) }
        p.serverGuid?.let { addProperty("serverGuid", it) }
        addProperty("matchStrictness", p.matchStrictness.wireValue)
        p.gpsLatitude?.let { addProperty("gpsLatitude", it) }
        p.gpsLongitude?.let { addProperty("gpsLongitude", it) }
        p.timeZoneId?.let { addProperty("timeZoneId", it) }
        p.localeTag?.let { addProperty("localeTag", it) }
    }

    /** Never throws. Blank input is a normal "nothing stored yet" and needs no backup. */
    fun decode(json: String?): DecodeResult {
        if (json.isNullOrBlank()) return DecodeResult(LocationProfileStoreData(), emptyList(), false)
        val issues = mutableListOf<String>()
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return DecodeResult(LocationProfileStoreData(), listOf("malformed json"), true)
        }
        if (!root.isJsonObject) return DecodeResult(LocationProfileStoreData(), listOf("root is not an object"), true)
        val obj = root.asJsonObject

        val schema = obj.get("schema")?.takeIf { it.isNumber() }?.asInt
        if (schema != null && schema > LocationProfile.CURRENT_SCHEMA_VERSION) {
            // Written by a newer app version. Do not guess at fields we do not know.
            return DecodeResult(LocationProfileStoreData(), listOf("newer schema: $schema"), true)
        }

        var needsBackup = false
        val seen = LinkedHashMap<String, LocationProfile>()
        val rows = obj.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray
        if (obj.has("profiles") && rows == null) {
            issues += "profiles is not an array"
            needsBackup = true
        }
        rows?.forEach { element ->
            if (seen.size >= MAX_PROFILES) {
                issues += "profile limit reached; extra profiles dropped"
                needsBackup = true
                return@forEach
            }
            val decoded = decodeProfile(element, issues)
            if (decoded == null) {
                needsBackup = true
            } else if (decoded.id in seen) {
                issues += "duplicate profile id dropped"
                needsBackup = true
            } else {
                seen[decoded.id] = decoded
            }
        }
        val active = obj.get("active")?.stringOrNull()?.takeIf { it in seen }
        if (obj.has("active") && active == null && seen.isNotEmpty()) issues += "active profile id not found"
        return DecodeResult(LocationProfileStoreData(seen.values.toList(), active), issues, needsBackup)
    }

    private fun decodeProfile(element: JsonElement, issues: MutableList<String>): LocationProfile? {
        if (!element.isJsonObject) {
            issues += "profile is not an object"
            return null
        }
        val o = element.asJsonObject
        val version = o.get("schemaVersion")?.takeIf { it.isNumber() }?.asInt
            ?: LocationProfile.CURRENT_SCHEMA_VERSION // no version recorded: treat as current
        if (version > LocationProfile.CURRENT_SCHEMA_VERSION || version < 1) {
            issues += "unsupported profile schema: $version"
            return null
        }
        val id = o.get("id")?.stringOrNull()?.takeIf { it.isNotBlank() }
        if (id == null) {
            issues += "profile without id dropped"
            return null
        }

        fun dropped(field: String) {
            issues += "invalid $field dropped"
        }

        val country = o.get("countryCode")?.stringOrNull()?.let { raw ->
            LocationProfileValidator.normalizeCountry(raw).also { if (it == null) dropped("countryCode") }
        }
        val lat = o.get("gpsLatitude")?.takeIf { it.isNumber() }?.asDouble
        val lon = o.get("gpsLongitude")?.takeIf { it.isNumber() }?.asDouble
        val gpsOk = lat != null && lon != null &&
            LocationProfileValidator.isValidLatitude(lat) && LocationProfileValidator.isValidLongitude(lon)
        if ((lat != null || lon != null) && !gpsOk) dropped("gps")
        val tz = o.get("timeZoneId")?.stringOrNull()?.let { raw ->
            raw.takeIf(LocationProfileValidator::isValidTimeZone).also { if (it == null) dropped("timeZoneId") }
        }
        val locale = o.get("localeTag")?.stringOrNull()?.let { raw ->
            raw.takeIf(LocationProfileValidator::isValidLocaleTag).also { if (it == null) dropped("localeTag") }
        }
        // Region/city are validated against the catalog at use time (the catalog may be absent here).
        return LocationProfile(
            schemaVersion = LocationProfile.CURRENT_SCHEMA_VERSION,
            id = id,
            name = (o.get("name")?.stringOrNull() ?: "").take(LocationProfile.MAX_NAME_LENGTH),
            countryCode = country,
            regionCode = o.get("regionCode")?.stringOrNull()?.takeIf { it.isNotBlank() },
            cityId = o.get("cityId")?.stringOrNull()?.takeIf { it.isNotBlank() },
            serverGuid = o.get("serverGuid")?.stringOrNull()?.takeIf { it.isNotBlank() },
            // Absent (data written before this field existed) or unknown value: use the default.
            matchStrictness = MatchStrictness.fromWire(o.get("matchStrictness")?.stringOrNull())
                ?: MatchStrictness.DEFAULT,
            gpsLatitude = if (gpsOk) lat else null,
            gpsLongitude = if (gpsOk) lon else null,
            timeZoneId = tz,
            localeTag = locale,
        )
    }

    private fun JsonElement.isNumber(): Boolean = isJsonPrimitive && asJsonPrimitive.isNumber

    private fun JsonElement.stringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
