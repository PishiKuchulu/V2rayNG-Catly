package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

class GeoCatalogTest {

    private fun catalog(json: String) = GeoCatalogParser.parse(json)

    private val valid = """
        {"schema":1,"meta":{"generated":"2026-01-01","cityPopulationMin":500000},
         "regions":{"US":[["US-NY","New York"],["US-CA","California"]],"DE":[["DE-BE","Berlin"]]},
         "cities":{"US":[["1","New York City","US-NY",40.7,-74.0,8000000],["2","Los Angeles","US-CA",34.0,-118.2,3900000]],
                   "DE":[["3","Berlin","DE-BE",52.5,13.4,3400000]]}}
    """.trimIndent()

    // ---- country identifiers -------------------------------------------------------------

    @Test
    fun countryIdentifiersAreValidatedAgainstIso3166() {
        assertEquals("US", LocationProfileValidator.normalizeCountry("us"))
        assertEquals("DE", LocationProfileValidator.normalizeCountry(" DE "))
        assertNull(LocationProfileValidator.normalizeCountry("USA"))
        assertNull(LocationProfileValidator.normalizeCountry("ZZ"))
        assertNull(LocationProfileValidator.normalizeCountry(""))
        assertNull(LocationProfileValidator.normalizeCountry(null))
        assertNull(LocationProfileValidator.normalizeCountry("U1"))
    }

    @Test
    fun countryListIsDerivedFromPlatformAndLocalized() {
        val en = CountryCatalog.entries(Locale.ENGLISH)
        assertEquals(Locale.getISOCountries().size, en.size)
        assertEquals("Germany", CountryCatalog.nameOf("DE", Locale.ENGLISH))
        assertEquals("Deutschland", CountryCatalog.nameOf("DE", Locale.GERMAN))
        assertNull(CountryCatalog.nameOf("ZZ", Locale.ENGLISH))
        assertEquals(en.map { it.name }, en.map { it.name }.sortedWith(java.text.Collator.getInstance(Locale.ENGLISH)))
    }

    @Test
    fun searchIgnoresCaseAndDiacritics() {
        assertTrue(CountryCatalog.matches("koln", "Köln"))
        assertTrue(CountryCatalog.matches("GERM", "Germany", "DE"))
        assertTrue(CountryCatalog.matches("de", "Germany", "DE"))
        assertFalse(CountryCatalog.matches("fr", "Germany", "DE"))
        assertTrue(CountryCatalog.matches("   ", "anything"))
    }

    // ---- parser ------------------------------------------------------------------------

    @Test
    fun parsesValidCatalog() {
        val c = catalog(valid)
        assertTrue(c.issues.isEmpty())
        assertEquals(3, c.regionCount)
        assertEquals(3, c.cityCount)
        assertEquals("US", c.city("1")!!.countryCode)
        assertEquals("US-NY", c.city("1")!!.regionCode)
        assertEquals("2026-01-01", c.meta.generated)
        assertEquals(1, c.regionsOf("DE").size)
        assertEquals(listOf("1"), c.citiesOf("US", "US-NY").map { it.id })
    }

    @Test
    fun missingCityMetadataYieldsEmptyListsNotErrors() {
        val c = catalog("""{"schema":1,"regions":{"US":[["US-NY","New York"]]}}""")
        assertEquals(1, c.regionCount)
        assertEquals(0, c.cityCount)
        assertTrue(c.citiesOf("US").isEmpty())
        assertTrue(c.regionsOf("FR").isEmpty())
        assertNull(c.city("999"))
        assertNull(c.region(null))
    }

    @Test
    fun unavailableOrBrokenCatalogDegradesToEmptyWithAnIssue() {
        val garbage = catalog("not json at all {")
        assertTrue(garbage.isEmpty)
        assertTrue(garbage.issues.isNotEmpty())
        assertTrue(catalog("[1,2,3]").isEmpty)
        assertTrue(catalog("""{"schema":99,"regions":{}}""").isEmpty)
        assertTrue(catalog("""{"regions":{}}""").issues.any { it.contains("schema") })
        assertTrue(GeoCatalogParser.parse("".byteInputStream()).isEmpty)
    }

