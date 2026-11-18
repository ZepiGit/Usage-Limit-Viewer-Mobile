package com.usagelimits.statusicon

import android.content.res.Resources
import com.usagelimits.R
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.StatusIcon
import com.usagelimits.core.model.StatusIconContent
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.percentSideWordRes
import com.usagelimits.ui.text.ResourceCountdownTexts
import com.usagelimits.ui.text.percentFigure
import com.usagelimits.ui.text.windowLabelRes

/**
 * The words of a status notification, handed to [StatusIconPublisher] the way
 * [com.usagelimits.core.notifications.NotificationTexts] is handed to the alerts.
 *
 * A shade row leads with the figure or state; its detail line names the window and next reset.
 * The figure is the presentation's token, never the icon's shortened number, so
 * "<1%", ">99%" and "100%" are said as they are. An underlined icon adds that the reading may be
 * out of date; the signs that replace a figure are named ("Exhausted", "No percentage reported").
 */
interface StatusIconTexts {
    fun channelName(): String
    fun channelDescription(): String

    /** The title of an account that needs signing in again. */
    fun reconnectRequired(): String

    /** The title of an account whose last sync failed. */
    fun refreshFailed(): String

    /** The title of an account with a reading, whose icon is [icon] (never an error). */
    fun reading(icon: StatusIcon, presentation: WindowPresentation): String

    /** The window and its next reset, or null when no window is reported. */
    fun windowDetails(headline: UsageWindow?, nowMs: Long): String?

    /** The provider and, if needed to distinguish its accounts, the account label. */
    fun subText(provider: ProviderId, accountLabel: String?): String
}

/** [StatusIconTexts] from `strings.xml`, in the language of the given [resources]. */
class ResourceStatusIconTexts(private val resources: Resources) : StatusIconTexts {
    private val countdown = ResourceCountdownTexts(resources)

    override fun channelName(): String = resources.getString(R.string.status_icon_channel_name)

    override fun channelDescription(): String = resources.getString(R.string.status_icon_channel_description)

    override fun reconnectRequired(): String = resources.getString(R.string.status_icon_text_reconnect)

    override fun refreshFailed(): String = resources.getString(R.string.status_icon_text_refresh_failed)

    override fun reading(icon: StatusIcon, presentation: WindowPresentation): String =
        when (icon.content) {
            StatusIconContent.Exhausted -> resources.getString(R.string.severity_exhausted)
            StatusIconContent.Unknown -> resources.getString(R.string.status_icon_text_unknown)
            else -> resources.getString(
                if (icon.outdated) R.string.status_icon_text_figure_outdated else R.string.status_icon_text_figure,
                percentFigure(presentation.token, resources),
                resources.getString(percentSideWordRes(presentation.side)),
            )
        }

    override fun subText(provider: ProviderId, accountLabel: String?): String =
        if (accountLabel == null) provider.displayName else "${provider.displayName} · $accountLabel"

    override fun windowDetails(headline: UsageWindow?, nowMs: Long): String? {
        if (headline == null) return null
        val label = windowLabelRes(headline.label, headline.category, headline.scope)?.let(resources::getString) ?: headline.label
        val reset = Countdown.absoluteResetLabel(headline.resetAt, nowMs, locale = resources.configuration.locales[0], texts = countdown)
        return listOfNotNull(label, reset).joinToString(" · ")
    }
}
