package com.usagelimits.feature.overview

import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SharedVectors
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the same hand-written identities and every rank permutation as the iOS suite. */
class DialIdentityVectorsTest {
    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val accounts: List<Account>,
        val ranks: List<List<String>>,
        val expected: List<Expected>,
    )

    @Serializable
    private data class Account(val id: String, val provider: String, val createdAt: Long, val maskedEmail: String?) {
        fun model() = account(id, requireNotNull(ProviderId.fromId(provider)), createdAt, maskedEmail)
    }

    @Serializable
    private data class Expected(val id: String, val kind: String, val email: String?, val number: Int?) {
        fun identity(): DialIdentity = when (kind) {
            "providerOnly" -> {
                require(email == null && number == null)
                DialIdentity.ProviderOnly
            }
            "email" -> {
                require(number == null)
                DialIdentity.Email(requireNotNull(email))
            }
            "number" -> {
                require(email == null)
                DialIdentity.Number(requireNotNull(number))
            }
            else -> error("unknown identity kind: $kind")
        }
    }

    private fun parse(text: String): List<Vector> =
        SharedVectors.parse(text, VectorFile.serializer()).vectors.also { require(it.isNotEmpty()) }

    @Test
    fun `every presentation rank keeps the shared identity attached to its account`() {
        val vectors = parse(SharedVectors.file("same-provider-identity.json").readText())
        assertTrue("the shared identity file retains its seven cases", vectors.size >= 7)
        for (vector in vectors) {
            val accounts = vector.accounts.associate { input ->
                val model = input.model()
                // Die Vektoren liefern bereits maskierte Adressen; der Modelleingang bleibt exakt derselbe.
                assertEquals(vector.name, input.maskedEmail, model.maskedEmail)
                input.id to model
            }
            val expected = vector.expected.associate { it.id to it.identity() }
            assertEquals(vector.name, accounts.keys, expected.keys)
            assertTrue(vector.name, vector.ranks.isNotEmpty())
            for (rank in vector.ranks) {
                assertEquals(vector.name, accounts.size, rank.size)
                assertEquals(vector.name, accounts.keys, rank.toSet())
                assertEquals("${vector.name}: $rank", expected, dialIdentities(rank.map(accounts::getValue)))
            }
        }
    }

    @Test
    fun `empty and blank addresses are absent while masked addresses compare exactly`() {
        val accounts = listOf(
            account("empty", createdAt = 1L, email = ""),
            account("blank", createdAt = 2L, email = " \t\n"),
            account("upper", createdAt = 3L, email = "a@example.test"),
            account("lower", createdAt = 4L, email = "a@EXAMPLE.test"),
        )
        assertNull(accounts[0].maskedEmail)
        assertNull(accounts[1].maskedEmail)
        assertEquals(
            mapOf(
                "empty" to DialIdentity.Number(1),
                "blank" to DialIdentity.Number(2),
                "upper" to DialIdentity.Email("a***@example.test"),
                "lower" to DialIdentity.Email("a***@EXAMPLE.test"),
            ),
            dialIdentities(accounts),
        )
    }

    @Test
    fun `masking preserves the first Unicode code point so distinct supplementary initials do not collide`() {
        val first = account("first", email = "\uD83D\uDE00one@example.test")
        val second = account("second", email = "\uD83D\uDE03two@example.test")
        assertEquals("\uD83D\uDE00***@example.test", first.maskedEmail)
        assertEquals("\uD83D\uDE03***@example.test", second.maskedEmail)
        assertEquals(
            mapOf(
                "first" to DialIdentity.Email("\uD83D\uDE00***@example.test"),
                "second" to DialIdentity.Email("\uD83D\uDE03***@example.test"),
            ),
            dialIdentities(listOf(first, second)),
        )
    }

    @Test
    fun `renamed vector keys and an empty vector list fail instead of skipping coverage`() {
        assertThrows(SerializationException::class.java) {
            parse("""{"description":"","schema":"","vectors":[],"cases":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"description":"","schema":"","vectors":[]}""")
        }
    }

    private companion object {
        fun account(
            id: String,
            provider: ProviderId = ProviderId.CLAUDE,
            createdAt: Long = 0L,
            email: String? = null,
        ) = ProviderAccount(
            localId = id, provider = provider, externalAccountId = "external-$id", email = email,
            displayName = null, plan = null, credentialReference = "synthetic-$id", createdAt = createdAt,
            lastSuccessfulSync = null,
        )
    }
}
