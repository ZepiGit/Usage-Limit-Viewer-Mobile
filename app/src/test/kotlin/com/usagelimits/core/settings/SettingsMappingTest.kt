package com.usagelimits.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.usagelimits.core.model.PercentDisplay
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
        assertEquals(30, settings.syncIntervalMinutes)
        assertTrue(settings.showSubscriptionTier)
        assertFalse(settings.showRenewalTime)
        assertFalse(settings.accountsManuallyOrdered)
        assertTrue(settings.mutedAccountIds.isEmpty())
    }

    @Test
    fun `the notification permission history survives settings reloads`() {
        assertTrue(settingsFrom(booleanPreferencesKey("notification_permission_asked") to true).notificationPermissionHandled)
        assertFalse(settingsFrom().notificationPermissionHandled)
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
    fun `five minutes survives reload and older interval choices stay unchanged`() {
        for (minutes in listOf(5, 15, 30, 60, 180)) {
            assertEquals(minutes, settingsFrom(intPreferencesKey("sync_interval_minutes") to minutes).syncIntervalMinutes)
        }
    }

    @Test
    fun `a stored interval below the supported minimum is raised on the way out`() {
        val settings = settingsFrom(intPreferencesKey("sync_interval_minutes") to 1)

        assertEquals(
            "a stored interval below five minutes must normalize on read",
            5,
            settings.syncIntervalMinutes,
        )
    }

    @Test
    fun `the percent display preference reads back, and unknown names fall back to LEFT`() {
        assertEquals(
            PercentDisplay.USED,
            settingsFrom(stringPreferencesKey("percent_display") to "USED").percentDisplay,
        )
        assertEquals(
            "an unknown stored name is the historical default, not a crash",
            PercentDisplay.LEFT,
            settingsFrom(stringPreferencesKey("percent_display") to "sideways").percentDisplay,
        )
        assertEquals(PercentDisplay.LEFT, settingsFrom().percentDisplay)
    }

    @Test
    fun `theme reads back stable names without resetting other choices`() {
        val key = stringPreferencesKey("appearance_theme")
        for ((name, theme) in mapOf(
            "WARM_DARK" to AppTheme.WARM_DARK,
            "PURE_BLACK" to AppTheme.PURE_BLACK,
            "COOL_GRAPHITE" to AppTheme.COOL_GRAPHITE,
            "LIGHT_PAPER" to AppTheme.LIGHT_PAPER,
            "future_theme" to AppTheme.PURE_BLACK,
        )) {
            val settings = settingsFrom(key to name, stringPreferencesKey("percent_display") to "USED")
            assertEquals(name, theme, settings.appTheme)
            assertEquals(PercentDisplay.USED, settings.percentDisplay)
        }
        assertEquals(AppTheme.PURE_BLACK, settingsFrom().appTheme)
    }

    @Test
    fun `saved font choices fall back independently of one another and the theme`() {
        val text = stringPreferencesKey("appearance_text_font")
        val figures = stringPreferencesKey("appearance_figure_font")
        val theme = stringPreferencesKey("appearance_theme")
        val settings = settingsFrom(text to "FIGTREE", figures to "IBM_PLEX_MONO", theme to "LIGHT_PAPER")
        assertEquals(TextFont.FIGTREE, settings.textFont)
        assertEquals(FigureFont.IBM_PLEX_MONO, settings.figureFont)
        assertEquals(AppTheme.LIGHT_PAPER, settings.appTheme)

        val unknownText = settingsFrom(text to "future_text", figures to "JETBRAINS_MONO")
        assertEquals(TextFont.ROBOTO, unknownText.textFont)
        assertEquals(FigureFont.JETBRAINS_MONO, unknownText.figureFont)

        val unknownFigures = settingsFrom(text to "IBM_PLEX_SANS", figures to "future_figures")
        assertEquals(TextFont.IBM_PLEX_SANS, unknownFigures.textFont)
        assertEquals(FigureFont.ROBOTO_MONO, unknownFigures.figureFont)
        assertEquals(TextFont.ROBOTO_FLEX, settingsFrom(text to "ROBOTO_FLEX").textFont)
        assertEquals(TextFont.ROBOTO, settingsFrom().textFont)
        assertEquals(FigureFont.ROBOTO_MONO, settingsFrom().figureFont)
    }

    @Test
    fun `dials read back from their own key and unknown layouts fall back to List`() {
        val key = stringPreferencesKey("overview_layout")
        assertEquals(OverviewLayout.DIALS, settingsFrom(key to "DIALS").overviewLayout)
        assertEquals(OverviewLayout.LIST, settingsFrom(key to "grid").overviewLayout)
        assertEquals(OverviewLayout.LIST, settingsFrom().overviewLayout)
    }

    @Test
    fun `status icons are off with nothing pinned until someone turns them on`() {
        val settings = settingsFrom()

        assertFalse(settings.statusIconsEnabled)
        assertEquals(emptyList<String>(), settings.pinnedAccountIds)
    }

    @Test
    fun `the status icon switch and the pinned ids read back in their stored order`() {
        val settings = settingsFrom(
            booleanPreferencesKey("status_icons_enabled") to true,
            stringPreferencesKey("pinned_account_ids") to "acct-2\nacct-1",
        )

        assertTrue(settings.statusIconsEnabled)
        assertEquals(listOf("acct-2", "acct-1"), settings.pinnedAccountIds)
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
