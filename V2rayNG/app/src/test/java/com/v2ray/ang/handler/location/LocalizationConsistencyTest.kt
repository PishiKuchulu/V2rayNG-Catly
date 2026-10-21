package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the real resource files; Gradle runs unit tests with the module directory as working dir. */
class LocalizationConsistencyTest {

    private val resDir = File("src/main/res")
    private val placeholder = Regex("%(\\d+\\$)?[sd]")

    private fun load(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val n = nodes.item(it)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }

    private val locales: Map<String, Map<String, String>> by lazy {
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }.filter { it.isFile }
            .associate { it.parentFile.name to load(it) }
    }

    private val customKeys: Set<String> get() = locales.getValue("values").keys.filter { it.startsWith("cl_") }.toSet()

    @Test
    fun everyLocaleDefinesEveryCustomLocationKey() {
        val untranslatable = setOf("cl_ip_v4", "cl_ip_v6")
        assertTrue(locales.size >= 10, "expected the 10 shipped locales, found ${locales.keys}")
        for ((name, strings) in locales) {
            if (name == "values") continue
            val missing = (customKeys - untranslatable).filter { it !in strings }
            assertTrue(missing.isEmpty(), "$name is missing $missing")
        }
    }

    @Test
    fun placeholdersMatchTheDefaultLanguageInEveryLocale() {
        val base = locales.getValue("values")
        for ((name, strings) in locales) {
            for (key in customKeys) {
                val translated = strings[key] ?: continue
                assertEquals(
                    placeholder.findAll(base.getValue(key)).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.toList().sorted(),
                    placeholder.findAll(translated).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.toList().sorted(),
                    "$name/$key placeholder mismatch"
                )
            }
        }
    }

    @Test
    fun noCustomLocationStringIsBlankAndNoneClaimsSuccessWithoutVerification() {
        val base = locales.getValue("values")
        for (key in customKeys) assertTrue(base.getValue(key).isNotBlank(), key)
        // Selection-only wording must not use success words; success states are verification states.
        for (key in listOf("cl_cand_not_checked", "cl_cand_checking", "cl_server_title", "cl_enable_title")) {
            val text = base[key] ?: continue
            assertFalse(Regex("applied|matched|verified", RegexOption.IGNORE_CASE).containsMatchIn(text), "$key: $text")
        }
    }

    @Test
    fun theOriginalKeySetIsUntouchedByTheNewStrings() {
        // Every locale keeps the same set of non-cl keys as the default language (no accidental removal).
        val base = locales.getValue("values").keys.filterNot { it.startsWith("cl_") }.toSet()
        for ((name, strings) in locales) {
            if (name == "values") continue
            val missing = base.filter { it !in strings }
            assertTrue(missing.size <= base.size, name) // present for diagnostics
            assertEquals(0, missing.size, "$name lost original keys: ${missing.take(5)}")
        }
    }
}
