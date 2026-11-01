package com.usagelimits.core.notifications

import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.time.CountdownTexts
import com.usagelimits.core.time.EnglishCountdownTexts

/**
 * The sentences [NotificationEvaluator] builds, handed in so the evaluator stays free of Android.
 *
 * The evaluator decides WHAT is true and when; this decides how it is said. Injecting it, rather
 * than returning tokens for the caller to assemble, keeps one code path for every language and
 * lets one set of inputs assert the wording in each.
 *
 * Every function takes whole pieces (an account name, a window label, a count) instead of
 * returning a fragment, because word order and plural forms differ between languages. Names and
 * labels are data — the user's own and the provider's — and are inserted untranslated.
 */
interface NotificationTexts {
    /** Formats the deadline inside [creditExpiring], in the same language as the sentence. */
    val countdown: CountdownTexts

    /**
     * The first tier. The threshold itself stays on remaining quota; only the wording follows
     * [mode], so "less than 20% remaining" and "more than 80% used" are one crossing.
     */
    fun warning(name: String, label: String, mode: PercentDisplay): String

    fun critical(name: String, label: String, mode: PercentDisplay): String

    fun exhausted(name: String, label: String): String

    fun resetApproaching(name: String, label: String, minutes: Long): String

    /** [duration] is already formatted with [countdown]. */
    fun creditExpiring(name: String, duration: String): String

    fun creditsAvailable(name: String, count: Int): String

    fun needsReconnect(name: String): String
}

/** The wording the app shipped with, kept as the default so context-free callers are unchanged. */
object EnglishNotificationTexts : NotificationTexts {
    override val countdown: CountdownTexts = EnglishCountdownTexts

    override fun warning(name: String, label: String, mode: PercentDisplay) =
        if (mode == PercentDisplay.USED) "$name · $label: more than 80% used"
        else "$name · $label: less than 20% remaining"

    override fun critical(name: String, label: String, mode: PercentDisplay) =
        if (mode == PercentDisplay.USED) "$name · $label: more than 90% used"
        else "$name · $label: less than 10% remaining"

    override fun exhausted(name: String, label: String) = "$name · $label exhausted"

    override fun resetApproaching(name: String, label: String, minutes: Long): String {
        val unit = if (minutes == 1L) "minute" else "minutes"
        return "$name · $label resets in about $minutes $unit"
    }

    override fun creditExpiring(name: String, duration: String) =
        "$name · a reset credit expires in $duration"

    override fun creditsAvailable(name: String, count: Int): String {
        val noun = if (count == 1) "reset credit" else "reset credits"
        return "$name · $count $noun available"
    }

    override fun needsReconnect(name: String) = "$name needs to be reconnected"
}
