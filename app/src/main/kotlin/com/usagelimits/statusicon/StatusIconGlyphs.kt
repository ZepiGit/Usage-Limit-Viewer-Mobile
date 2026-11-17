package com.usagelimits.statusicon

import com.usagelimits.core.model.ProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The provider silhouettes as path data on a 24-unit square, one per provider plus the fallback
 * disc, read from `shared/glyphs/status-icon-glyphs.json`.
 *
 * The iOS Lock Screen widget draws the same file, so the two platforms cannot drift apart; that
 * file is the one place to change a glyph. Sources and licences are in `docs/provider-assets.md`.
 * The build packages `shared/glyphs` as a Java resource, so reading it needs no Context.
 *
 * Both readers accept exactly the grammar in `docs/provider-assets.md` ("Glyph path grammar") and
 * reject the rest, so a path that one platform would draw cannot be skipped or misread by the
 * other. AndroidX's parser is more lenient (it skips unknown letters), so [validatePath] runs first.
 */
class StatusIconGlyphs private constructor(val paths: Map<ProviderId, String>, val fallback: String) {

    companion object {
        private const val RESOURCE = "/status-icon-glyphs.json"
        private const val GRID = 24.0

        /** Operands per command; Z takes none. */
        private val ARITY = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'Z' to 0)
        private val NUMBER = Regex("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")

        val shipped: StatusIconGlyphs by lazy {
            val stream = requireNotNull(StatusIconGlyphs::class.java.getResourceAsStream(RESOURCE)) {
                "$RESOURCE is not packaged; app/build.gradle.kts adds shared/glyphs as a resource folder"
            }
            parse(stream.bufferedReader().use { it.readText() })
        }

        /** Strict: exactly the known providers, a 24-unit grid, string paths in the shared grammar. */
        fun parse(text: String): StatusIconGlyphs {
            val root = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("the glyph file must be a JSON object")
            require(root.keys == setOf("grid", "glyphs", "fallback")) { "unexpected keys ${root.keys}" }
            require((root.getValue("grid") as? JsonPrimitive)?.doubleOrNull == GRID) { "glyphs must be on a 24-unit grid" }
            val glyphs = root.getValue("glyphs") as? JsonObject
                ?: throw IllegalArgumentException("glyphs must be an object of provider to path")
            val named = glyphs.mapValues { (key, value) -> pathString(value, key) }
            require(named.keys == ProviderId.entries.map { it.id }.toSet()) {
                "glyphs ${named.keys.sorted()} do not match providers ${ProviderId.entries.map { it.id }.sorted()}"
            }
            return StatusIconGlyphs(
                paths = ProviderId.entries.associateWith { named.getValue(it.id) },
                fallback = pathString(root.getValue("fallback"), "fallback"),
            )
        }

        private fun pathString(value: Any, name: String): String {
            val primitive = value as? JsonPrimitive
            require(primitive != null && primitive.isString) { "$name must be a string of path data" }
            return primitive.content.also { validatePath(it, name) }
        }

        /**
         * Absolute M, L, H, V, C and Z with their operand counts; a command repeats while operands
         * follow, and pairs after M continue as lines. Numbers: optional minus, digits with at most
         * one decimal point, no exponent. Separators: spaces and commas.
         */
        fun validatePath(data: String, name: String = "path") {
            var index = 0
            fun skipSeparators() { while (index < data.length && (data[index] == ' ' || data[index] == ',')) index++ }
            var command: Char? = null
            var commands = 0
            while (true) {
                skipSeparators()
                if (index == data.length) break
                val c = data[index]
                if (c.isLetter()) {
                    require(c in ARITY) { "$name: unsupported path command '$c' (absolute M L H V C Z only)" }
                    require(commands > 0 || c == 'M') { "$name: path data must start with M" }
                    command = c
                    commands++
                    index++
                    if (c == 'Z') continue
                } else {
                    require(command != null && command != 'Z') { "$name: operands without a command at $index" }
                    if (command == 'M') command = 'L'
                }
                repeat(ARITY.getValue(command!!)) {
                    skipSeparators()
                    val match = NUMBER.matchAt(data, index)
                    require(match != null) { "$name: command $command expects ${ARITY.getValue(command!!)} numbers at $index" }
                    index = match.range.last + 1
                    require(index == data.length || data[index] in " ,-" || data[index].isLetter()) {
                        "$name: malformed number at ${match.range.first}"
                    }
                }
            }
            require(commands > 0) { "$name: empty path data" }
        }
    }
}
