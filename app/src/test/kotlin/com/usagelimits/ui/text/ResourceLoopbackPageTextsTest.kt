package com.usagelimits.ui.text

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.oauth.EnglishLoopbackPageTexts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browser result page's words, read from resources (R4).
 *
 * Under `en` each resource must equal the default the listener carries for code without resources;
 * under `de` the same four read as German.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class ResourceLoopbackPageTextsEnglishTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `every word equals the English default`() {
        val texts = ResourceLoopbackPageTexts(resources)

        assertEquals(EnglishLoopbackPageTexts.signedIn(), texts.signedIn())
        assertEquals(EnglishLoopbackPageTexts.signInFailed(), texts.signInFailed())
        assertEquals(EnglishLoopbackPageTexts.closeThisTab(), texts.closeThisTab())
        assertEquals(EnglishLoopbackPageTexts.noCodeReturned(), texts.noCodeReturned())
    }

    @Test
    fun `the default reads as it always did`() {
        assertEquals("Signed in", EnglishLoopbackPageTexts.signedIn())
        assertEquals("Sign-in failed", EnglishLoopbackPageTexts.signInFailed())
        assertEquals("You can close this tab and return to Usage Limits.", EnglishLoopbackPageTexts.closeThisTab())
        assertEquals("No authorization code was returned.", EnglishLoopbackPageTexts.noCodeReturned())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class ResourceLoopbackPageTextsGermanTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `the page reads as German`() {
        val texts = ResourceLoopbackPageTexts(resources)

        assertEquals("Angemeldet", texts.signedIn())
        assertEquals("Anmeldung fehlgeschlagen", texts.signInFailed())
        assertEquals("Du kannst diesen Tab schlie\u00DFen und zu Usage Limits zur\u00FCckkehren.", texts.closeThisTab())
        assertEquals("Es wurde kein Autorisierungscode zur\u00FCckgegeben.", texts.noCodeReturned())
    }
}
