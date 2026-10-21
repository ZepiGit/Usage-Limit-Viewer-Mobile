package com.usagelimits.feature.settings

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
import com.usagelimits.MainActivity
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.*
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

    @Test
    fun `picker displays every choice and selects its launcher alias`() {
        compose.setContent { UsageLimitsTheme { AppIconSettings() } }
        compose.onNodeWithText(AppIcon.CURRENT.label).assertIsSelected()
        AppIcon.entries.forEach { selected ->
            compose.onNodeWithText(selected.label).assertIsDisplayed().performClick().assertIsSelected()
            assertEquals(selected, AppIcon.current(context))
            AppIcon.entries.filterNot { it == selected }.forEach {
                compose.onNodeWithText(it.label).assertIsNotSelected()
            }
        }
    }

    @Test
    fun `every icon alias targets the main activity and has a foreground`() {
        AppIcon.entries.forEach { icon ->
            val info = context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java.name.substringBeforeLast('.') + icon.alias), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(MainActivity::class.java.name, info.targetActivity)
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
                    context.packageManager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java.name.substringBeforeLast('.') + icon.alias)))
            }
            val launchers = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
            assertEquals(listOf(MainActivity::class.java.name.substringBeforeLast('.') + selected.alias), launchers.map { it.activityInfo.name })
        }
    }
}
