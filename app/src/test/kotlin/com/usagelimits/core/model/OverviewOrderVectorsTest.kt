package com.usagelimits.core.model

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.feature.UsageUiState
import com.usagelimits.widget.WidgetDataBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Overview order, proven against the vector file the Swift suite reads too.
 *
 * Each vector runs through the callers a person sees: the Overview list, the All accounts
 * widget, a Provider widget (stored order) and a Custom widget (saved order). Expected lists were
 * written by hand from the rule; the loader is strict, as in `HeadlineVectorsTest`.
 */
class OverviewOrderVectorsTest {

    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val now: Long,
        val accounts: List<Account>,
        val custom: List<String>,
        val expected: Expected,
    )

    @Serializable
    private data class Expected(val automatic: List<String>, val stored: List<String>, val custom: List<String>)

    @Serializable
    private data class Account(val id: String, val createdAt: Long, val connection: Connection, val reading: Reading?) {
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
        require(vectors.isNotEmpty()) { "the overview order vector file holds no vectors" }
        return vectors
    }

    private val file = SharedVectors.file("overview-order.json")

    private fun List<AccountUsage>.ids() = map { it.account.localId }

    @Test
    fun `Overview shows every vector in the expected automatic order`() {
        for (vector in parse(file.readText())) {
            val accounts = vector.accounts.map(Account::toUsage)
            assertEquals(vector.name, vector.expected.automatic, UsageUiState(accounts).overviewAccountsAt(vector.now).ids())
        }
    }

    @Test
    fun `All accounts ranks like Overview, Provider keeps the stored order and Custom its saved one`() {
        for (vector in parse(file.readText())) {
            val accounts = vector.accounts.map(Account::toUsage)
            fun widget(scope: WidgetScope) = WidgetDataBuilder.build(accounts, vector.now, scope, null, ProviderId.CODEX.id,
                customAccountIds = vector.custom).accounts.map { it.accountId }
            assertEquals(vector.name, vector.expected.automatic, widget(WidgetScope.ALL_ACCOUNTS))
            assertEquals(vector.name, vector.expected.stored, widget(WidgetScope.PROVIDER))
            assertEquals(vector.name, vector.expected.custom, widget(WidgetScope.CUSTOM))
        }
    }

    @Test
    fun `the vector file covers every case the rule names`() {
        // A floor, not a count kept in step with the file: a truncated file cannot pass.
        assertTrue(parse(file.readText()).size >= 12)
    }

    @Test
    fun `a renamed key fails the suite instead of skipping the vector`() {
        val renamed = """
            {"description": "", "schema": "", "vectors": [{"name": "x", "now": 0, "custom": [],
              "accounts": [{"id": "a", "createdAt": 0, "connection": "connected", "reading": null, "addedAt": 0}],
              "expected": {"automatic": ["a"], "stored": ["a"], "custom": []}}]}
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