    @Test
    fun duplicateAndMalformedRowsAreDroppedAndReported() {
        val c = catalog(
            """
            {"schema":1,
             "regions":{"US":[["US-NY","New York"],["US-NY","Dup"],["DE-BE","wrong country"],["US-XX"],["US-TX",""]],
                        "ZZ":[["ZZ-A","bad country"]]},
             "cities":{"US":[["1","A","US-NY",1,1,10],["1","Dup","US-NY",2,2,10],
                             ["2","BadLat","US-NY",95,1,10],["3","NoCoords","US-NY","x",1,10],
                             ["4","UnknownRegion","US-ZZ",5,5,10],[7]]}}
            """.trimIndent()
        )
        assertEquals(1, c.regionCount)
        assertEquals("New York", c.region("US-NY")!!.name)
        assertEquals(2, c.cityCount) // "1" and "4"
        assertEquals("A", c.city("1")!!.name)
        assertNull(c.city("4")!!.regionCode) // unknown region link is not guessed
        assertTrue(c.issues.any { it.contains("duplicate region") })
        assertTrue(c.issues.any { it.contains("duplicate city") })
        assertTrue(c.issues.any { it.contains("bad coordinates") })
        assertTrue(c.issues.any { it.contains("unknown region") })
    }

    @Test
    fun ambiguousCityNamesGetDisambiguatingLabels() {
        val c = catalog(
            """
            {"schema":1,"regions":{"US":[["US-VA","Virginia"],["US-OR","Oregon"]]},
             "cities":{"US":[["1","Springfield","US-VA",1,1,9],["2","Springfield","US-OR",2,2,8],
                             ["3","Springfield","",3,3,7],["4","Boston","",4,4,6]]}}
            """.trimIndent()
        )
        assertEquals("Springfield (Virginia)", c.cityLabel(c.city("1")!!))
        assertEquals("Springfield (Oregon)", c.cityLabel(c.city("2")!!))
        assertEquals("Springfield (#3)", c.cityLabel(c.city("3")!!))
        assertEquals("Boston", c.cityLabel(c.city("4")!!))
    }

    @Test
    fun citiesWithoutRegionLinkAreNotGuessedIntoARegion() {
        val c = catalog(
            """{"schema":1,"regions":{"US":[["US-NY","New York"]]},
                "cities":{"US":[["1","Linked","US-NY",1,1,5],["2","Unlinked","",2,2,5]]}}"""
        )
        assertEquals(listOf("1"), c.citiesOf("US", "US-NY").map { it.id })
        assertEquals(2, c.citiesOf("US").size)
    }

    // ---- the real bundled asset ----------------------------------------------------------

    private fun realAsset(): GeoCatalog {
        val file = File("src/main/assets/location_catalog.json")
        assertTrue(file.isFile, "bundled catalog missing at ${file.absolutePath}")
        return file.inputStream().use { GeoCatalogParser.parse(it) }
    }

    @Test
    fun bundledCatalogParsesCleanly() {
        val c = realAsset()
        assertTrue(c.issues.isEmpty(), "catalog issues: ${c.issues}")
        assertNotNull(c.meta.generated)
        assertTrue(c.regionCount > 3000)
        assertTrue(c.cityCount > 500)
        assertEquals("New York", c.region("US-NY")!!.name)
        assertEquals(16, c.regionsOf("DE").size)
        assertTrue(c.citiesOf("DE").any { it.name == "Berlin" })
        assertTrue(c.citiesOf("US").any { it.name == "Los Angeles" && it.regionCode == "US-CA" })
    }

    @Test
    fun bundledCatalogHasNoAmbiguousLabelsAndOnlyValidCountries() {
        val c = realAsset()
        for (country in Locale.getISOCountries()) {
            val labels = c.citiesOf(country).map { c.cityLabel(it) }
            assertEquals(labels.size, labels.toSet().size, "ambiguous labels in $country")
            c.citiesOf(country).forEach { city ->
                assertEquals(country, city.countryCode)
                city.regionCode?.let { assertEquals(country, c.region(it)!!.countryCode) }
            }
        }
    }

    @Test
    fun distanceHelperMatchesKnownDistances() {
        val berlinParis = GeoMath.distanceKm(52.52, 13.405, 48.857, 2.352)
        assertTrue(berlinParis in 870.0..890.0, "got $berlinParis")
        assertEquals(0.0, GeoMath.distanceKm(10.0, 10.0, 10.0, 10.0), 0.0001)
    }
}
