package com.usagelimits.statusicon

import com.usagelimits.core.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The glyph file is shared with iOS (`shared/glyphs/status-icon-glyphs.json`), so it is read
 * strictly: a provider missing from it, or one it names that this build does not know, fails here
 * rather than falling back to the disc in the status bar.
 */
class StatusIconGlyphsTest {

    @Test
    fun `the shipped file has one distinct glyph per provider and a separate fallback`() {
        val glyphs = StatusIconGlyphs.shipped
        assertEquals(ProviderId.entries.toSet(), glyphs.paths.keys)
        assertEquals(ProviderId.entries.size, glyphs.paths.values.toSet().size)
        for (path in glyphs.paths.values) assertNotEquals(glyphs.fallback, path)
    }

    @Test
    fun `a file that does not match the providers is rejected`() {
        val all = ProviderId.entries.joinToString(",") { "\"${it.id}\": \"M0 0H1Z\"" }
        val files = listOf(
            """{"grid": 24, "glyphs": {}, "fallback": "M0 0H1Z"}""",
            """{"grid": 24, "glyphs": {$all, "other": "M0 0H1Z"}, "fallback": "M0 0H1Z"}""",
            """{"grid": 48, "glyphs": {$all}, "fallback": "M0 0H1Z"}""",
            """{"grid": 24, "glyphs": {$all}, "fallback": "M0 0H1Z", "x": 1}""",
            """{"grid": 24, "glyphs": {$all}}""",
        )
        for (file in files) assertThrows(file, IllegalArgumentException::class.java) { StatusIconGlyphs.parse(file) }
        StatusIconGlyphs.parse("""{"grid": 24, "glyphs": {$all}, "fallback": "M0 0H1Z"}""")
    }
}
