package com.usagelimits.core.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import java.io.File

internal object SharedVectors {
    private val json = Json {
        ignoreUnknownKeys = false
        // Valid JSON encodes the non-finite presentation input as the string "NaN".
        allowSpecialFloatingPointValues = true
    }

    /** Gradle runs unit tests from the module directory. */
    fun file(name: String) = File("../shared/test-vectors/$name")

    fun <T> parse(text: String, deserializer: DeserializationStrategy<T>): T =
        json.decodeFromString(deserializer, text)
}
