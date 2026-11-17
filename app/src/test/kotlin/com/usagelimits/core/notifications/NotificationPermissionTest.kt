package com.usagelimits.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun `only an unasked ungranted permission on Android 13 or newer is requested at launch`() {
        data class Case(val sdk: Int, val asked: Boolean, val granted: Boolean, val expected: Boolean)
        val cases = listOf(
            Case(32, false, false, false),
            Case(32, false, true, false),
            Case(32, true, false, false),
            Case(32, true, true, false),
            Case(33, false, false, true),
            Case(33, false, true, false),
            Case(33, true, false, false),
            Case(33, true, true, false),
            Case(36, false, false, true),
            Case(36, true, false, false),
        )
        cases.forEach { case ->
            assertEquals(case.toString(), case.expected,
                shouldAskNotificationPermission(case.sdk, case.asked, case.granted))
        }
    }
}
