package com.usagelimits.core.model

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.usagelimits.core.settings.toSettings
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncIntervalVectorsTest {
    @Serializable
    private data class VectorFile(val cases: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val requestedMinutes: Int,
        val normalizedMinutes: Int,
        val staleAfterSeconds: Double,
    )

    @Test
    fun `shared intervals normalize and determine staleness`() {
        val vectors = SharedVectors.parse(
            SharedVectors.file("sync-interval.json").readText(),
            VectorFile.serializer(),
        ).cases
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val settings = mutablePreferencesOf(
                intPreferencesKey("sync_interval_minutes") to vector.requestedMinutes,
            ).toSettings()
            assertEquals(vector.name, vector.normalizedMinutes, settings.syncIntervalMinutes)
            assertEquals(
                vector.name,
                (vector.staleAfterSeconds * 1000).toLong(),
                Severity.staleAfterMs(settings.syncIntervalMinutes),
            )
        }
    }

    @Test
    fun `five minute interval becomes stale at forty five minutes`() {
        val fetchedAt = 1_757_000_000_000L
        val snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow("w", "5h limit", WindowCategory.FIVE_HOUR, 10.0, 18_000,
                    resetAt = null, exhausted = false),
            ),
        )
        val threshold = Severity.staleAfterMs(5)
        assertEquals(Severity.HEALTHY, snapshot.severityAt(fetchedAt + 2_699_999L, threshold))
        assertEquals(Severity.STALE, snapshot.severityAt(fetchedAt + 2_700_000L, threshold))
        assertEquals(false, snapshot.isStaleAt(fetchedAt + 2_699_999L, threshold))
        assertEquals(true, snapshot.isStaleAt(fetchedAt + 2_700_000L, threshold))
    }
}
