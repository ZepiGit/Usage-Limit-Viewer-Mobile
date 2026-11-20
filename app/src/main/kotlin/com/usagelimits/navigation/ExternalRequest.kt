package com.usagelimits.navigation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.usagelimits.UsageLimitsActivity

/**
 * What an entry from outside the app asked the shell to show: one account (a status icon, a widget
 * row) or Overview itself (a usage alert, a whole-widget tap). An ordinary launch asks for nothing
 * and the app stays where it was.
 *
 * The activity holds one pending request until the shell has handled it, and keeps it across
 * recreation. Once handled there is none, so the same link tapped again is a new request.
 */
sealed interface ExternalRequest {
    // Plain classes, not data classes or an object: each entry is its own request, handled by
    // identity, so two equal ones in a row are two requests.
    class OpenAccount(val accountId: String) : ExternalRequest

    class OpenOverview : ExternalRequest

    fun writeTo(bundle: Bundle) {
        when (this) {
            is OpenAccount -> bundle.putString(KEY_ACCOUNT, accountId)
            is OpenOverview -> bundle.putBoolean(KEY_OVERVIEW, true)
        }
    }

    companion object {
        /** The extra that names an account; status icons and widget rows set it. */
        const val EXTRA_ACCOUNT_ID = "accountId"

        /** The action of an entry that opens Overview, whatever the app showed before. */
        const val ACTION_OPEN_OVERVIEW = "com.usagelimits.action.OPEN_OVERVIEW"

        private const val KEY_ACCOUNT = "externalRequest.accountId"
        private const val KEY_OVERVIEW = "externalRequest.overview"

        /**
         * The request [intent] makes, or null for an ordinary launch. A launch from Recents
         * replays the intent that first started the task, which was handled then: it asks for
         * nothing.
         */
        fun from(intent: Intent?): ExternalRequest? {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            intent.getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.isNotEmpty() }?.let { return OpenAccount(it) }
            return if (intent.action == ACTION_OPEN_OVERVIEW) OpenOverview() else null
        }

        /** An entry that opens Overview: a usage alert, a whole-widget tap. */
        fun openOverview(context: Context): Intent =
            Intent(context, UsageLimitsActivity::class.java).setAction(ACTION_OPEN_OVERVIEW)

        /** The request [writeTo] kept in [bundle], or null when none was pending. */
        fun readFrom(bundle: Bundle): ExternalRequest? {
            bundle.getString(KEY_ACCOUNT)?.let { return OpenAccount(it) }
            return if (bundle.getBoolean(KEY_OVERVIEW)) OpenOverview() else null
        }
    }
}
