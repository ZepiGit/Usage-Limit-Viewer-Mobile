package com.usagelimits.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.oauth.SignInKeepAliveService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.robolectric.shadows.ShadowLooper
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.*
import org.junit.Before
import org.robolectric.Robolectric
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class AppIconsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPendingChoice() {
        AppIcon.select(AppIcon.CURRENT)
        AppIcon.applyPending(context)
        SignInKeepAliveService.signingIn = false
    }

    @After
    fun drainStartupWork() {
        val app = context as? UsageLimitsApp ?: return
        ShadowLooper.idleMainLooper()
        runBlocking { app.startupJob?.join() }
        ShadowLooper.idleMainLooper()
        WorkManager.getInstance(app).cancelAllWork().result.get()
    }

    @Test
    fun `picker displays pending choice without changing the active launcher`() {
        compose.setContent { UsageLimitsTheme { AppIconSettings() } }
        compose.onNodeWithText(AppIcon.CURRENT.label).assertIsSelected()
        AppIcon.entries.forEach { selected ->
            compose.onNodeWithText(selected.label).assertIsDisplayed().performClick().assertIsSelected()
            assertEquals(selected, AppIcon.pending)
            assertEquals(AppIcon.CURRENT, AppIcon.current(context))
            AppIcon.entries.filterNot { it == selected }.forEach {
                compose.onNodeWithText(it.label).assertIsNotSelected()
            }
        }
    }

    @Test
    @Config(application = UsageLimitsApp::class)
    fun `pending choice survives picker disposal and is applied when activity stops`() {
        var showing by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { UsageLimitsTheme { if (showing) AppIconSettings() } }
        compose.onNodeWithText(AppIcon.GAUGE_COIN.label).performClick().assertIsSelected()
        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        assertEquals(AppIcon.CURRENT, AppIcon.current(context))
        compose.runOnIdle { showing = true }
        compose.onNodeWithText(AppIcon.GAUGE_COIN.label).assertIsSelected()
        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        val activity = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        activity.pause().stop()
        assertEquals(AppIcon.GAUGE_COIN, AppIcon.current(context))
        assertNull(AppIcon.pending)
        activity.destroy()
    }

    @Test
    @Config(application = UsageLimitsApp::class)
    fun `pending choice waits while a browser sign-in is in progress`() {
        AppIcon.select(AppIcon.GAUGE_COIN)
        val login = SignInKeepAliveService.start(context)
        val activity = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        activity.pause().stop()
        assertEquals(AppIcon.CURRENT, AppIcon.current(context))
        assertEquals(AppIcon.GAUGE_COIN, AppIcon.pending)

        SignInKeepAliveService.stop(context, login)
        activity.restart().resume().pause().stop()
        assertEquals(AppIcon.GAUGE_COIN, AppIcon.current(context))
        assertNull(AppIcon.pending)
        activity.destroy()
    }

    @Test
    fun `default launcher retains the original component identity`() {
        assertEquals(".MainActivity", AppIcon.CURRENT.alias)
        val launchers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        assertEquals(listOf("com.usagelimits.MainActivity"), launchers.map { it.activityInfo.name })
    }

    @Test
    fun `icon ids and labels match the iOS catalog in order`() {
        val swift = File("../ios/UsageLimits/Sources/Screens/AppIconScreen.swift").readText()
        val choices = Regex("""\(\.(\w+), "[^"]+", "([^"]+)"\)""").findAll(swift)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        val androidChoices = AppIcon.entries.map { icon ->
            val parts = icon.name.lowercase().split('_')
            (parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }) to icon.label
        }
        assertEquals(6, choices.size)
        assertEquals(androidChoices, choices)
    }

    @Test
    fun `every icon alias targets the main activity and has a foreground`() {
        AppIcon.entries.forEach { icon ->
            val info = context.packageManager.getActivityInfo(
                ComponentName(context, UsageLimitsActivity::class.java.name.substringBeforeLast('.') + icon.alias), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(UsageLimitsActivity::class.java.name, info.targetActivity)
            assertTrue(info.exported)
            assertNotNull(context.getDrawable(icon.foreground))
        }
    }

    @Test
    fun `switching icons leaves only the selected launcher enabled and can restore default`() {
        assertEquals(AppIcon.CURRENT, AppIcon.current(context))
        listOf(AppIcon.GAUGE_COIN, AppIcon.CURRENT).forEach { selected ->
            AppIcon.apply(context, selected)
            assertEquals(selected, AppIcon.current(context))
            AppIcon.entries.forEach { icon ->
                assertEquals(if (icon == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    context.packageManager.getComponentEnabledSetting(ComponentName(context, UsageLimitsActivity::class.java.name.substringBeforeLast('.') + icon.alias)))
            }
            val launchers = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
            assertEquals(listOf(UsageLimitsActivity::class.java.name.substringBeforeLast('.') + selected.alias), launchers.map { it.activityInfo.name })
        }
    }
}
