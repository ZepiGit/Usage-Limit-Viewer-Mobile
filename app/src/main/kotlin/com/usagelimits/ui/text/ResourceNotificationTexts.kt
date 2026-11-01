package com.usagelimits.ui.text

import android.content.res.Resources
import com.usagelimits.R
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.notifications.NotificationTexts
import com.usagelimits.core.time.CountdownTexts

/**
 * [NotificationTexts] read from `strings.xml`, in the language of the given [resources].
 *
 * The countdown inside a credit-expiry line comes from the same resources, so a German sentence
 * never carries an English "1h".
 */
class ResourceNotificationTexts(
    private val resources: Resources,
    override val countdown: CountdownTexts = ResourceCountdownTexts(resources),
) : NotificationTexts {

    override fun warning(name: String, label: String, mode: PercentDisplay): String =
        resources.getString(
            if (mode == PercentDisplay.USED) R.string.notification_warning_used
            else R.string.notification_warning_left,
            name, label,
        )

    override fun critical(name: String, label: String, mode: PercentDisplay): String =
        resources.getString(
            if (mode == PercentDisplay.USED) R.string.notification_critical_used
            else R.string.notification_critical_left,
            name, label,
        )

    override fun exhausted(name: String, label: String): String =
        resources.getString(R.string.notification_exhausted, name, label)

    override fun resetApproaching(name: String, label: String, minutes: Long): String =
        resources.getQuantityString(
            R.plurals.notification_reset_approaching, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            name, label, minutes,
        )

    override fun creditExpiring(name: String, duration: String): String =
        resources.getString(R.string.notification_credit_expiring, name, duration)

    override fun creditsAvailable(name: String, count: Int): String =
        resources.getQuantityString(R.plurals.notification_credits_available, count, name, count)

    override fun needsReconnect(name: String): String =
        resources.getString(R.string.notification_needs_reconnect, name)
}
