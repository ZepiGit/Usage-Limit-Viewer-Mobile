package com.usagelimits.ui

import androidx.annotation.StringRes
import com.usagelimits.R
import com.usagelimits.core.model.PercentDisplay

/*
 * The words that say which side of a quota a figure counts (R2). Every surface — app screens,
 * widgets, widget configuration — takes them from here, so a bare figure can never be captioned
 * differently on two screens.
 *
 * Each word has two forms: a `…Res` function giving the string resource a surface reads in the
 * user's language, and a pure English function that is its reference. Only the `…Res` form is read
 * on screen. The English form stays because PercentResourcesEnglishTest holds it equal to the
 * resource under `en` and PercentWordsTest pins its wording; without it nothing would notice a
 * resource drifting away from the words every upgrade must keep.
 */

/** The English reference of [percentSideWordRes]: "left" / "used". Nothing on screen reads it. */
fun percentSideWord(mode: PercentDisplay): String = when (mode) {
    PercentDisplay.LEFT -> "left"
    PercentDisplay.USED -> "used"
}

/**
 * The English reference of [percentSideCaptionRes], [percentSideWord] as a caption or leading label:
 * "Left" / "Used". Nothing on screen reads it.
 */
fun percentSideCaption(mode: PercentDisplay): String = when (mode) {
    PercentDisplay.LEFT -> "Left"
    PercentDisplay.USED -> "Used"
}

/**
 * The English reference of [percentSpokenClauseRes], the clause a spoken description ends its figure
 * with: "remaining" / "used". Nothing on screen reads it.
 *
 * It differs from [percentSideWord] in LEFT on purpose. The visible "left" is new with the display
 * mode, but the accessibility sentences already said "remaining" before it, and an upgrade must not
 * change what a screen reader announces for users who never touch the setting. USED has no history
 * to preserve, so its visible and spoken words agree.
 */
fun percentSpokenClause(mode: PercentDisplay): String = when (mode) {
    PercentDisplay.LEFT -> "remaining"
    PercentDisplay.USED -> "used"
}

/**
 * The English reference of [percentFlipLabelRes], the action label of the tap that switches away
 * from [mode]: it names where the tap goes. Nothing on screen reads it.
 */
fun percentFlipLabel(mode: PercentDisplay): String = when (mode) {
    PercentDisplay.LEFT -> "Show percentage used"
    PercentDisplay.USED -> "Show percentage left"
}

/** [percentSideWord] in the user's language. */
@StringRes
fun percentSideWordRes(mode: PercentDisplay): Int = when (mode) {
    PercentDisplay.LEFT -> R.string.percent_side_left
    PercentDisplay.USED -> R.string.percent_side_used
}

/** [percentSideCaption] in the user's language. */
@StringRes
fun percentSideCaptionRes(mode: PercentDisplay): Int = when (mode) {
    PercentDisplay.LEFT -> R.string.percent_caption_left
    PercentDisplay.USED -> R.string.percent_caption_used
}

/** [percentSpokenClause] in the user's language. */
@StringRes
fun percentSpokenClauseRes(mode: PercentDisplay): Int = when (mode) {
    PercentDisplay.LEFT -> R.string.percent_spoken_left
    PercentDisplay.USED -> R.string.percent_spoken_used
}

/** [percentFlipLabel] in the user's language. */
@StringRes
fun percentFlipLabelRes(mode: PercentDisplay): Int = when (mode) {
    PercentDisplay.LEFT -> R.string.percent_flip_to_used
    PercentDisplay.USED -> R.string.percent_flip_to_left
}
