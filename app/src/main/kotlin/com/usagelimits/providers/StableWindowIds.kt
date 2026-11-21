package com.usagelimits.providers

import java.util.Base64

/** Kimi and Z.ai share this byte-exact identity contract with their Swift parsers. */
internal class StableWindowIds {
    private val occurrences = mutableMapOf<String, Int>()

    fun next(base: String): String {
        // Equal stable fields cannot identify individual rows; preserve them with unique occurrence IDs.
        val occurrence = (occurrences[base] ?: 0) + 1
        occurrences[base] = occurrence
        return if (occurrence == 1) base else "$base:$occurrence"
    }

    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        // ':' is outside Base64URL, so text, duration and occurrence cannot collide at a boundary.
        fun text(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}
