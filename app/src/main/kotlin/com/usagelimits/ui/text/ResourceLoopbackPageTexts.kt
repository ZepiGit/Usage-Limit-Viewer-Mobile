package com.usagelimits.ui.text

import android.content.res.Resources
import com.usagelimits.R
import com.usagelimits.core.oauth.LoopbackPageTexts

/**
 * [LoopbackPageTexts] read from `strings.xml`, in the language of the given [resources].
 *
 * Resources rather than a Context so the registry can build it from the application's resources
 * once and still follow a change of language, as [ResourceCountdownTexts] does.
 */
class ResourceLoopbackPageTexts(private val resources: Resources) : LoopbackPageTexts {
    override fun signedIn(): String = resources.getString(R.string.loopback_signed_in)
    override fun signInFailed(): String = resources.getString(R.string.loopback_sign_in_failed)
    override fun closeThisTab(): String = resources.getString(R.string.loopback_close_tab)
    override fun noCodeReturned(): String = resources.getString(R.string.loopback_no_code)
}
