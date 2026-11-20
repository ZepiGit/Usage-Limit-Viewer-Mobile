package com.usagelimits.navigation

/**
 * Where an incoming `accountId` (a status icon, a widget row) leads, once the account store has
 * answered. Nothing while it has not: on a cold launch the link waits for the first read rather
 * than calling a not-yet-loaded account missing.
 */
internal sealed interface AccountRoute {
    /** The account exists: open its detail over Overview. */
    data class Detail(val accountId: String) : AccountRoute

    /** The store answered and the account is not in it: its detail says so, with a way back. */
    data class Unavailable(val accountId: String) : AccountRoute

    /** The store could not be read: the detail says that, not that the account is gone. */
    data class Unreadable(val accountId: String) : AccountRoute

    companion object {
        /**
         * The route for [requested] given what the store has said: [loadedIds] is null until its
         * first answer, and [storageError] is true when that answer was a failure. The caller
         * consumes the request once it gets a route, so a later change does not open it again.
         */
        fun resolve(requested: String?, loadedIds: Collection<String>?, storageError: Boolean): AccountRoute? = when {
            requested.isNullOrEmpty() -> null
            storageError -> Unreadable(requested)
            loadedIds == null -> null
            requested in loadedIds -> Detail(requested)
            else -> Unavailable(requested)
        }
    }
}
