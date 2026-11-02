package com.usagelimits.core.oauth

/**
 * The words of the page [LoopbackServer] shows the browser once the redirect has arrived.
 *
 * The listener is served from a socket and has no Context, so it cannot reach `Resources`. The
 * caller that has one hands the words in; [EnglishLoopbackPageTexts] is the default every
 * context-free caller and test gets, the same arrangement as `CountdownTexts`. The server escapes
 * what it is given, so an implementation returns plain text.
 */
interface LoopbackPageTexts {
    /** Title and heading of the page after a redirect that carried a code. */
    fun signedIn(): String

    /** Title and heading of the page after a redirect that carried an error or no code. */
    fun signInFailed(): String

    /** The line under the heading when the sign-in worked. */
    fun closeThisTab(): String

    /** The line under the heading when the redirect named no error and carried no code either. */
    fun noCodeReturned(): String
}

/** The wording the page shipped with, kept as the default so context-free callers are unchanged. */
object EnglishLoopbackPageTexts : LoopbackPageTexts {
    override fun signedIn() = "Signed in"
    override fun signInFailed() = "Sign-in failed"
    override fun closeThisTab() = "You can close this tab and return to Usage Limits."
    override fun noCodeReturned() = "No authorization code was returned."
}
