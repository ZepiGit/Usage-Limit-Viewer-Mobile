package com.usagelimits.core.model

/**
 * What a status icon shows beside its provider glyph. Exactly one of these, never a string or a
 * pixel: the renderer decides how each one looks.
 */
sealed interface StatusIconContent {
    /** The account needs signing in again, or its reading failed: "!" instead of an old number. */
    data object Error : StatusIconContent

    /** The headline is exhausted, in either display mode: its own sign instead of a number. */
    data object Exhausted : StatusIconContent

    /** No headline, or no figure: a dash, never 0 or 100. */
    data object Unknown : StatusIconContent

    /** A figure of 100 on a window that is not exhausted: the glyph alone. */
    data object None : StatusIconContent

    /** A number from 0 to 99, written without a leading zero. */
    data class Figure(val value: Int) : StatusIconContent {
        init {
            require(value in 0..99) { "a status icon figure is 0 to 99, not $value" }
        }
    }
}

/** One status icon: its [content], and whether it is underlined because it may be out of date. */
data class StatusIcon(val content: StatusIconContent, val outdated: Boolean)

/**
 * The status icon for an account whose [headline] presentation is this (null without a usage
 * window), read with [validity], for an account that does or does not [needsReauthentication].
 *
 * Decided in the order the shared vectors state: error, exhausted, unknown, the glyph alone, the
 * figure. The figure is deliberately lossy ("<1" is 0, ">99" is 99, 100 is no number at all), so
 * anything said in words uses the presentation's own token, never this value. [validity] is
 * passed in rather than read from the fill state, because the fill state folds validity and
 * severity together and the icon needs them apart. `StatusIconVectorsTest` proves this against
 * `shared/test-vectors/status-icon.json`, which the Swift twin runs too.
 */
fun statusIcon(headline: WindowPresentation?, validity: ReadingValidity, needsReauthentication: Boolean): StatusIcon {
    val content = when {
        needsReauthentication || validity == ReadingValidity.ERROR -> StatusIconContent.Error
        headline == null -> StatusIconContent.Unknown
        headline.exhausted -> StatusIconContent.Exhausted
        else -> when (headline.token) {
            PERCENT_TOKEN_UNDER_ONE -> StatusIconContent.Figure(0)
            PERCENT_TOKEN_OVER_NINETY_NINE -> StatusIconContent.Figure(99)
            "100" -> StatusIconContent.None
            // The unknown token, and anything else that is not a figure, is the dash.
            else -> headline.token.toIntOrNull()?.takeIf { it in 0..99 }
                ?.let(StatusIconContent::Figure) ?: StatusIconContent.Unknown
        }
    }
    val aged = validity == ReadingValidity.STALE || validity == ReadingValidity.RESET_DUE
    val outdated = aged && (content is StatusIconContent.Figure || content == StatusIconContent.None)
    return StatusIcon(content, outdated)
}

/** How many accounts can be pinned at once. */
const val MAX_PINNED_ACCOUNTS = 3

/**
 * The effectively pinned account ids, in the app's account order.
 *
 * Nothing while the feature is not [enabled]. Otherwise the [pinned] ids that name one of the
 * [usageAccountIds] (given in the app's account order), ordered by that list rather than by pin
 * order, at most [MAX_PINNED_ACCOUNTS]. A credit account never appears among usage accounts, so
 * its id cannot qualify; an id whose account is gone is simply not found. Settings refuses a
 * fourth pin; the cut here is a defence against a stored list that holds one anyway.
 * `PinnedAccountsVectorsTest` proves this against `shared/test-vectors/pinned-accounts.json`.
 */
fun pinnedAccountIds(enabled: Boolean, pinned: List<String>, usageAccountIds: List<String>): List<String> {
    if (!enabled) return emptyList()
    val wanted = pinned.toHashSet()
    return usageAccountIds.filter { it in wanted }.distinct().take(MAX_PINNED_ACCOUNTS)
}
