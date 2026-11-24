package com.usagelimits.core.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

/**
 * The page the browser is shown after the redirect, and where its words come from (R4).
 *
 * The listener has no Context, so it takes its words as [LoopbackPageTexts] and falls back to
 * [EnglishLoopbackPageTexts]. The English page is pinned byte for byte against the markup the
 * listener shipped with, so moving the words out of it changed nothing.
 */
class LoopbackPageTextsTest {

    private val german = object : LoopbackPageTexts {
        override fun signedIn() = "Angemeldet"
        override fun signInFailed() = "Anmeldung fehlgeschlagen"
        override fun closeThisTab() = "Du kannst diesen Tab schlie\u00DFen und zu Usage Limits zur\u00FCckkehren."
        override fun noCodeReturned() = "Es wurde kein Autorisierungscode zur\u00FCckgegeben."
    }

    /** The markup as it was before the words were injected. */
    private fun shippedHtml(title: String, detail: String) = """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>$title</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{background:#05070D;color:#E8ECF5;font-family:system-ui,-apple-system,sans-serif;
               display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
          .card{background:#111726;border-radius:20px;padding:32px;max-width:360px;text-align:center}
          h1{font-size:20px;margin:0 0 8px}p{color:#93A0B8;margin:0;font-size:14px}
        </style></head>
        <body><div class="card"><h1>$title</h1><p>$detail</p></div></body></html>
    """.trimIndent()

    /** Serves one redirect request and returns the whole HTTP reply the browser would read. */
    private fun reply(query: String, texts: LoopbackPageTexts?): String = runBlocking {
        val server = if (texts == null) LoopbackServer(0) else LoopbackServer(0, texts)
        server.start()
        val port = server.localPort
        val redirect = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 20_000) }
        delay(200)
        val raw = withContext(Dispatchers.IO) {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { client ->
                client.getOutputStream().write(
                    "GET /callback?$query HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(Charsets.UTF_8),
                )
                client.getOutputStream().flush()
                client.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        }
        redirect.await()
        server.close()
        raw
    }

    private fun body(reply: String) = reply.substringAfter("\r\n\r\n")

    @Test(timeout = 30_000)
    fun `without texts the success page is the English page it always was`() {
        val raw = reply("code=c&state=s", texts = null)

        assertEquals(
            shippedHtml("Signed in", "You can close this tab and return to Usage Limits."),
            body(raw),
        )
        assertTrue(raw.contains("Content-Length: ${body(raw).toByteArray(Charsets.UTF_8).size}\r\n"))
    }

    @Test(timeout = 30_000)
    fun `without texts the failure page names the authorization server's description`() {
        val raw = reply("error=access_denied&error_description=User%20said%20no", texts = null)

        assertEquals(shippedHtml("Sign-in failed", "User said no"), body(raw))
    }

    @Test(timeout = 30_000)
    fun `without texts a failure with no description falls back to the error code`() {
        assertEquals(shippedHtml("Sign-in failed", "access_denied"), body(reply("error=access_denied", texts = null)))
    }

    @Test(timeout = 30_000)
    fun `the English default has the sentence for a redirect that carried nothing usable`() {
        assertEquals("No authorization code was returned.", EnglishLoopbackPageTexts.noCodeReturned())
    }

    @Test(timeout = 30_000)
    fun `injected texts replace every word of the success page and Content-Length counts their bytes`() {
        val raw = reply("code=c&state=s", german)
        val page = body(raw)

        assertEquals(
            shippedHtml("Angemeldet", "Du kannst diesen Tab schlie\u00DFen und zu Usage Limits zur\u00FCckkehren."),
            page,
        )
        assertFalse(page.contains("Signed in"))
        assertTrue(raw.contains("Content-Length: ${page.toByteArray(Charsets.UTF_8).size}\r\n"))
    }

    @Test(timeout = 30_000)
    fun `injected texts title the failure page and the server's description stays as sent`() {
        val page = body(reply("error=access_denied&error_description=Nutzer%20lehnte%20ab", german))

        assertEquals(shippedHtml("Anmeldung fehlgeschlagen", "Nutzer lehnte ab"), page)
    }

    @Test(timeout = 30_000)
    fun `injected texts are HTML-escaped like the description is`() {
        val hostile = object : LoopbackPageTexts by EnglishLoopbackPageTexts {
            override fun signedIn() = "<b>Tom & \"Jerry\"</b>"
        }

        val page = body(reply("code=c&state=s", hostile))

        assertTrue(page, page.contains("<title>&lt;b&gt;Tom &amp; &quot;Jerry&quot;&lt;/b&gt;</title>"))
        assertFalse(page.contains("<b>"))
    }

    @Test(timeout = 30_000)
    fun `an attacker-chosen description is still escaped when texts are injected`() {
        val page = body(reply("error=x&error_description=%3Cimg%20src%3Dx%20onerror%3Dalert(1)%3E", german))

        assertFalse(page.contains("<img"))
        assertTrue(page.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }
}
