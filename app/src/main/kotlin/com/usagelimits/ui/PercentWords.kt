package com.usagelimits.ui

import com.usagelimits.core.model.PercentDisplay

/*
 * The words that say which side of a quota a figure counts (R2). Every surface — app screens,
 * widgets, widget configuration — takes them from here, so a bare figure can never be captioned
 * differently on two screens. English until the strings move to resources (R4).
 */

/** The side word beside a figure: "left" / "used". */
fun percentSideWord(mode: PercentDisplay): String = when (mode) {
    PercentDisplay.LEFT -> "left"
    PercentDisplay.USED -> "used"
}

/** [percentSideWord] as a caption or leading label: "Left" / "Used". */
fun percentSideCaption(mode: PercentDisplay): String = when (mode) {
    PercentDisplay.LEFT -> "Left"
    PercentDisplay.USED -> "Used"
}

/**
 * The clause a spoken description ends its figure with: "remaining" / "used".
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

/** The action label of the tap that switches away from [mode]: it names where the tap goes. */
fun percentFlipLabel(mode: PercentDisplay): String = when (mode) {
    PercentDisplay.LEFT -> "Show percentage used"
    PercentDisplay.USED -> "Show percentage left"
}
