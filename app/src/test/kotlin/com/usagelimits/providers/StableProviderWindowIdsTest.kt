package com.usagelimits.providers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.AccountEntity
import com.usagelimits.core.database.UsageLimitsDatabase
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.database.UsageSnapshotEntity
import com.usagelimits.core.model.SharedVectors
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.providers.kimi.KimiUsageParser
import com.usagelimits.providers.zai.ZaiUsageParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StableProviderWindowIdsTest {
    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val provider: String,
        val payload: JsonObject,
        val expected: List<Window>,
    )

    @Serializable
    private data class Window(
        val id: String,
        val label: String,
        val category: String,
        val usedPercent: Double?,
        val periodSeconds: Long?,
        val resetAt: Long?,
        val exhausted: Boolean,
    ) {
        fun toWindow() = UsageWindow(
            id = id, label = label,
            category = when (category) {
                "fiveHour" -> WindowCategory.FIVE_HOUR
                "weekly" -> WindowCategory.WEEKLY
                "monthly" -> WindowCategory.MONTHLY
                "other" -> WindowCategory.OTHER
                else -> error("unknown vector category: $category")
            },
            usedPercent = usedPercent, periodSeconds = periodSeconds, resetAt = resetAt, exhausted = exhausted,
        )
    }

    @Test
    fun `both parsers preserve identities and readings through every shared payload`() {
        val file = SharedVectors.parse(
            SharedVectors.file("stable-provider-window-ids.json").readText(), VectorFile.serializer(),
        )
        assertTrue("the reorder, shortened, Unicode and duplicate cases must remain", file.vectors.size >= 16)
        assertEquals(setOf("kimi", "zai"), file.vectors.map { it.provider }.toSet())
        assertEquals("vector names must be unique", file.vectors.size, file.vectors.map { it.name }.toSet().size)
        for (vector in file.vectors) {
            val windows = when (vector.provider) {
                "kimi" -> KimiUsageParser.parse(vector.payload)
                "zai" -> ZaiUsageParser.parse(vector.payload)
                else -> error("unknown vector provider: ${vector.provider}")
            }
            assertEquals(vector.name, vector.expected.map(Window::toWindow), windows)
            assertEquals("${vector.name}: window IDs must be unique", windows.size, windows.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `old serialized provider windows keep their IDs and readings until a fresh read`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), UsageLimitsDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = UsageRepository(database.accountDao(), database.usageSnapshotDao())
            for ((provider, id) in listOf("kimi" to "kimi-limit-0", "zai" to "zai-tokens_limit-1")) {
                database.accountDao().upsert(AccountEntity(
                    localId = provider, provider = provider, externalAccountId = provider,
                    email = null, displayName = null, plan = null, credentialReference = provider,
                    createdAt = 1, lastSuccessfulSync = 1,
                ))
                database.usageSnapshotDao().upsert(UsageSnapshotEntity(
                    accountId = provider, fetchedAt = 1, status = "OK", errorMessage = null,
                    windowsJson = """[{"id":"$id","label":"A","category":"FIVE_HOUR","usedPercent":25.0,"periodSeconds":18000,"resetAt":1800000000000,"exhausted":false,"scope":"GLOBAL"}]""",
                    resetCreditsJson = "[]",
                ))
                val restored = requireNotNull(repository.snapshot(provider))
                assertEquals(listOf(UsageWindow(id, "A", WindowCategory.FIVE_HOUR, 25.0,
                    18_000, 1_800_000_000_000, false, scope = UsageScope.GLOBAL)), restored.windows)
            }
        } finally {
            database.close()
        }
    }
}
