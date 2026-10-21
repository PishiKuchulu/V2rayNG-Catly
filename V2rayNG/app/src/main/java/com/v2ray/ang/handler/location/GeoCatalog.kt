package com.v2ray.ang.handler.location

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geographic metadata only. This is *not* an inventory of exit servers: a city being listed here
 * says nothing about whether any VPN endpoint exists there.
 */
data class GeoRegion(val code: String, val name: String, val countryCode: String)

data class GeoCity(
    val id: String,
    val name: String,
    val countryCode: String,
    /** ISO 3166-2 code, or null when the source data does not map to one reliably. */
    val regionCode: String?,
    val latitude: Double,
    val longitude: Double,
    val population: Long,
)

data class CatalogMeta(
    val generated: String? = null,
    val cityPopulationMin: Long? = null,
)

class GeoCatalog(
    regions: List<GeoRegion>,
    cities: List<GeoCity>,
    val meta: CatalogMeta = CatalogMeta(),
    /** Human-readable parse problems (capped). Empty for a clean catalog. */
    val issues: List<String> = emptyList(),
) {
    private val regionsByCode = regions.associateBy { it.code }
    private val regionsByCountry = regions.groupBy { it.countryCode }
    private val citiesById = cities.associateBy { it.id }
    private val citiesByCountry = cities.groupBy { it.countryCode }
    private val duplicateNames: Map<String, Set<String>> = citiesByCountry.mapValues { (_, list) ->
        list.groupBy { it.name }.filterValues { it.size > 1 }.keys
    }

    val isEmpty: Boolean get() = regionsByCode.isEmpty() && citiesById.isEmpty()
    val regionCount: Int get() = regionsByCode.size
    val cityCount: Int get() = citiesById.size

    fun region(code: String?): GeoRegion? = code?.let(regionsByCode::get)
    fun city(id: String?): GeoCity? = id?.let(citiesById::get)
    fun regionsOf(countryCode: String): List<GeoRegion> = regionsByCountry[countryCode].orEmpty()

    /**
     * Cities of a country. When [regionCode] is given, only cities *linked* to that region are
     * returned; cities without a reliable region link are deliberately not guessed into it.
     */
    fun citiesOf(countryCode: String, regionCode: String? = null): List<GeoCity> {
        val all = citiesByCountry[countryCode].orEmpty()
        return if (regionCode == null) all else all.filter { it.regionCode == regionCode }
    }

    /** Label that stays unambiguous when the same name occurs twice in one country. */
    fun cityLabel(city: GeoCity): String {
        val ambiguous = city.name in duplicateNames[city.countryCode].orEmpty()
        if (!ambiguous) return city.name
        val regionName = region(city.regionCode)?.name
        return if (regionName != null) "${city.name} ($regionName)" else "${city.name} (#${city.id})"
    }

    /** Nearest-label helper for status text: region name, else null. */
    fun regionName(code: String?): String? = region(code)?.name

    companion object {
        val EMPTY = GeoCatalog(emptyList(), emptyList())
    }
}

object GeoCatalogParser {
    const val SUPPORTED_SCHEMA = 1
    private const val MAX_ISSUES = 20

