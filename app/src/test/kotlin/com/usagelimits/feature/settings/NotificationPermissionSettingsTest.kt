package com.usagelimits.feature.settings

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.await
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.jvm.internal.CoroutineStackFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.Before
import org.junit.After
import org.robolectric.shadows.ShadowLooper
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.feature.waitDrained
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPermissionSettingsTest {
    private val fixtureDispatcher = StandardTestDispatcher()
    private val fixtureJob = SupervisorJob()
    private val fixtureScope = CoroutineScope(fixtureJob + fixtureDispatcher)
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(effectContext = fixtureDispatcher)
    @get:Rule
    val testName = org.junit.rules.TestName()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()

    @Before fun prepare() {
        // The v2 rule queues composition work; setup must let that same scheduler run while suspended.
        val preparation = fixtureScope.async {
            boundary("prepare.startup") { app.startupJob?.join() }
            boundary("prepare.cancelWork") { WorkManager.getInstance(app).cancelAllWork().await() }
            boundary("prepare.statusIconsEnabled") { app.container.settingsStore.setStatusIconsEnabled(false) }
            boundary("prepare.pinnedAccounts") { app.container.settingsStore.setPinnedAccounts(emptyList()) }
            boundary("prepare.eligibility") { app.container.settingsStore.recordStatusIconEligibility(true) }
        }
        try {
            compose.waitDrained { preparation.isCompleted }
            preparation.getCompleted()
        } catch (failure: Throwable) {
            try {
                dumpSettingsWait(preparation)
            } catch (diagnosticFailure: Exception) {
                failure.addSuppressed(diagnosticFailure)
            }
            throw failure
        } finally {
            preparation.cancel()
            compose.waitDrained { preparation.isCompleted }
        }
    }

    @After fun drain() {
        try {
            lateinit var barrier: kotlinx.coroutines.Job
            boundary("drain.observeEligibility") {
                compose.runOnUiThread { barrier = app.observeStatusIconEligibility() }
            }
            boundary("drain.awaitEligibility") { compose.waitDrained { barrier.isCompleted } }
            boundary("drain.cancelWork") { WorkManager.getInstance(app).cancelAllWork().result.get() }
            boundary("drain.idleMain") { ShadowLooper.idleMainLooper() }
            boundary("drain.resetWorkManager") { resetTestWorkManager() }
        } finally {
            fixtureJob.cancel()
            compose.waitDrained { fixtureJob.isCompleted }
        }
    }

    // DataStore 1.3 / coroutines 1.11 shortcut: the actor's frame owns the update; a queue entry or ack waiter does not.
    private fun dumpSettingsWait(preparation: Job) {
        val prefix = "NotificationPermissionSettingsTest ${testName.methodName} WAIT"
        fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).run {
            isAccessible = true
            checkNotNull(get(owner))
        }
        fun identity(value: Any?): String =
            if (value == null) "none" else "${value.javaClass.name}@${System.identityHashCode(value)}"

        System.err.println("$prefix sdk=${android.os.Build.VERSION.SDK_INT} main=${identity(Dispatchers.Main)} " +
            "fixture=${identity(fixtureDispatcher)} scheduler=${System.identityHashCode(fixtureDispatcher.scheduler)} " +
            "composeScheduler=${System.identityHashCode(compose.mainClock.scheduler)}")
        var store = field(app.container.settingsStore, "dataStore")
        var wrappers = 0
        while (wrappers < 4 && store.javaClass.name == "androidx.datastore.preferences.core.PreferenceDataStore") {
            store = field(store, "delegate")
            wrappers++
        }
        check(store.javaClass.name == "androidx.datastore.core.DataStoreImpl") {
            "Expected DataStoreImpl after $wrappers PreferenceDataStore delegates, got ${store.javaClass.name}"
        }
        System.err.println("$prefix datastore=${identity(store)} wrappers=$wrappers")
        val storeContext = (field(store, "scope") as CoroutineScope).coroutineContext
        val appContext = (field(app, "appScope") as CoroutineScope).coroutineContext
        System.err.println("$prefix datastoreDispatcher=${identity(storeContext[ContinuationInterceptor])} " +
            "applicationDispatcher=${identity(appContext[ContinuationInterceptor])}")
        val remaining = field(field(store, "writeActor"), "remainingMessages")
        System.err.println("$prefix actorRemaining=${remaining.javaClass.getMethod("get").invoke(remaining)}")

        System.err.println("$prefix continuationCoverage=bounded-job-trees " +
            "scopes=datastore,application,preparation,active-caller-children queue=not-inspected")
        val stateMethod = Class.forName("kotlinx.coroutines.JobSupport").getMethod("getState\$kotlinx_coroutines_core")
        val incomplete = Class.forName("kotlinx.coroutines.Incomplete")
        val listMethod = incomplete.getMethod("getList")
        val nextNode = Class.forName("kotlinx.coroutines.internal.LockFreeLinkedListNode").getMethod("getNextNode")
        val continuationDelegate = Class.forName("kotlinx.coroutines.CancellableContinuationImpl")
            .getDeclaredField("delegate").apply { isAccessible = true }
        fun status(job: Job?) = "${identity(job)} active=${job?.isActive} " +
            "completed=${job?.isCompleted} cancelled=${job?.isCancelled}"

        fun walkFrames(start: CoroutineStackFrame?, limit: Int, visit: (CoroutineStackFrame) -> Unit): Int {
            var frame = start
            var visited = 0
            while (visited < limit) {
                val current = frame ?: break
                visit(current)
                visited++
                if (visited < limit) frame = current.callerFrame
            }
            return visited
        }

        val storeClass = store.javaClass.name
        var handleUpdate: CoroutineStackFrame? = null
        var jobsLeft = 24
        var scopeJobs = 0
        var recoveredContinuations = 0
        fun scan(label: String, job: Job, actorTree: Boolean, depth: Int = 0) {
            if (scopeJobs >= 8 || jobsLeft == 0) return
            scopeJobs++
            jobsLeft--
            val dispatcher = (job as? CoroutineScope)?.coroutineContext?.get(ContinuationInterceptor)
            System.err.println("$prefix $label job=${status(job)} dispatcher=${identity(dispatcher)}")
            var framesLeft = 20
            if (actorTree && job.isActive) {
                framesLeft -= walkFrames(job as? CoroutineStackFrame, framesLeft) { frame ->
                    frame.getStackTraceElement()?.let { System.err.println("$prefix $label actorFrame=$it") }
                    if (handleUpdate == null && frame.javaClass.name == "$storeClass\$handleUpdate\$2") {
                        handleUpdate = frame
                    }
                }
            }
            val state = checkNotNull(stateMethod.invoke(job))
            if (incomplete.isInstance(state)) {
                val head = listMethod.invoke(state)
                var node: Any = head ?: state
                for (index in 0 until 16) {
                    if (framesLeft == 0) break
                    if (node.javaClass.name == "kotlinx.coroutines.ChildContinuation") {
                        val child = field(node, "child")
                        val delegate = continuationDelegate.get(child) as Continuation<*>
                        recoveredContinuations++
                        System.err.println("$prefix $label continuationDispatcher=${identity(delegate.context[ContinuationInterceptor])}")
                        framesLeft -= walkFrames(delegate as? CoroutineStackFrame, framesLeft) { frame ->
                            frame.getStackTraceElement()?.let { System.err.println("$prefix at $it") }
                        }
                    }
                    if (head == null) break
                    node = nextNode.invoke(node) ?: break
                    if (node === head) break
                }
            }
            if (depth < 3) job.children.take(minOf(8 - scopeJobs, jobsLeft)).forEachIndexed { index, child ->
                scan("$label/$index", child, actorTree, depth + 1)
            }
        }

        for ((label, job) in listOf("datastore" to storeContext[Job], "application" to appContext[Job], "preparation" to preparation)) {
            scopeJobs = 0
            if (job != null) scan(label, job, actorTree = label == "datastore")
            System.err.println("$prefix continuationScope=$label scannedJobs=$scopeJobs")
        }
        System.err.println("$prefix recoveredContinuations=$recoveredContinuations")

        val updateFrame = handleUpdate
        if (updateFrame != null) {
            try {
                val update = field(updateFrame, "\$update")
                if (update.javaClass.name == "androidx.datastore.core.Message\$Update") {
                    val callerContext = update.javaClass.getMethod("getCallerContext").invoke(update) as CoroutineContext
                    val ack = update.javaClass.getMethod("getAck").invoke(update) as Job
                    if (!ack.isCompleted) {
                        val callerJob = callerContext[Job]
                        val dispatcher = callerContext[ContinuationInterceptor]
                        val scheduler = (dispatcher as? TestDispatcher)?.scheduler
                        val inTransform = callerJob?.children?.take(jobsLeft)?.any { child ->
                            var transform = false
                            var sameUpdate = false
                            walkFrames(child as? CoroutineStackFrame, 20) { frame ->
                                if (frame.javaClass.name == "$storeClass\$transformAndWrite\$2\$1" &&
                                    frame.getStackTraceElement()?.lineNumber == 435
                                ) transform = true
                                if (frame === updateFrame) sameUpdate = true
                            }
                            child.isActive && transform && sameUpdate
                        } == true
                        val phase = if (inTransform) "transformAndWrite.withContext" else "UNRESOLVED"
                        System.err.println("$prefix actorOwner=${identity(update)} actorPhase=$phase " +
                            "callerContext=${identity(callerContext)} callerJob=${status(callerJob)} " +
                            "callerDispatcher=${identity(dispatcher)} callerScheduler=${identity(scheduler)} ack=${status(ack)}")
                        try {
                            if (dispatcher?.javaClass?.name == "kotlinx.coroutines.android.HandlerContext") {
                                val handler = field(dispatcher, "handler") as Handler
                                val looper = handler.looper
                                val main = Looper.getMainLooper()
                                val queue = looper.queue
                                val mainQueue = main.queue
                                val thread = looper.thread
                                System.err.println("$prefix actorHandler=${identity(handler)} actorLooper=${identity(looper)} " +
                                    "actorQueue=${identity(queue)} actorThreadId=${thread.id} " +
                                    "actorThreadName=${thread.name} actorThreadAlive=${thread.isAlive} " +
                                    "pumpedLooper=${identity(main)} pumpedQueue=${identity(mainQueue)} " +
                                    "sameLooper=${looper === main} sameQueue=${queue === mainQueue} sameThread=${thread === main.thread}")
                                val mainShadow = ShadowLooper.getShadowMainLooper()
                                System.err.println("$prefix actorPaused=${shadowOf(looper).isPaused} " +
                                    "pumpedShadow=${identity(mainShadow)} pumpedPaused=${mainShadow.isPaused}")
                            }
                            walkFrames(callerJob as? CoroutineStackFrame, 20) { frame ->
                                frame.getStackTraceElement()?.let { System.err.println("$prefix actorCaller frame=$it") }
                            }
                            scopeJobs = 0
                            if (callerJob != null) scan("actorCaller", callerJob, actorTree = false)
                        } catch (failure: ReflectiveOperationException) {
                            System.err.println("$prefix actorCallerProbeError=${failure.javaClass.name}")
                        }
                        return
                    }
                }
            } catch (failure: ReflectiveOperationException) {
                System.err.println("$prefix actorProbeError=${failure.javaClass.name}")
            }
        }
        System.err.println("$prefix actorOwner=UNRESOLVED actorPhase=UNRESOLVED " +
            "callerContext=UNRESOLVED callerDispatcher=UNRESOLVED")
    }

    // The CI test START event precedes setup; paired markers locate its first unfinished wait.
    private inline fun <T> boundary(name: String, action: () -> T): T {
        System.err.println("NotificationPermissionSettingsTest ${testName.methodName} START $name nanoTime=${System.nanoTime()}")
        return action().also {
            System.err.println("NotificationPermissionSettingsTest ${testName.methodName} END $name nanoTime=${System.nanoTime()}")
        }
    }

    @Test
    fun `blocked notifications explain why alerts are silent without disabling preferences`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var below20: Boolean? = null
        render(onNotifyBelow20 = { below20 = it })
        scrollTo("Notifications not allowed")
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
        compose.onNodeWithText("No alerts will be shown until notifications are allowed. Your alert preferences are kept.")
            .assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertIsEnabled()
        scrollTo("Below 20% left")
        compose.onNodeWithContentDescription("Below 20% left").assertIsEnabled().performClick()
        assertEquals(false, below20)
    }

    @Test
    fun `returning from system settings rereads both grants and revocations`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(notificationPermissionHandled = true))
        scrollTo("Notifications not allowed")
        scrollTo("Open notification settings")
        compose.onNodeWithText("Open notification settings").assertIsEnabled().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Notifications allowed").assertIsDisplayed().assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            ),
        )
        compose.onNodeWithText("Open notification settings").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
    }

    @Test
    fun `an earlier denial can be allowed through the system dialog from Settings`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        var permissionAsked = false
        render(AppSettings(notificationPermissionHandled = true), onNotificationPermissionHandled = { permissionAsked = true })
        scrollTo("Allow notifications")
        boundary("request.click") { compose.onNodeWithText("Allow notifications").performClick() }
        org.junit.Assert.assertTrue(permissionAsked)
        boundary("request.awaitDialog") { compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null } }
        val request = shadowOf(compose.activity).lastRequestedPermission
        boundary("request.assertPending") { compose.onNodeWithText("Allow notifications").assertIsNotEnabled() }
        org.junit.Assert.assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        boundary("answer.runOnIdle") {
            compose.runOnIdle {
                shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                boundary("answer.permissionCallback") {
                    compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                        intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
                }
            }
        }
        boundary("answer.assertAllowed") { compose.onNodeWithText("Notifications allowed").assertIsDisplayed() }
        boundary("answer.assertNoRequest") { compose.onNodeWithText("Allow notifications").assertDoesNotExist() }
    }

    @Test
    fun `a second denial replaces the dialog action with app notification settings`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        render(AppSettings(notificationPermissionHandled = true))
        scrollTo("Allow notifications")
        compose.onNodeWithText("Allow notifications").performClick()
        compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null }
        val request = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnIdle {
            shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        }
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        compose.onNodeWithText("Open notification settings").assertIsEnabled().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `a first request made from Settings does not offer an unavailable dialog again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render()
        scrollTo("Allow notifications")
        compose.onNodeWithText("Allow notifications").performClick()
        compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null }
        val request = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnIdle {
            shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        }
        compose.onNodeWithText("Open notification settings").assertIsEnabled()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
    }

    @Test
    fun `missing notification settings falls back to this app's details page`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val details = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", app.packageName, null))
        val resolution = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "android.settings"
                name = "AppDetails"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(details, resolution)
        shadowOf(app).checkActivities(true)
        render(AppSettings(notificationPermissionHandled = true))
        scrollTo("Open notification settings")
        compose.onNodeWithText("Open notification settings").performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${app.packageName}", opened.data.toString())
    }

    @Test
    @Config(sdk = [32])
    fun `Android before 13 has no permission to deny, so notifications left on read as allowed`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render()
        scrollTo("Notifications allowed")
        compose.onNodeWithText("Notifications allowed").assertIsDisplayed()
        compose.onNodeWithText("Notifications not allowed").assertDoesNotExist()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        org.junit.Assert.assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [30])
    fun `Android before 13 with notifications switched off says so and leads to the app's notification settings, never a dialog`() {
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        render()
        scrollTo("Notifications not allowed")
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        scrollTo("Open notification settings")
        compose.onNodeWithText("Open notification settings").assertIsEnabled().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        org.junit.Assert.assertNull(shadowOf(compose.activity).lastRequestedPermission)

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Notifications allowed").assertIsDisplayed()
    }

    @Test
    fun `a granted permission with notifications switched off leads to the app's notification settings, not the dialog`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        render()
        scrollTo("Notifications not allowed")
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        scrollTo("Open notification settings")
        compose.onNodeWithText("Open notification settings").performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        org.junit.Assert.assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    @Config(qualifiers = "de")
    fun `German Settings explains denied notifications and offers a way to allow them`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render()
        scrollTo("Benachrichtigungen nicht erlaubt")
        compose.onNodeWithText("Benachrichtigungen nicht erlaubt").assertIsDisplayed()
        compose.onNodeWithText("Es werden keine Hinweise angezeigt, bis Benachrichtigungen erlaubt sind. Deine Auswahl unten bleibt gespeichert.")
            .assertIsDisplayed()
        compose.onNodeWithText("Benachrichtigungen erlauben").assertIsEnabled()
    }

    private fun render(
        settings: AppSettings = AppSettings(),
        onNotifyBelow20: (Boolean) -> Unit = {},
        onNotificationPermissionHandled: () -> Unit = {},
    ) {
        boundary("render.setContent") {
            compose.setContent {
                UsageLimitsTheme {
                    SettingsScreen(
                        state = UsageUiState(settings = settings),
                        onSyncIntervalChange = {},
                        onNotificationPermissionHandled = onNotificationPermissionHandled, onAppThemeChange = {},
                        onNotifyBelow20 = onNotifyBelow20,
                        onNotifyBelow10 = {},
                        onNotifyExhausted = {},
                        onNotifyResetCredit = {},
                        onNotifyAuthExpired = {},
                        onNotifyResetApproaching = {},
                        onNotifyCreditExpiring = {},
                        onShowTier = {},
                        onOverviewLayoutChange = {},
                        onPercentDisplayChange = {},
                    )
                }
            }
        }
    }

    private fun scrollTo(text: String) {
        boundary("scrollTo") { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text)) }
    }
}
