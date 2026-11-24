package com.usagelimits.feature.overview

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SharedVectors
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.RATE_LIMITED_PAUSE_MESSAGE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Overview's filters and header, proven against the vector file the Swift suite wrote and reads.
 *
 * The file came from the iOS ticket and is read unchanged. Each vector runs through the projection
 * the screen draws from, once per filter, at the vector's instant and IANA zone, so the DST cases
 * exercise the real day boundary. The loader is strict, as in `OverviewOrderVectorsTest`.
 */
class OverviewFiltersHeaderVectorsTest {

    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val zone: String,
        val zoneRule: String?,
        val now: Long,
        val staleAfterMs: Long,
        val accounts: List<Account>,
        val expected: Expected,
    )

    @Serializable
    private data class Expected(val visible: Visible, val attentionCount: Int, val header: Header)

    @Serializable
    private data class Visible(val all: List<String>, val needsAttention: List<String>, val resetsToday: List<String>)

    @Serializable
    private data class Header(val kind: Kind, val resetAt: Long?)

    @Serializable
    private enum class Kind(val model: OverviewHeaderKind) {
        @SerialName("attention") ATTENTION(OverviewHeaderKind.ATTENTION),
        @SerialName("room") ROOM(OverviewHeaderKind.ROOM),
        @SerialName("resetOnly") RESET_ONLY(OverviewHeaderKind.RESET_ONLY),
        @SerialName("none") NONE(OverviewHeaderKind.NONE),
    }

    @Serializable
    private data class Account(
        val id: String,
        val createdAt: Long,
        val connection: Connection,
        val pauseUntil: Long?,
        val reading: Reading?,
    ) {
        fun toUsage() = AccountUsage(
            ProviderAccount(
                localId = id, provider = ProviderId.CODEX, externalAccountId = "ext-$id", email = null,
                displayName = id, plan = null, credentialReference = "codex_$id", createdAt = createdAt,
                lastSuccessfulSync = reading?.fetchedAt,
            ),
            reading?.let {
                UsageSnapshot(
                    accountId = id, fetchedAt = it.fetchedAt, status = it.status.model,
                    windows = it.windows.map(Window::toUsageWindow), connectionStatus = connection.model,
                    // The app stores a rate-limit pause as this message beside a usable reading.
                    errorMessage = if (pauseUntil != null) RATE_LIMITED_PAUSE_MESSAGE else null,
                )
            },
        )
    }

    @Serializable
    private data class Reading(val fetchedAt: Long, val status: Status, val windows: List<Window>)

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
    private enum class Connection(val model: ConnectionStatus) {
        @SerialName("connected") CONNECTED(ConnectionStatus.CONNECTED),
        @SerialName("reconnectRequired") RECONNECT_REQUIRED(ConnectionStatus.RECONNECT_REQUIRED),
        @SerialName("unknown") UNKNOWN(ConnectionStatus.UNKNOWN),
    }

    @Serializable
    private enum class Status(val model: SnapshotStatus) {
        @SerialName("ok") OK(SnapshotStatus.OK),
        @SerialName("partial") PARTIAL(SnapshotStatus.PARTIAL),
        @SerialName("failed") FAILED(SnapshotStatus.FAILED),
    }

    @Serializable
    private enum class Scope(val model: UsageScope) {
        @SerialName("global") GLOBAL(UsageScope.GLOBAL),
        @SerialName("model") MODEL(UsageScope.MODEL),
        @SerialName("unknown") UNKNOWN(UsageScope.UNKNOWN),
    }

    private fun parse(text: String): List<Vector> {
        val vectors = SharedVectors.parse(text, VectorFile.serializer()).vectors
        require(vectors.isNotEmpty()) { "the overview filters and header vector file holds no vectors" }
        return vectors
    }

    private val file = SharedVectors.file("overview-filters-header.json")

    private fun Vector.project(filter: OverviewFilter) =
        accounts.map(Account::toUsage).overviewAt(filter, now, ZoneId.of(zone), staleAfterMs)

    @Test
    fun `every filter shows the expected accounts in rank order`() {
        for (vector in parse(file.readText())) {
            fun ids(filter: OverviewFilter) = vector.project(filter).entries.map { it.usage.account.localId }
            assertEquals("${vector.name}: All", vector.expected.visible.all, ids(OverviewFilter.ALL))
            assertEquals("${vector.name}: Needs attention", vector.expected.visible.needsAttention, ids(OverviewFilter.NEEDS_ATTENTION))
            assertEquals("${vector.name}: Resets today", vector.expected.visible.resetsToday, ids(OverviewFilter.RESETS_TODAY))
        }
    }

    @Test
    fun `the header counts and claims the same under every filter`() {
        for (vector in parse(file.readText())) {
            for (filter in OverviewFilter.entries) {
                val projection = vector.project(filter)
                assertEquals("${vector.name} ($filter): attention count", vector.expected.attentionCount, projection.attentionCount)
                assertEquals("${vector.name} ($filter): header",
                    OverviewHeader(vector.expected.header.kind.model, vector.expected.header.resetAt), projection.header)
            }
        }
    }

    @Test
    fun `the vector file covers every case the rule names`() {
        val vectors = parse(file.readText())
        // A floor, not a count kept in step with the file: a truncated file cannot pass.
        assertTrue(vectors.size >= 17)
        assertTrue("the DST cases are there", vectors.count { it.zoneRule != null } >= 3)
    }

    @Test
    fun `a renamed key fails the suite instead of skipping the vector`() {
        val renamed = """
            {"description": "", "schema": "", "vectors": [{"name": "x", "zone": "Etc/UTC", "zoneRule": null,
              "now": 0, "staleAfterMs": 1, "accounts": [{"id": "a", "createdAt": 0, "connection": "connected",
              "pausedUntil": null, "reading": null}],
              "expected": {"visible": {"all": ["a"], "needsAttention": [], "resetsToday": []},
                "attentionCount": 0, "header": {"kind": "none", "resetAt": null}}}]}
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
