package com.usagelimits.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two sentences the publisher builds around its findings: the title over several of them and
 * the summary for the lines the inbox cannot show (R4). Both must follow the language of the
 * resources, and in English must read exactly as they did when they were hard-coded.
 */
abstract class NotificationSummaryTextsFixture {
    protected val resources = ApplicationProvider.getApplicationContext<Context>().resources
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NotificationSummaryTextsEnglishTest : NotificationSummaryTextsFixture() {

    @Test
    fun `the title over several findings counts them`() {
        for (count in listOf(2, 3, 7, 12)) {
            assertEquals("$count usage alerts", NotificationPublisher.titleForCount(resources, count))
        }
    }

    @Test
    fun `the overflow summary is a plus and the hidden count`() {
        for (hidden in listOf(1, 2, 9)) {
            assertEquals("+$hidden more", NotificationPublisher.overflowSummary(resources, hidden))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class NotificationSummaryTextsGermanTest : NotificationSummaryTextsFixture() {

    @Test
    fun `the title over several findings counts them in German`() {
        assertEquals("2 Nutzungswarnungen", NotificationPublisher.titleForCount(resources, 2))
        assertEquals("12 Nutzungswarnungen", NotificationPublisher.titleForCount(resources, 12))
    }

    @Test
    fun `the title takes the singular for one`() {
        assertEquals("1 Nutzungswarnung", NotificationPublisher.titleForCount(resources, 1))
    }

    @Test
    fun `the overflow summary says weitere`() {
        assertEquals("+1 weitere", NotificationPublisher.overflowSummary(resources, 1))
        assertEquals("+4 weitere", NotificationPublisher.overflowSummary(resources, 4))
    }
}
