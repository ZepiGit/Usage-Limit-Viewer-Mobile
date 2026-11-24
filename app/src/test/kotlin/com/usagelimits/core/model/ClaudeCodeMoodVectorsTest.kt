package com.usagelimits.core.model

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.sync.RATE_LIMITED_PAUSE_MESSAGE
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.OverviewFilter
import com.usagelimits.feature.overview.overviewAt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The Claude Code figure's steady mood, proven against the vector file the Swift suite reads too.
 *
 * Each case goes through what Overview runs: the account's snapshot, the Overview projection that
 * names its headline (ADR 0002), then the mood of that headline. Every case runs in Left and in
 * Used, because the person's display choice must never change the figure. The loader rejects
 * unknown keys and an empty list, so a renamed key or a truncated file cannot pass as green.
 */
class ClaudeCodeMoodVectorsTest {

    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val now: Long,
        val fetchedAt: Long?,
        val reading: Reading,
        val windows: List<Window>,
        val expected: Expected,
    )

    @Serializable
    private data class Window(
        val id: String,
        val scope: Scope,
        val remainingPercent: Double?,
        val exhausted: Boolean,
        val resetAt: Long?,
    ) {
        fun toUsageWindow() = UsageWindow(
            id = id, label = id, category = WindowCategory.OTHER,
            usedPercent = remainingPercent?.let { 100.0 - it }, periodSeconds = null,
            resetAt = resetAt, exhausted = exhausted, scope = scope.model,
        )
    }

    @Serializable
    private data class Expected(val headlineId: String?, val mood: Mood)

    @Serializable
    private enum class Scope(val model: UsageScope) {
        @SerialName("global") GLOBAL(UsageScope.GLOBAL),
        @SerialName("model") MODEL(UsageScope.MODEL),
        @SerialName("unknown") UNKNOWN(UsageScope.UNKNOWN),
    }

    @Serializable
    private enum class Mood(val model: ClaudeCodeMood) {
        @SerialName("asleep") ASLEEP(ClaudeCodeMood.ASLEEP),
        @SerialName("tired") TIRED(ClaudeCodeMood.TIRED),
        @SerialName("working") WORKING(ClaudeCodeMood.WORKING),
        @SerialName("idle") IDLE(ClaudeCodeMood.IDLE),
        @SerialName("dancing") DANCING(ClaudeCodeMood.DANCING),
    }

    /** The stored shape each fixture condition has in the app. */
    @Serializable
    private enum class Reading {
        @SerialName("current") CURRENT,
        @SerialName("stale") STALE,
        @SerialName("failed") FAILED,
        @SerialName("paused") PAUSED,
        @SerialName("expired") EXPIRED,
        @SerialName("unread") UNREAD,
    }

    private fun parse(text: String): List<Vector> {
        val vectors = SharedVectors.parse(text, VectorFile.serializer()).vectors
        require(vectors.isNotEmpty()) { "the mood vector file holds no vectors" }
        return vectors
    }

    private val file = SharedVectors.file("claude-code-mood.json")

    private fun usage(vector: Vector): AccountUsage {
        val account = ProviderAccount("claude", ProviderId.CLAUDE, "ext", null, null, "Pro", "ref", 0L, vector.fetchedAt)
        val windows = vector.windows.map { it.toUsageWindow() }
        val snapshot = when (vector.reading) {
            // No reading has ever been stored: the account exists, its snapshot does not.
            Reading.UNREAD -> if (vector.fetchedAt == null) null
                else UsageSnapshot("claude", vector.fetchedAt, SnapshotStatus.OK, windows)
            Reading.CURRENT, Reading.STALE ->
                UsageSnapshot("claude", vector.fetchedAt!!, SnapshotStatus.OK, windows)
            Reading.FAILED -> UsageSnapshot("claude", vector.fetchedAt ?: vector.now, SnapshotStatus.FAILED, windows,
                errorMessage = "No network connection")
            Reading.PAUSED -> UsageSnapshot("claude", vector.fetchedAt!!, SnapshotStatus.FAILED, windows,
                errorMessage = RATE_LIMITED_PAUSE_MESSAGE)
            Reading.EXPIRED -> UsageSnapshot("claude", vector.fetchedAt!!, SnapshotStatus.FAILED, windows,
                connectionStatus = ConnectionStatus.RECONNECT_REQUIRED)
        }
        return AccountUsage(account, snapshot)
    }

    @Test
    fun `every shared case gives its headline and mood in Left and in Used`() {
        val vectors = parse(file.readText())
        for (vector in vectors) {
            val usage = usage(vector)
            for (mode in PercentDisplay.entries) {
                val state = UsageUiState(accounts = listOf(usage), settings = AppSettings(percentDisplay = mode))
                val entry = state.accounts.overviewAt(OverviewFilter.ALL, vector.now, ZoneId.of("Etc/UTC"), state.staleAfterMs)
                    .entries.single()
                val label = "${vector.name} ($mode)"
                assertEquals(label, vector.expected.headlineId, entry.headlineWindowId)
                assertEquals(label, vector.expected.mood.model, entry.usage.snapshot.claudeCodeMoodAt(vector.now))
            }
        }
    }

    @Test
    fun `the vector file covers every case the rule names`() {
        // A floor, not a count to keep in step: a file truncated by a bad merge cannot pass.
        assertTrue(parse(file.readText()).size >= 30)
    }

    @Test
    fun `a renamed key fails the suite instead of skipping the vector`() {
        val renamed = """
            {"description": "", "schema": "", "vectors": [{"name": "x", "now": 0, "fetchedAt": 0, "reading": "current",
              "windows": [{"id": "a", "scope": "global", "remaining": 5, "exhausted": false, "resetAt": null}],
              "expected": {"headlineId": "a", "mood": "tired"}}]}
        """.trimIndent()

        assertThrows(SerializationException::class.java) { parse(renamed) }
    }

    @Test
    fun `an empty vector list fails the suite`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"description": "", "schema": "", "vectors": []}""")
        }
    }
}
