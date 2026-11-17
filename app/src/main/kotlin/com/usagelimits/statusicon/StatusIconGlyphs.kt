package com.usagelimits.statusicon

import com.usagelimits.core.model.ProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The provider silhouettes as SVG path data on a 24-unit square, one per provider plus the
 * fallback disc, read from `shared/glyphs/status-icon-glyphs.json`.
 *
 * The iOS Lock Screen widget draws the same file, so the two platforms cannot drift apart; that
 * file is the one place to change a glyph. Sources and licences are in `docs/provider-assets.md`.
 * The build packages `shared/glyphs` as a Java resource, so reading it needs no Context.
 */
class StatusIconGlyphs private constructor(val paths: Map<ProviderId, String>, val fallback: String) {

    companion object {
        private const val RESOURCE = "/status-icon-glyphs.json"
        private const val GRID = 24.0

        val shipped: StatusIconGlyphs by lazy {
            val stream = requireNotNull(StatusIconGlyphs::class.java.getResourceAsStream(RESOURCE)) {
                "$RESOURCE is not packaged; app/build.gradle.kts adds shared/glyphs as a resource folder"
            }
            parse(stream.bufferedReader().use { it.readText() })
        }

        /** Strict: exactly the known providers, a 24-unit grid, and no other keys. */
        fun parse(text: String): StatusIconGlyphs {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.keys == setOf("grid", "glyphs", "fallback")) { "unexpected keys ${root.keys}" }
            require(root.getValue("grid").jsonPrimitive.doubleOrNull == GRID) { "glyphs must be on a 24-unit grid" }
            val named = (root.getValue("glyphs") as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }
            require(named.keys == ProviderId.entries.map { it.id }.toSet()) {
                "glyphs ${named.keys.sorted()} do not match providers ${ProviderId.entries.map { it.id }.sorted()}"
            }
            return StatusIconGlyphs(
                paths = ProviderId.entries.associateWith { named.getValue(it.id) },
                fallback = root.getValue("fallback").jsonPrimitive.content,
            )
        }
    }
}
