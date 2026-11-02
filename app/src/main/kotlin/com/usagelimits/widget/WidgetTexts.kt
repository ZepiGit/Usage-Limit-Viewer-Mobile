package com.usagelimits.widget

import android.content.res.Resources
import androidx.annotation.StringRes
import com.usagelimits.R
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.CountdownTexts
import com.usagelimits.core.time.EnglishCountdownTexts
import com.usagelimits.ui.text.ResourceCountdownTexts
import com.usagelimits.ui.text.storedMessageText
import com.usagelimits.ui.text.windowLabelRes

/*
 * The words of the widgets. Glance has no `stringResource`, so a widget takes the [Resources] of
 * the context it is composed in and asks here; the language is whatever those resources carry at
 * the moment of composition.
 */

/** "N accounts" for several, "Account" for one, as the compact widget's status tile is labelled. */
internal fun WidgetSnapshot.statusLabel(resources: Resources): String =
    if (accountCount > 1) resources.getQuantityString(R.plurals.widget_accounts_count, accountCount, accountCount)
    else resources.getString(R.string.widget_account)

/** [emptyProvider] as a sentence, or null when the widget has no specific reason to be empty. */
internal fun WidgetSnapshot.emptyText(resources: Resources): String? =
    emptyProvider?.let { resources.getString(R.string.widget_no_provider_account, it.displayName) }

/** The row's window label: the app's own horizon name translated, a provider's label as it came. */
internal fun WidgetRow.labelText(resources: Resources): String =
    windowLabelRes(label, category, scope)?.let(resources::getString) ?: label

/** The one-word status a compact tile shows beside its dot. */
@StringRes
internal fun statusWordRes(severity: Severity): Int = when (severity) {
    Severity.HEALTHY -> R.string.widget_status_ok
    Severity.MEDIUM -> R.string.widget_status_fair
    Severity.LOW -> R.string.widget_status_low
    Severity.EXHAUSTED -> R.string.widget_status_out
    Severity.STALE -> R.string.widget_status_stale
    Severity.ERROR -> R.string.widget_status_error
    Severity.UNKNOWN -> R.string.widget_status_unknown
    Severity.LIMITED -> R.string.widget_status_limited
}

/**
 * The words of the Credits widget beyond its countdown vocabulary.
 *
 * A pure interface with an English default, like [CountdownTexts] it extends, so the status lines
 * stay testable without resources.
 */
internal interface CreditWidgetTexts : CountdownTexts {
    fun couldNotBeRead(): String
    fun refreshFailed(reason: String): String
    fun notReadYet(): String
    fun outOfDate(asOf: String): String
    fun spentThisMonth(amount: String): String

    /** A failure message as the provider layer stored it, in the language of the screen. */
    fun stored(message: String): String
}

internal object EnglishCreditWidgetTexts : CreditWidgetTexts, CountdownTexts by EnglishCountdownTexts {
    override fun couldNotBeRead() = "Could not be read"
    override fun refreshFailed(reason: String) = "Refresh failed: $reason"
    override fun notReadYet() = "Not read yet"
    override fun outOfDate(asOf: String) = "Out of date · $asOf"
    override fun spentThisMonth(amount: String) = "$amount spent this month"
    override fun stored(message: String) = message
}

internal class ResourceCreditWidgetTexts(private val resources: Resources) :
    CreditWidgetTexts, CountdownTexts by ResourceCountdownTexts(resources) {
    override fun couldNotBeRead(): String = resources.getString(R.string.widget_credits_unreadable)
    override fun refreshFailed(reason: String): String = resources.getString(R.string.widget_credits_refresh_failed_reason, reason)
    override fun notReadYet(): String = resources.getString(R.string.widget_credits_not_read)
    override fun outOfDate(asOf: String): String = resources.getString(R.string.widget_credits_out_of_date, asOf)
    override fun spentThisMonth(amount: String): String = resources.getString(R.string.credit_spent_this_month, amount)
    override fun stored(message: String): String = storedMessageText(resources, message)
}
