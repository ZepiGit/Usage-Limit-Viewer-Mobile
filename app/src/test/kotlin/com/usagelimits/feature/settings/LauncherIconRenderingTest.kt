package com.usagelimits.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.R
import com.usagelimits.UsageLimitsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconRenderingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launchers = listOf(
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_gauge_coin,
        R.mipmap.ic_launcher_ai_gauge,
        R.mipmap.ic_launcher_ai_hourglass,
        R.mipmap.ic_launcher_ai_refresh_ring,
        R.mipmap.ic_launcher_ai_battery,
    )

    private fun render(drawable: Drawable, inset: Int = 0): Bitmap =
        Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(inset, inset, it.width - inset, it.height - inset)
            drawable.draw(Canvas(it))
        }

    @Test
    fun `framework adaptive icons fill transparent layers with black`() {
        val adaptive = render(AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT), ColorDrawable(Color.TRANSPARENT)))
        assertEquals(Color.BLACK, adaptive.getPixel(108, 108))
    }

    @Test
    fun `launcher and round resources render the exact alpha foreground without an adaptive plate`() {
        (launchers.zip(AppIcon.entries.map { it.foreground }) +
            (R.mipmap.ic_launcher_round to AppIcon.CURRENT.foreground)).forEach { (launcher, foreground) ->
            val name = context.resources.getResourceEntryName(launcher)
            val drawable = requireNotNull(context.getDrawable(launcher))
            val actual = render(drawable)
            // AdaptiveIconDrawable extends each foreground edge by 25% of the icon bounds.
            val expected = render(requireNotNull(context.getDrawable(foreground)), inset = -54)
            assertEquals("$name transparent padding", 0, Color.alpha(actual.getPixel(108, 8)))
            assertTrue("$name must retain foreground pixels and alpha", expected.sameAs(actual))
            assertTrue("$name must bypass AdaptiveIconDrawable", drawable is InsetDrawable)
            assertTrue("$name must still contain visible artwork", (0 until 216).any { x ->
                (0 until 216).any { y -> Color.alpha(actual.getPixel(x, y)) > 0 }
            })
        }
    }

    @Test
    fun `application and all six unchanged launcher aliases resolve the alpha resources`() {
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        assertEquals(".MainActivity", AppIcon.CURRENT.alias)
        assertEquals(6, AppIcon.entries.size)
        AppIcon.entries.zip(launchers).forEach { (choice, launcher) ->
            val info = context.packageManager.getActivityInfo(
                ComponentName(context, "com.usagelimits${choice.alias}"),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals(UsageLimitsActivity::class.java.name, info.targetActivity)
            assertEquals(launcher, info.icon)
            assertTrue(info.loadIcon(context.packageManager) is InsetDrawable)
        }
    }
}