    /** Never throws: a broken or unsupported asset yields an empty catalog and an issue. */
    fun parse(stream: InputStream): GeoCatalog = try {
        parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (e: Exception) {
        GeoCatalog(emptyList(), emptyList(), issues = listOf("unreadable: ${e.javaClass.simpleName}"))
    }

    fun parse(json: String): GeoCatalog {
        val issues = mutableListOf<String>()
        fun issue(message: String) {
            if (issues.size < MAX_ISSUES) issues += message
        }
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return GeoCatalog(emptyList(), emptyList(), issues = listOf("malformed json"))
        }
        if (!root.isJsonObject) return GeoCatalog(emptyList(), emptyList(), issues = listOf("root is not an object"))
        val obj = root.asJsonObject
        val schema = obj.intOrNull("schema")
        if (schema != SUPPORTED_SCHEMA) {
            return GeoCatalog(emptyList(), emptyList(), issues = listOf("unsupported schema: $schema"))
        }

        val regions = LinkedHashMap<String, GeoRegion>()
        obj.objectOrNull("regions")?.entrySet()?.forEach { (cc, element) ->
            if (!LocationProfileValidator.isValidCountry(cc) || !element.isJsonArray) {
                issue("bad region country: $cc")
                return@forEach
            }
            element.asJsonArray.forEach rows@{ row ->
                val cells = row.cellsOrNull(2)
                val code = cells?.get(0)?.stringOrNull()
                val name = cells?.get(1)?.stringOrNull()
                when {
                    code == null || name.isNullOrBlank() -> issue("bad region row in $cc")
                    !code.startsWith("$cc-") -> issue("region $code not in $cc")
                    code in regions -> issue("duplicate region $code")
                    else -> regions[code] = GeoRegion(code, name, cc)
                }
            }
        }

        val cities = LinkedHashMap<String, GeoCity>()
        var unlinked = 0
        obj.objectOrNull("cities")?.entrySet()?.forEach { (cc, element) ->
            if (!LocationProfileValidator.isValidCountry(cc) || !element.isJsonArray) {
                issue("bad city country: $cc")
                return@forEach
            }
            element.asJsonArray.forEach rows@{ row ->
                val cells = row.cellsOrNull(6)
                if (cells == null) {
                    issue("bad city row in $cc")
                    return@rows
                }
                val id = cells[0].stringOrNull()
                val name = cells[1].stringOrNull()
                val regionRaw = cells[2].stringOrNull()
                val lat = cells[3].doubleOrNull()
                val lon = cells[4].doubleOrNull()
                val pop = cells[5].doubleOrNull()?.toLong() ?: 0L
                when {
                    id.isNullOrBlank() || name.isNullOrBlank() -> issue("bad city row in $cc")
                    !LocationProfileValidator.isValidLatitude(lat) ||
                        !LocationProfileValidator.isValidLongitude(lon) -> issue("bad coordinates for city $id")
                    id in cities -> issue("duplicate city $id")
                    else -> {
                        val linked = regionRaw?.takeIf { it.isNotEmpty() && it in regions }
                        if (!regionRaw.isNullOrEmpty() && linked == null) unlinked++
                        cities[id] = GeoCity(id, name, cc, linked, lat!!, lon!!, pop)
                    }
                }
            }
        }
        if (unlinked > 0) issue("$unlinked cities reference an unknown region; left unlinked")

        val metaObj = obj.objectOrNull("meta")
        val meta = CatalogMeta(
            generated = metaObj?.get("generated")?.stringOrNull(),
            cityPopulationMin = metaObj?.get("cityPopulationMin")?.doubleOrNull()?.toLong(),
        )
        return GeoCatalog(regions.values.toList(), cities.values.toList(), meta, issues)
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.intOrNull(name: String): Int? = get(name)?.doubleOrNull()?.toInt()

    private fun JsonElement.cellsOrNull(minSize: Int): JsonArray? =
        takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() >= minSize }

    private fun JsonElement.stringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.doubleOrNull(): Double? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
}

data class CountryEntry(val code: String, val name: String)

/**
 * ISO 3166-1 alpha-2 countries derived from the platform, so names follow the device language and
 * no country list is bundled or hard-coded. Names depend on the platform's locale data.
 */
object CountryCatalog {

    fun entries(displayLocale: Locale): List<CountryEntry> {
        val collator = java.text.Collator.getInstance(displayLocale)
        return Locale.getISOCountries()
            .mapNotNull { code ->
                val name = Locale.Builder().setRegion(code).build().getDisplayCountry(displayLocale)
                // A platform without data for a code returns the code itself; keep it, but visibly.
                CountryEntry(code, name.ifBlank { code })
            }
            .sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    fun nameOf(code: String?, displayLocale: Locale): String? {
        val normalized = LocationProfileValidator.normalizeCountry(code) ?: return null
        return Locale.Builder().setRegion(normalized).build().getDisplayCountry(displayLocale).ifBlank { normalized }
    }

    /** Diacritic- and case-insensitive matching ("koln" finds "Köln"). Matches name or code. */
    fun matches(query: String, vararg haystacks: String): Boolean {
        val needle = fold(query)
        if (needle.isEmpty()) return true
        return haystacks.any { fold(it).contains(needle) }
    }

    fun fold(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
}

object GeoMath {
    private const val EARTH_RADIUS_KM = 6371.0088

    /** Great-circle distance in kilometres. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
