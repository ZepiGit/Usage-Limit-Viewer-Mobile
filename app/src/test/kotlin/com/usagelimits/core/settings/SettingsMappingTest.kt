package com.usagelimits.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stored preference mapping preserves choices made by earlier builds.
 * Spell out storage key names independently of Keys so tests detect accidental renames
 * that would hide persisted values and silently restore defaults.
 */
class SettingsMappingTest {

    private fun settingsFrom(vararg pairs: androidx.datastore.preferences.core.Preferences.Pair<*>) =
        mutablePreferencesOf(*pairs).toSettings()

    @Test
    fun `nothing stored means the documented defaults`() {
        val settings = settingsFrom()

        assertEquals(AppSettings(), settings)
        assertTrue(settings.showSubscriptionTier)
        assertFalse(settings.showRenewalTime)
        assertFalse(settings.accountsManuallyOrdered)
        assertTrue(settings.mutedAccountIds.isEmpty())
    }

    @Test
    fun `a pre-split opt-out silences BOTH low-quota tiers, not just one`() {
        // The legacy low-quota opt-out must suppress both tiers after upgrade.
        val settings = settingsFrom(booleanPreferencesKey("notify_low_usage") to false)

        assertFalse("the 20% tier must inherit the old opt-out", settings.notifyBelow20Percent)
        assertFalse("and so must the 10% tier", settings.notifyBelow10Percent)
    }

    @Test
    fun `an explicit new-style choice wins over the legacy switch`() {
        val settings = settingsFrom(
            booleanPreferencesKey("notify_low_usage") to false,
            booleanPreferencesKey("notify_below_20_percent") to true,
        )

        assertTrue(settings.notifyBelow20Percent)
        assertFalse(settings.notifyBelow10Percent)
    }

    @Test
    fun `muted accounts are read back, so a silenced account stays silenced`() {
        val settings = settingsFrom(
            stringSetPreferencesKey("muted_account_ids") to setOf("acct-1", "acct-2"),
        )

        assertEquals(setOf("acct-1", "acct-2"), settings.mutedAccountIds)
    }

    @Test
    fun `the display and ordering preferences are read back`() {
        val settings = settingsFrom(
            booleanPreferencesKey("show_subscription_tier") to false,
            booleanPreferencesKey("show_renewal_time") to true,
            booleanPreferencesKey("accounts_manually_ordered") to true,
        )

        assertFalse(settings.showSubscriptionTier)
        assertTrue(settings.showRenewalTime)
        assertTrue(settings.accountsManuallyOrdered)
    }

    @Test
    fun `a stored interval below the platform floor is raised on the way out`() {
        val settings = settingsFrom(intPreferencesKey("sync_interval_minutes") to 1)

        assertEquals(
            "an interval the platform ignores must not survive a read",
            AppSettings.MIN_SYNC_INTERVAL_MINUTES,
            settings.syncIntervalMinutes,
        )
    }

    @Test
    fun `diagnostics timestamps live beside the settings without changing them`() {
        // They share the file, so a sync writing its timestamp must not alter what
        // `toSettings` reads — the widgets observe that mapping.
        val prefs = mutablePreferencesOf(
            longPreferencesKey("last_sync_attempt_at") to 10L,
            longPreferencesKey("last_sync_finished_at") to 20L,
            longPreferencesKey("last_widget_refresh_at") to 30L,
        )
        assertEquals(SyncDiagnostics(10L, 20L, 30L), prefs.toDiagnostics())
        assertEquals(AppSettings(), prefs.toSettings())
        assertEquals(SyncDiagnostics(), mutablePreferencesOf().toDiagnostics())
    }
}
