package com.usagelimits.core.time

import com.usagelimits.core.model.SharedVectors
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class ResetStampTierTest {
    @Serializable private data class VectorFile(val description: String, val vectors: List<Vector>)
    @Serializable private data class Vector(
        val name: String, val now: String, val instant: String, val zone: String, val tier: String,
    )

    @Test
    fun `shared local date tiers keep native English and German forms`() {
        val vectors = SharedVectors.parse(SharedVectors.file("reset-stamp-tier.json").readText(),
            VectorFile.serializer()).vectors
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val now = Instant.parse(vector.now).toEpochMilli()
            val instant = Instant.parse(vector.instant)
            val zone = ZoneId.of(vector.zone)
            for (locale in listOf(Locale.US, Locale.GERMANY)) {
                val reset = instant.atZone(zone)
                val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(reset)
                val expected = when (vector.tier) {
                    "time" -> time
                    "weekday" -> DateTimeFormatter.ofPattern("EEE", locale).format(reset) + " " + time
                    "date" -> DateTimeFormatter.ofPattern(EnglishCountdownTexts.resetDatePattern(), locale).format(reset)
                    else -> error("unknown tier ${vector.tier}")
                }
                assertEquals("${vector.name}, $locale", expected,
                    Countdown.absoluteResetStamp(instant.toEpochMilli(), now, zone, locale))
            }
        }
    }
}
