package com.usagelimits.core.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ResetConfirmationTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: UsageLimitsDatabase
    private fun repository(accounts: AccountDao = db.accountDao(), snapshots: UsageSnapshotDao = db.usageSnapshotDao()) =
        UsageRepository(accounts, snapshots, transactions = RoomTransactionRunner(db))

    @Before fun open() { context.deleteDatabase("usage_limits.db"); db = UsageLimitsDatabase.build(context) }
    @After fun close() { db.close(); context.deleteDatabase("usage_limits.db") }

    @Serializable private data class Vectors(val description: String, val schema: String, val vectors: List<Vector>)
    @Serializable private data class Vector(val name: String, val sameAccount: Boolean, val outcome: String,
        val old: Reading?, val new: Reading, val expected: Expected)
    @Serializable private data class Reading(val fetchedAt: Long, val windows: List<Window>) {
        fun snapshot(id: String, status: SnapshotStatus = SnapshotStatus.OK) = UsageSnapshot(id, fetchedAt, status,
            windows.map { UsageWindow(it.id, it.id, WindowCategory.OTHER, it.usedPercent, null, it.resetAt, false, measured = it.measured) })
    }
    @Serializable private data class Window(val id: String, val resetAt: Long?, val measured: Boolean, val usedPercent: Double?)
    @Serializable private data class Expected(val confirmed: Boolean, val windowIds: List<String>)
    private val file get() = SharedVectors.file("reset-confirmation.json")
    private fun parse(text: String): List<Vector> {
        val data = SharedVectors.parse(text, Vectors.serializer())
        require(data.schema == "reset-confirmation-v1" && data.description.isNotBlank() && data.vectors.isNotEmpty())
        require(data.vectors.map { it.name }.distinct().size == data.vectors.size)
        for (vector in data.vectors) {
            require(vector.name.isNotBlank() && vector.outcome in setOf("ok", "partial", "failed", "skipped"))
            for (reading in listOfNotNull(vector.old, vector.new)) {
                require(reading.windows.isNotEmpty() && reading.windows.all { it.id.isNotBlank() })
                require(reading.windows.map { it.id }.distinct().size == reading.windows.size)
            }
            require(vector.expected.confirmed == vector.expected.windowIds.isNotEmpty())
            require(vector.expected.windowIds.distinct() == vector.expected.windowIds)
        }
        return data.vectors
    }

    private suspend fun account(name: String = "claude", provider: ProviderId = ProviderId.CLAUDE) =
        repository().upsertFromLogin(provider, name, null, name, "Pro", emptyMap())
    private fun reading(id: String, fetch: Long, reset: Long, windows: Int = 1) = UsageSnapshot(id, fetch, SnapshotStatus.OK,
        List(windows) { UsageWindow("window-$it", "Window", WindowCategory.FIVE_HOUR, 80.0, 18_000, reset, false) })
    private suspend fun confirm(id: String, windows: Int = 1) {
        repository().saveSnapshot(reading(id, 1000, 2000, windows))
        repository().saveSnapshot(reading(id, 2500, 4000, windows))
    }

    @Test fun `shared dated evidence drives production projection commit and one claim`() = runBlocking {
        for (vector in parse(file.readText())) {
            val repo = repository()
            val oldAccount = account(vector.name)
            val newAccount = if (vector.sameAccount) oldAccount else {
                vector.old?.let { repo.saveSnapshot(it.snapshot(oldAccount.localId)) }
                repo.deleteAccount(oldAccount.localId)
                account(vector.name)
            }
            if (vector.sameAccount) vector.old?.let { repo.saveSnapshot(it.snapshot(oldAccount.localId)) }
            val status = when (vector.outcome) { "partial" -> SnapshotStatus.PARTIAL; "failed" -> SnapshotStatus.FAILED; else -> SnapshotStatus.OK }
            val fresh = vector.new.snapshot(newAccount.localId, status)
            val ids = if (vector.outcome == "skipped") emptyList() else fresh.confirmedResetWindowIds(vector.old?.snapshot(oldAccount.localId))
            assertEquals("${vector.name}: confirmed window identities", vector.expected.windowIds, ids)
            if (vector.outcome != "skipped") repo.saveSnapshot(fresh)
            assertEquals("${vector.name}: committed account mark", if (vector.expected.confirmed) setOf(newAccount.localId) else emptySet<String>(), repo.claimPendingResetConfirmations())
            assertTrue("${vector.name}: consumed once", repo.claimPendingResetConfirmations().isEmpty())
            repo.deleteAccount(newAccount.localId)
        }
    }

    @Test fun `vector reader rejects unknown fields empty data and unstable identities`() {
        assertThrows(SerializationException::class.java) { parse(file.readText().replace("\"measured\"", "\"fresh\"")) }
        assertThrows(IllegalArgumentException::class.java) { parse("""{"description":"test","schema":"reset-confirmation-v1","vectors":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { parse(file.readText().replace("\"id\":\"five-hour\"", "\"id\":\"\"")) }
    }

    @Test fun `coalesced marks survive failure reconnect and reopen but never removal`() = runBlocking {
        val first = account()
        confirm(first.localId, windows = 2)
        repository().saveSnapshot(reading(first.localId, 4500, 6000, windows = 2))
        repository().saveFailure(first.localId, "Offline", 4700)
        assertEquals(first.localId, account().localId)
        db.close(); db = UsageLimitsDatabase.build(context)
        assertEquals("first confirmation remains coalesced", 2500L, db.accountDao().getById(first.localId)!!.pendingResetConfirmationAt)
        assertEquals(setOf(first.localId), repository().claimPendingResetConfirmations())
        db.close(); db = UsageLimitsDatabase.build(context)
        assertTrue("clear survives process restart", repository().claimPendingResetConfirmations().isEmpty())
        repository().deleteAccount(first.localId)
        val replacement = account()
        assertNotEquals(first.localId, replacement.localId)
        repository().saveSnapshot(reading(replacement.localId, 6500, 8000))
        assertTrue("re-added identity cannot inherit a reset", repository().claimPendingResetConfirmations().isEmpty())
        val codex = account("codex", ProviderId.CODEX)
        confirm(codex.localId)
        assertTrue("only Claude owns a cheer", repository().claimPendingResetConfirmations().isEmpty())
    }

    @Test fun `snapshot failure rolls back the confirmation in the same Room transaction`() = runBlocking {
        val account = account()
        repository().saveSnapshot(reading(account.localId, 1000, 2000))
        val failing = object : UsageSnapshotDao by db.usageSnapshotDao() {
            override suspend fun upsert(snapshot: UsageSnapshotEntity) { error("controlled snapshot write failure") }
        }
        try { repository(snapshots = failing).saveSnapshot(reading(account.localId, 2500, 4000)); fail("write should fail") }
        catch (expected: IllegalStateException) { assertEquals("controlled snapshot write failure", expected.message) }
        assertTrue("no cheer for a reading that did not commit", repository().claimPendingResetConfirmations().isEmpty())
        assertEquals(1000L, repository().snapshot(account.localId)!!.fetchedAt)
    }

    @Test fun `concurrent Room claims cannot both consume the same account`() = runBlocking {
        val account = account()
        confirm(account.localId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val gated = object : AccountDao by db.accountDao() {
            override suspend fun getAll(): List<AccountEntity> {
                val rows = db.accountDao().getAll()
                if (++reads == 1) { entered.complete(Unit); release.await() }
                return rows
            }
        }
        val repo = repository(accounts = gated)
        val first = async { repo.claimPendingResetConfirmations() }
        entered.await()
        val second = async { repo.claimPendingResetConfirmations() }
        try {
            release.complete(Unit)
            val results = listOf(first.await(), second.await())
            assertEquals("only one concurrent opening consumes the mark", listOf(account.localId), results.flatMap { it })
        } finally { release.complete(Unit); first.cancel(); second.cancel(); first.join(); second.join() }
    }

    @Test fun `version 14 keeps legacy order readings widget settings and begins unmarked`() = runBlocking {
        db.close()
        context.deleteDatabase("usage_limits.db")
        val path = context.getDatabasePath("usage_limits.db").also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val schema = JSONObject(File("schemas/com.usagelimits.core.database.UsageLimitsDatabase/14.json").readText()).getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) sqlite.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO accounts VALUES('later','claude','ext-later','kept@example.com','Kept','Pro','ref-later',1,1000,'{}',7)")
            sqlite.execSQL("INSERT INTO accounts VALUES('first','codex','ext-first',NULL,NULL,NULL,'ref-first',2,NULL,'{}',3)")
            sqlite.execSQL("INSERT INTO usage_snapshots(accountId,fetchedAt,status,windowsJson,resetCreditsJson,lastAttemptAt,backgroundNotBefore) VALUES('later',1000,'OK','[]','[]',1100,9000)")
            sqlite.execSQL("INSERT INTO widget_configs(appWidgetId,scope,accountId,updatedAt,backgroundOpacity,textTone) VALUES(7,'ACCOUNT','later',3,45,'LIGHT')")
            sqlite.version = 14
        }
        db = UsageLimitsDatabase.build(context)
        val accounts = db.accountDao().getAll()
        assertEquals(listOf("first", "later"), accounts.map { it.localId })
        assertEquals(listOf(3, 7), accounts.map { it.sortOrder })
        assertEquals("kept@example.com", accounts.last().email)
        assertTrue(accounts.all { it.pendingResetConfirmationAt == null })
        assertEquals(1100L, repository().snapshot("later")!!.lastAttemptAt)
        assertEquals(9000L, repository().backgroundNotBefore("later"))
        assertEquals(45, db.widgetConfigDao().get(7)!!.backgroundOpacity)
        assertEquals("LIGHT", db.widgetConfigDao().get(7)!!.textTone)
        assertTrue(repository().claimPendingResetConfirmations().isEmpty())
    }
}
