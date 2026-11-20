package com.usagelimits.core.model

/**
 * The automatic order Overview and the All accounts widget share (ADR 0004).
 *
 * Expired sign-ins lead, because they are what the user can fix. Within each group the account
 * headline at [nowMs] ranks by its unrounded percentage left, lowest first; a headline the
 * provider declares exhausted counts as 0 whatever its figure, and a headline without a figure,
 * an empty reading and no reading at all follow every known figure. Ties go to the account added
 * first, then to the id compared by Unicode scalar values, so the order never depends on the
 * stored arrangement, the display mode or the locale.
 *
 * Only these two surfaces use it. Pins, Provider scope and the export keep the stored order and
 * Custom keeps its saved ids, so they do not shuffle as readings change.
 * `OverviewOrderVectorsTest` proves this against `shared/test-vectors/overview-order.json`,
 * which the Swift suite reads too.
 */
fun <T> List<T>.inOverviewOrder(
    nowMs: Long,
    account: (T) -> ProviderAccount,
    snapshot: (T) -> UsageSnapshot?,
): List<T> = map { item -> OverviewRank(item, account(item), snapshot(item), nowMs) }
    .sortedWith(OverviewRank.ORDER)
    .map { it.item }

private class OverviewRank<T>(val item: T, account: ProviderAccount, snapshot: UsageSnapshot?, nowMs: Long) {
    val signInExpired = snapshot?.connectionStatus == ConnectionStatus.RECONNECT_REQUIRED
    val left: Double? = snapshot?.headlineAt(nowMs)?.let { headline ->
        if (headline.severity == Severity.EXHAUSTED) 0.0 else headline.remainingPercent
    }
    val createdAt = account.createdAt
    val id = account.localId

    companion object {
        val ORDER: Comparator<OverviewRank<*>> = compareBy<OverviewRank<*>> { !it.signInExpired }
            .thenBy(nullsLast()) { it.left }
            .thenBy { it.createdAt }
            .thenComparator { a, b -> compareUnicodeScalars(a.id, b.id) }
    }
}

/**
 * Compares by Unicode scalar values, as Swift's `unicodeScalars` do. `String.compareTo` compares
 * UTF-16 units, which puts a character beyond U+FFFF before U+FF5E.
 */
internal fun compareUnicodeScalars(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) return x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    return (i < a.length).compareTo(j < b.length)
}
