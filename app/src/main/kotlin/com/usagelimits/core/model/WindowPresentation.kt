package com.usagelimits.core.model

/**
 * Whether a reading can be trusted as current.
 *
 * STALE is a reading too old to trust, RESET_DUE one whose window has already ended, ERROR one the
 * account failed to deliver. They are separate because they draw differently: the first two keep
 * their figure under a grey fill, the last draws no fill at all.
 */
enum class ReadingValidity { CURRENT, STALE, RESET_DUE, ERROR }

/** Whether and how a fill is painted; the colour belongs to each surface's own palette. */
enum class FillState { DATA, STALE, NO_FILL }

/**
 * Everything a surface needs to present one usage window, with no strings and no colours.
 *
 * Every surface presents a usage window through this one value, never through its own copy of the
 * rules, so the figure, the fill and the exhausted state cannot drift apart between surfaces. Words,
 * the percent sign and palettes stay with each surface.
 */
data class WindowPresentation(
    /** The figure the window shows in [side]; [PERCENT_TOKEN_UNKNOWN] when there is none. */
    val token: String,
    /** The mode the figure counts, so a surface can pick its word without re-reading the setting. */
    val side: PercentDisplay,
    /** 0.0 to 1.0, or null for a bare track: a figure that is missing or out of range never fills. */
    val fill: Double?,
    val fillState: FillState,
    /** The provider's verdict from the raw severity, never an aged or display severity. */
    val exhausted: Boolean,
) {
    val hasFigure: Boolean get() = token != PERCENT_TOKEN_UNKNOWN

    companion object {
        /** What a surface shows for a window that is not there: a dash and no fill, never exhausted. */
        fun absent(mode: PercentDisplay) = WindowPresentation(
            token = PERCENT_TOKEN_UNKNOWN,
            side = mode,
            fill = null,
            fillState = FillState.NO_FILL,
            exhausted = false,
        )
    }
}

/**
 * Presents a window from its [remaining] figure and its raw [severity].
 *
 * [severity] is the raw one, never an aged or display severity, because [WindowPresentation.exhausted]
 * is the provider's verdict. The fill is [fillFraction] kept only when finite and within 0 to 1:
 * a missing or out-of-range figure never fills, matching the dash it reads as.
 */
fun windowPresentation(
    remaining: Double?,
    severity: Severity,
    validity: ReadingValidity,
    mode: PercentDisplay,
): WindowPresentation {
    val exhausted = severity == Severity.EXHAUSTED
    val fill = fillFraction(remaining, exhausted, mode)?.takeIf { it.isFinite() && it in 0.0..1.0 }
    return WindowPresentation(
        token = percentToken(remaining, exhausted, mode),
        side = mode,
        fill = fill,
        fillState = fillState(validity, severity, fill, mode),
        exhausted = exhausted,
    )
}

/**
 * How the fill of a window with this [severity] and [fill] is painted, checked in this order.
 *
 * Validity outranks severity: a reading that is stale or past its reset is grey whatever it says,
 * and a failed one draws nothing. Only a current reading lets severity decide, where an exhausted
 * window has no fill ink except in USED, where it is the full bar and must not sit bare beside its
 * own "100" ([usedFillOverridesMissingInk]).
 */
fun fillState(validity: ReadingValidity, severity: Severity, fill: Double?, mode: PercentDisplay): FillState =
    when (validity) {
        ReadingValidity.STALE, ReadingValidity.RESET_DUE -> FillState.STALE
        ReadingValidity.ERROR -> FillState.NO_FILL
        ReadingValidity.CURRENT -> when (severity) {
            Severity.STALE -> FillState.STALE
            Severity.ERROR, Severity.UNKNOWN -> FillState.NO_FILL
            Severity.EXHAUSTED ->
                if (usedFillOverridesMissingInk(mode, fill)) FillState.DATA else FillState.NO_FILL
            else -> FillState.DATA
        }
    }

/**
 * [windowPresentation] for a whole window, or [WindowPresentation.absent] when there is none.
 *
 * Nullable on purpose: the lead strip and the ring widgets hold an optional window, and handling
 * the missing one here keeps their dash identical to every other surface's.
 */
fun UsageWindow?.presentation(validity: ReadingValidity, mode: PercentDisplay): WindowPresentation =
    if (this == null) WindowPresentation.absent(mode)
    else windowPresentation(remainingPercent, severity, validity, mode)

/**
 * The validity an app row gives this window.
 *
 * [stale] covers the whole account being old or failed, so it also carries a failed account folded
 * into grey, and outranks a passed reset. Without a [fetchedAt] there is nothing to compare the
 * reset against, which is how the lead strip calls it: its view model already marks a lead whose
 * window has reset as stale.
 */
fun UsageWindow.readingValidity(stale: Boolean, fetchedAt: Long?, nowMs: Long): ReadingValidity = when {
    stale -> ReadingValidity.STALE
    fetchedAt != null && resetElapsedAt(fetchedAt, nowMs) -> ReadingValidity.RESET_DUE
    else -> ReadingValidity.CURRENT
}
