package com.usagelimits.core.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Version 10 to 11 adds the Credit Viewer's two tables and touches nothing else.
 *
 * Builds the database exactly as version 10 shipped it (from the exported schema JSON, as
 * DatabaseUpgradeTest does), puts a usage account, snapshot and widget in it, opens it with the
 * current app, and checks those rows survived and the new tables exist, work, and cascade. Room
 * validates the migrated tables against the entities on open, so a migration whose SQL drifts
 * from the entity fails here rather than on a user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class CreditMigrationTest {

    private val schemaDir = File("schemas/com.usagelimits.core.database.UsageLimitsDatabase")

    private fun createVersion10(db: SQLiteDatabase) {
        val schema = JSONObject(File(schemaDir, "10.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val table = entities.getJSONObject(i)
            val name = table.getString("tableName")
            db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = table.getJSONArray("indices")
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = 10
    }

    @Test
    fun `version 10 rows survive and the credit tables are created`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("usage_limits.db")
        val file = context.getDatabasePath("usage_limits.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createVersion10(db)
            db.execSQL(
                "INSERT INTO accounts(localId, provider, externalAccountId, email, credentialReference, createdAt, attributesJson, sortOrder) " +
                    "VALUES('kept', 'codex', 'ext', 'kept@example.com', 'ref', 1, '{}', 0)",
            )
            db.execSQL(
                "INSERT INTO usage_snapshots(accountId, fetchedAt, status, windowsJson, resetCreditsJson, lastAttemptAt) " +
                    "VALUES('kept', 2, 'OK', '[]', '[]', 5)",
            )
            db.execSQL(
                "INSERT INTO widget_configs(appWidgetId, scope, accountId, provider, updatedAt) VALUES(7, 'ACCOUNT', 'kept', NULL, 3)",
            )
        }

        val upgraded = UsageLimitsDatabase.build(context)
        try {
            val sqlite = upgraded.openHelper.readableDatabase
            assertEquals(11, sqlite.version)

            // Existing rows, untouched.
            assertEquals("kept", upgraded.accountDao().getAll().single().localId)
            assertEquals(5L, upgraded.usageSnapshotDao().getForAccount("kept")!!.lastAttemptAt)
            assertEquals("kept", upgraded.widgetConfigDao().get(7)!!.accountId)

            // The new tables exist and start empty.
            val tables = mutableListOf<String>()
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use {
                while (it.moveToNext()) tables += it.getString(0)
            }
            assertTrue(tables.toString(), "credit_accounts" in tables && "credit_snapshots" in tables)
            assertTrue(upgraded.creditDao().getAccounts().isEmpty())

            // And they work end to end: write through the repository, read back, cascade on delete.
            val repository = CreditRepository(upgraded.creditDao())
            repository.upsert(
                CreditAccount("credit-fal-1", CreditProviderId.FAL, "Fal", "credit:credit-fal-1", createdAt = 9),
                CreditSnapshot("credit-fal-1", listOf(CreditBalance("USD", BigDecimal("24.5"))), fetchedAt = 10),
            )
            assertEquals("24.5", repository.snapshot("credit-fal-1")!!.balances.single().amount.toPlainString())
            repository.remove("credit-fal-1")
            assertTrue(upgraded.creditDao().getSnapshots().isEmpty())
            assertFalse(upgraded.creditDao().getAccounts().any())
        } finally {
            upgraded.close()
            context.deleteDatabase("usage_limits.db")
        }
    }

    @Test
    fun `the exported schema carries the credit tables`() {
        val schema = JSONObject(File(schemaDir, "11.json").readText()).getJSONObject("database")
        assertEquals(11, schema.getInt("version"))
        val names = (0 until schema.getJSONArray("entities").length())
            .map { schema.getJSONArray("entities").getJSONObject(it).getString("tableName") }
        assertTrue("credit_accounts" in names && "credit_snapshots" in names)
    }
}
