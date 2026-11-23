package com.usagelimits.core.model

import com.usagelimits.core.sync.RATE_LIMITED_PAUSE_MESSAGE
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverviewCreditSummaryVectorsTest {
    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val now: Long,
        val staleAfterMs: Long,
        val accounts: List<Account>,
        val expected: Expected,
    )

    @Serializable
    private data class Account(val id: String, val customThreshold: String?, val snapshot: Snapshot?)

    @Serializable
    private data class Snapshot(val fetchedAt: Long, val status: String, val balances: List<Balance>)

    @Serializable
    private data class Balance(val currency: String, val amount: String, val total: String?, val unlimited: Boolean)

    @Serializable
    private data class Expected(val count: Int, val winner: Winner?)

    @Serializable
    private data class Winner(val accountId: String, val balanceIndex: Int, val currency: String, val amount: String)

    private val decimalPattern = Regex("-?[0-9]+(\\.[0-9]+)?")
    private val currencyPattern = Regex("[A-Z]{3}")

    private fun decimal(text: String): BigDecimal {
        require(decimalPattern.matches(text)) { "Invalid decimal: $text" }
        return BigDecimal(text)
    }

    private fun vectors(): List<Vector> {
        val vectors = SharedVectors.parse(
            SharedVectors.file("overview-credits-summary.json").readText(),
            VectorFile.serializer(),
        ).vectors
        require(vectors.isNotEmpty())
        require(vectors.all { it.name.isNotBlank() } && vectors.map { it.name }.distinct().size == vectors.size)
        vectors.forEach { vector ->
            require(vector.staleAfterMs > 0)
            require(vector.accounts.all { it.id.isNotBlank() } &&
                vector.accounts.map { it.id }.distinct().size == vector.accounts.size)
            vector.accounts.forEach { account ->
                account.customThreshold?.let { require(decimal(it).signum() > 0) }
                account.snapshot?.let { snapshot ->
                    require(snapshot.status in setOf("ok", "paused", "failed"))
                    snapshot.balances.forEach { balance ->
                        require(currencyPattern.matches(balance.currency))
                        decimal(balance.amount)
                        balance.total?.let(::decimal)
                    }
                }
            }
            vector.expected.winner?.let { winner ->
                require(winner.accountId.isNotBlank() && winner.balanceIndex >= 0)
                require(currencyPattern.matches(winner.currency))
                decimal(winner.amount)
            }
        }
        return vectors
    }

    private fun check(vector: Vector) {
        val entries = vector.accounts.map { input ->
            val account = CreditAccount(
                id = input.id,
                provider = CreditProviderId.DEEPSEEK,
                label = input.id,
                credentialReference = input.id,
                attributes = input.customThreshold?.let { mapOf(CreditAccount.LOW_THRESHOLD_KEY to it) } ?: emptyMap(),
            )
            val snapshot = input.snapshot?.let { raw ->
                CreditSnapshot(
                    accountId = input.id,
                    balances = raw.balances.map { balance ->
                        CreditBalance(balance.currency, decimal(balance.amount), balance.total?.let(::decimal), unlimited = balance.unlimited)
                    },
                    fetchedAt = raw.fetchedAt,
                    status = if (raw.status == "ok") CreditStatus.OK else CreditStatus.FAILED,
                    error = if (raw.status == "paused") RATE_LIMITED_PAUSE_MESSAGE else if (raw.status == "failed") "failed" else null,
                )
            }
            CreditEntry(account, snapshot)
        }
        val result = entries.overviewCreditSummary(vector.now, vector.staleAfterMs)
        assertEquals(vector.name, vector.expected.count, result.count)
        val expected = vector.expected.winner
        if (expected == null) {
            assertNull(vector.name, result.winner)
        } else {
            val winner = requireNotNull(result.winner) { "${vector.name}: expected a winner" }
            assertEquals(vector.name, expected.accountId, winner.account.id)
            assertEquals(vector.name, expected.balanceIndex, winner.balanceIndex)
            assertEquals(vector.name, expected.currency, winner.balance.currency)
            assertEquals(vector.name, decimal(expected.amount), winner.balance.amount)
        }
    }

    @Test fun `every shared vector selects the expected summary`() {
        vectors().forEach(::check)
    }
}
