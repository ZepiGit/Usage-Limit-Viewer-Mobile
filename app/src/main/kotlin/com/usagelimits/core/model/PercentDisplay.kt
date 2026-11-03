package com.usagelimits.core.model

import kotlin.math.roundToInt

/**
 * Which side of a quota the figures count: what is left, or what has been used.
 *
 * LEFT is the historical reading and stays the default everywhere, so an upgrade changes nothing
 * until the user asks for it. USED is 100 − LEFT, derived from the same clamped remaining figure
 * rather than from the raw [UsageWindow.usedPercent], so both modes describe the same window and
 * sum to 100 before rounding.
 */
enum class PercentDisplay {
    LEFT,
    USED;

    /**
     * The mode a surface actually shows when it may carry its own choice on top of the app's.
     *
     * A null [override] follows the app mode, so a widget that never picked one keeps tracking the
     * setting instead of freezing the value it was placed under. Kept pure and in the model so
     * the widget read site, the config preview and the iOS twin resolve identically.
     */
    fun resolved(override: PercentDisplay?): PercentDisplay = override ?: this

    companion object {
        /** Lenient read: an unknown or missing stored name falls back to the historical default. */
        fun fromNameOrNull(name: String?): PercentDisplay? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The figure a surface shows in [mode], derived from the already-clamped [remaining] figure.
 *
 * Null stays null: a window the provider gave no usable percentage for has no figure in either
 * mode. Exhaustion deliberately does not enter here — it is a labelling concern, handled by
 * [percentToken]; keeping this function pure lets both modes be checked against each other.
 */
fun displayPercent(remaining: Double?, mode: PercentDisplay): Double? = when (mode) {
    PercentDisplay.LEFT -> remaining
    PercentDisplay.USED -> remaining?.let { 100.0 - it }
}

/**
 * The fraction a bar or ring fills in [mode].
 *
 * USED flips what full means: a window the provider calls exhausted fills completely, agreeing with
 * its "100" label even when no usable percentage is behind it. A window with no figure fills
 * nothing in either mode — unknown never becomes "100 − 0".
 */
fun fillFraction(remaining: Double?, exhausted: Boolean, mode: PercentDisplay): Double? =
    if (mode == PercentDisplay.USED && exhausted) 1.0
    else displayPercent(remaining, mode)?.div(100.0)

/**
 * Whether a bar or ring whose severity gives it no ink must still be filled, in [mode] with the
 * displayed [fraction].
 *
 * USED makes an exhausted window the full bar, and exhausted severity has no fill ink; without this
 * the one window that is 100% used would sit bare beside its own figure. LEFT never needs it: there
 * a nil-ink severity has a zero or unknown fraction, so the historical bare track stays exact. One
 * predicate so the in-app bars and the widget bars cannot disagree about when the fallback fires.
 */
fun usedFillOverridesMissingInk(mode: PercentDisplay, fraction: Double?): Boolean =
    mode == PercentDisplay.USED && fraction != null && fraction > 0.0

/**
 * Whether this window counts as exhausted for labelling and fill: the provider's verdict, or a
 * remainder of zero. Read from the window's own raw severity, never from a display severity that
 * a stale reading or a passed reset has swapped out, so a stale exhausted window still reads
 * "100" in USED mode while its colour follows the stale grey.
 */
val UsageWindow.isExhausted: Boolean get() = severity == Severity.EXHAUSTED

/** [fillFraction] for a whole window; the raw severity decides "exhausted", never an aged one. */
fun fillFraction(window: UsageWindow?, mode: PercentDisplay): Double? = window?.let {
    fillFraction(it.remainingPercent, it.isExhausted, mode)
}

/** [percentToken] for a whole window; the raw severity decides "exhausted", never an aged one. */
fun percentToken(window: UsageWindow?, mode: PercentDisplay): String =
    percentToken(window?.remainingPercent, window?.isExhausted == true, mode)

/**
 * A figure token with its `%` sign; an unknown figure stays a bare dash.
 *
 * The English reference of [com.usagelimits.ui.text.percentFigure], which PercentResourcesEnglishTest
 * holds equal to it under `en`. It needs no resources, so [percentLabel] uses it; screens take the
 * localized form.
 */
fun withPercentSign(token: String): String =
    if (token == PERCENT_TOKEN_UNKNOWN) token else "$token%"

/** Token values the localized templates key on (the UI composes "73%" / "73 %"). */
const val PERCENT_TOKEN_UNKNOWN = "—"
const val PERCENT_TOKEN_UNDER_ONE = "<1"
const val PERCENT_TOKEN_OVER_NINETY_NINE = ">99"

/** The token an exhausted window labels in USED mode, where the provider's verdict is the figure. */
const val PERCENT_TOKEN_EXHAUSTED = "100"

/**
 * The figure token for a window in [mode]: the number only, no `%` — the UI composes it with a
 * locale template, and tokens (not formatted strings) are the parity contract between this table
 * and the iOS one.
 *
 * Exhaustion overrides the number: the provider's verdict is the figure, the same precedence
 * the LEFT treatment applies when a blocker with a full-looking percentage stays empty. The check
 * comes first, so an exhausted window with an unusable percentage still reads 100 in USED mode.
 */
fun percentToken(remaining: Double?, exhausted: Boolean, mode: PercentDisplay): String {
    if (mode == PercentDisplay.USED && exhausted) return PERCENT_TOKEN_EXHAUSTED
    val figure = displayPercent(remaining, mode)
    return when {
        figure == null || !figure.isFinite() || figure !in 0.0..100.0 -> PERCENT_TOKEN_UNKNOWN
        figure > 0.0 && figure < 1.0 -> PERCENT_TOKEN_UNDER_ONE
        figure < 100.0 && figure.roundToInt() == 100 -> PERCENT_TOKEN_OVER_NINETY_NINE
        else -> figure.roundToInt().toString()
    }
}
