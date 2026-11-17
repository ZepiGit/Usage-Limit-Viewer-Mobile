package com.usagelimits.statusicon

import com.usagelimits.core.model.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * The glyph file is shared with iOS (`shared/glyphs/status-icon-glyphs.json`), so it is read
 * strictly: a provider missing from it, one it names that this build does not know, or path data
 * outside the shared grammar fails here rather than drawing differently from the iOS widget.
 */
class StatusIconGlyphsTest {

    @Serializable
    private data class Grammar(val description: String, val accept: List<Case>, val reject: List<Case>) {
        @Serializable
        data class Case(val name: String, val path: String)
    }

    @Test
    fun `the shipped file has one distinct glyph per provider and a separate fallback`() {
        val glyphs = StatusIconGlyphs.shipped
        assertEquals(ProviderId.entries.toSet(), glyphs.paths.keys)
        assertEquals(ProviderId.entries.size, glyphs.paths.values.toSet().size)
        for (path in glyphs.paths.values) assertNotEquals(glyphs.fallback, path)
    }

    /** The same cases the iOS StatusIconGlyphsTests read, so both readers accept one grammar. */
    @Test
    fun `both platforms accept and reject the shared grammar cases`() {
        val grammar = Json.decodeFromString<Grammar>(File("../shared/test-vectors/glyph-path-grammar.json").readText())
        assertFalse(grammar.accept.isEmpty())
        assertFalse(grammar.reject.isEmpty())
        for (valid in grammar.accept) StatusIconGlyphs.validatePath(valid.path, valid.name)
        for (invalid in grammar.reject) {
            assertThrows(invalid.name, IllegalArgumentException::class.java) {
                StatusIconGlyphs.validatePath(invalid.path, invalid.name)
            }
        }
    }

    @Test
    fun `a file that does not match the providers or the grammar is rejected`() {
        val all = ProviderId.entries.joinToString(",") { "\"${it.id}\": \"M0 0H1Z\"" }
        val allButOne = ProviderId.entries.drop(1).joinToString(",") { "\"${it.id}\": \"M0 0H1Z\"" }
        val first = ProviderId.entries.first().id
        val files = listOf(
            """{"grid": 24, "glyphs": {}, "fallback": "M0 0H1Z"}""",
            """{"grid": 24, "glyphs": {$all, "other": "M0 0H1Z"}, "fallback": "M0 0H1Z"}""",
            """{"grid": 48, "glyphs": {$all}, "fallback": "M0 0H1Z"}""",
            """{"grid": 24, "glyphs": {$all}, "fallback": "M0 0H1Z", "x": 1}""",
            """{"grid": 24, "glyphs": {$all}}""",
            """{"grid": 24, "glyphs": {$allButOne, "$first": 12}, "fallback": "M0 0H1Z"}""",
            """{"grid": 24, "glyphs": {$all}, "fallback": 12}""",
            """{"grid": 24, "glyphs": {$allButOne, "$first": "M0 0H1X2 2Z"}, "fallback": "M0 0H1Z"}""",
            """[]""",
        )
        for (file in files) assertThrows(file, IllegalArgumentException::class.java) { StatusIconGlyphs.parse(file) }
        StatusIconGlyphs.parse("""{"grid": 24, "glyphs": {$all}, "fallback": "M0 0H1Z"}""")
    }
}
