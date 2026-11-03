package com.usagelimits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The German resources are complete and say the same thing with the same arguments (R4).
 *
 * Lint's MissingTranslation catches a name that is absent, but not a translation that dropped
 * `%1$s` or turned `%%` into a bare `%`: both compile, and the first crashes or garbles a line
 * only when that string is shown in German. Comparing the format specifiers per name makes that a
 * test failure instead of a runtime surprise. Read as plain XML so no Android runtime is involved.
 */
class StringResourcesCompletenessTest {

    private class Catalog(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun load(path: String): Catalog {
        val file = File(path)
        assertTrue("${file.path} must exist (tests run from the module directory)", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).documentElement
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.getAttribute("translatable") == "false") continue
            when (node.tagName) {
                "string" -> strings[node.getAttribute("name")] = node.textContent
                "plurals" -> {
                    val items = linkedMapOf<String, String>()
                    val itemNodes = node.getElementsByTagName("item")
                    for (j in 0 until itemNodes.length) {
                        val item = itemNodes.item(j) as Element
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[node.getAttribute("name")] = items
                }
            }
        }
        return Catalog(strings, plurals)
    }

    private val english = load("src/main/res/values/strings.xml")
    private val german = load("src/main/res/values-de/strings.xml")

    /** `%1$s`, `%d`, `%%` ... in order of first appearance, as a sorted set for comparison. */
    private fun specifiers(text: String): List<String> =
        Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")
            .findAll(text).map { it.value }.distinct().sorted().toList()

    @Test
    fun `both languages define the same strings`() {
        assertEquals(english.strings.keys.sorted(), german.strings.keys.sorted())
    }

    @Test
    fun `both languages define the same plurals`() {
        assertEquals(english.plurals.keys.sorted(), german.plurals.keys.sorted())
    }

    @Test
    fun `every string keeps its format specifiers in German`() {
        val drifted = english.strings.filter { (name, text) ->
            specifiers(text) != specifiers(german.strings[name].orEmpty())
        }.keys
        assertTrue("specifiers differ for $drifted", drifted.isEmpty())
    }

    @Test
    fun `every plural keeps its format specifiers in German`() {
        val drifted = english.plurals.filter { (name, items) ->
            specifiers(items.values.joinToString(" ")) !=
                specifiers(german.plurals[name].orEmpty().values.joinToString(" "))
        }.keys
        assertTrue("specifiers differ for $drifted", drifted.isEmpty())
    }

    @Test
    fun `every plural has one and other in both languages`() {
        for ((language, catalog) in listOf("en" to english, "de" to german)) {
            for ((name, items) in catalog.plurals) {
                assertTrue("$language $name needs <item quantity=\"one\">", "one" in items)
                assertTrue("$language $name needs <item quantity=\"other\">", "other" in items)
            }
        }
    }

    @Test
    fun `no German string is blank`() {
        val blank = german.strings.filterValues { it.isBlank() }.keys +
            german.plurals.filterValues { items -> items.values.any { it.isBlank() } }.keys
        assertTrue("blank German entries: $blank", blank.isEmpty())
    }
}
