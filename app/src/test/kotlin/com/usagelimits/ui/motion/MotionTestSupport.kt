package com.usagelimits.ui.motion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import java.io.File
import kotlin.math.roundToInt

/**
 * Shared by the motion tests: synthetic accounts, and frames stepped on the Compose test clock.
 *
 * A frame is what the decor view draws at one 16 ms step of the held clock, so a test can ask
 * what was on screen at a given moment instead of sleeping.
 */
internal typealias MotionRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

internal const val MOTION_NOW = 1_757_000_000_000L

internal fun demoAccount(
    id: String,
    provider: ProviderId,
    usedPercent: Double? = 40.0,
    windowId: String = "5h",
    windowLabel: String = "5h limit",
    category: WindowCategory = WindowCategory.FIVE_HOUR,
    resetInMs: Long = 3_600_000,
) = AccountUsage(
    account = ProviderAccount(
        localId = id, provider = provider, externalAccountId = "ext-$id", email = "$id@example.invalid",
        displayName = id, plan = "Plus", credentialReference = "ref-$id", createdAt = 0L,
        lastSuccessfulSync = MOTION_NOW,
    ),
    snapshot = UsageSnapshot(
        accountId = id, fetchedAt = MOTION_NOW, status = SnapshotStatus.OK,
        windows = listOf(
            UsageWindow(
                id = windowId, label = windowLabel, category = category, usedPercent = usedPercent,
                periodSeconds = 18_000, resetAt = MOTION_NOW + resetInMs, exhausted = usedPercent == 100.0,
            ),
        ),
    ),
)

/**
 * The decor view drawn in software, for the one thing the hardware frames below cannot show under
 * Robolectric: the pre-API-31 ripple (see SegmentPressTest).
 */
internal fun MotionRule.softwareFrame(): Bitmap {
    waitForIdle()
    val root = activity.window.decorView
    return Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
}

/**
 * Frames drawn by one hardware renderer that lives as long as the test, as a device's does.
 *
 * A lazy item that has left the list is drawn from the display list its layer recorded while it
 * was still there, and a renderer that is torn down between frames discards those lists, so the
 * item would vanish at once and a fade-out could never be observed.
 */
internal class FrameRecorder(private val rule: MotionRule) : AutoCloseable {
    private val root = rule.activity.window.decorView.also { rule.waitForIdle() }
    private val reader = android.media.ImageReader.newInstance(root.width, root.height, android.graphics.PixelFormat.RGBA_8888, 2)
    private val renderer = android.graphics.HardwareRenderer()
    private val node = android.graphics.RenderNode("frames").apply { setPosition(0, 0, root.width, root.height) }

    init {
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
    }

    fun frame(): Bitmap {
        rule.waitForIdle()
        root.draw(node.beginRecording())
        node.endRecording()
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        return reader.acquireNextImage().use { image ->
            val plane = image.planes[0]
            val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, root.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(padded, 0, 0, root.width, root.height)
        }
    }

    /** The next [count] frames, 16 ms apart. */
    fun next(count: Int): List<Bitmap> = List(count) {
        rule.mainClock.advanceTimeByFrame()
        frame()
    }

    override fun close() {
        renderer.destroy()
        reader.close()
    }
}

/** Applies a state write made while the clock is held: no frame delivers it otherwise. */
internal fun MotionRule.change(block: () -> Unit) = runOnUiThread {
    block()
    Snapshot.sendApplyNotifications()
}

internal fun Bitmap.at(x: Int, y: Int): Int = getPixel(x, y)

internal fun Bitmap.sameAs(other: Bitmap, left: Int = 0, top: Int = 0, right: Int = width, bottom: Int = height): Boolean {
    for (y in top until bottom) for (x in left until right) if (getPixel(x, y) != other.getPixel(x, y)) return false
    return true
}

internal val Color.argb: Int get() = toArgb()

/** A rectangle of a frame in pixels, [right] and [bottom] exclusive. */
internal data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun union(other: Region) = Region(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

internal fun Rect.toRegion(margin: Int = 2) = Region(
    left.roundToInt() - margin, top.roundToInt() - margin, right.roundToInt() + margin, bottom.roundToInt() + margin,
)

/** How many pixels of [region] are drawn on (differ from [ground]), at whatever opacity. */
internal fun Bitmap.ink(region: Region, ground: Int): Int {
    var count = 0
    for (y in region.top until region.bottom) for (x in region.left until region.right) if (getPixel(x, y) != ground) count++
    return count
}

/**
 * How many pixels of [region] this frame draws on that [reference] leaves bare. Zero means that
 * everything visible here is part of what [reference] shows, however faded: the way to tell
 * "the old content, fading" from "something else is on screen as well".
 */
internal fun Bitmap.inkOutside(reference: Bitmap, region: Region, ground: Int): Int {
    var count = 0
    for (y in region.top until region.bottom) for (x in region.left until region.right) {
        if (getPixel(x, y) != ground && reference.getPixel(x, y) == ground) count++
    }
    return count
}

private val captureDir: File? = System.getProperty("capture.dir")?.takeIf { it.isNotBlank() }?.let(::File)

/**
 * Writes the frames as numbered PNGs when `-PcaptureDir=<abs path>` is given, as RedesignCaptureTest
 * does; otherwise the test only asserts. These are the frame sequences a person can look at.
 */
internal fun List<Bitmap>.saveSequence(name: String) {
    val dir = captureDir?.resolve("motion/$name") ?: return
    dir.mkdirs()
    forEachIndexed { index, frame ->
        File(dir, "%02d.png".format(index)).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
