package com.usagelimits.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette: an achromatic ledger on Claude's warm neutrals, with Terracotta as the one data hue.
 *
 * Roles, not hues, drive every call site:
 *
 *  - **Chrome is ink.** Buttons, tabs, switches, links, focus and spinners use [Ink] and the greys;
 *    none of them is Terracotta.
 *  - **[Data] is Terracotta.** It fills usage bars and the "next reset" element, and nothing
 *    else, so the brand colour always means "this is the quota".
 *  - **Severity is a dot.** Green, amber and red appear as an 8dp mark or as the colour of one
 *    figure, never as a bar fill, a tinted card or a pill.
 *
 * Colour is never the only channel: every status also carries a word and a bar length.
 */
object UsageColors {

    // Surfaces: warm near-black, lifted by tone steps rather than borders or shadows.
    val Ground = Color(0xFF0F0F0E)
    val Surface = Color(0xFF1A1918)
    /** Selected segment, active nav pill, dialogs, skeleton bars. */
    val Raised = Color(0xFF242322)
    /** 1px dividers inside cards, the nav-bar top line, and the track under bars. */
    val Rule = Color(0xFF2E2D2B)
    /** 1px border of outlined buttons and fields: brighter than [Rule] because it is interactive. */
    val Control = Color(0xFF4A4844)

    // Text: warm off-white through warm greys.
    val Ink = Color(0xFFF0EEE6)
    val Muted = Color(0xFFB4B2A9)
    /** Stamps and meta on [Ground] or [Surface] only: 4.41:1 on [Raised] fails AA. */
    val Faint = Color(0xFF8A887F)
    val OnInk = Color(0xFF0F0F0E)

    /** Claude's terracotta, as the one data hue: usage bar fill and the next-reset element. */
    val Data = Color(0xFFD97757)

    // Severity: dots and the occasional figure.
    val Positive = Color(0xFF6FBF73)
    val Warning = Color(0xFFE0A33E)
    /** A graphic mark only (4.56:1 on [Surface]). Words use [NegativeText]. */
    val Negative = Color(0xFFD9584F)
    val NegativeText = Color(0xFFE8756B)
    /** Stale and unknown: the dot and the bar fill. */
    val StaleMark = Faint
}
