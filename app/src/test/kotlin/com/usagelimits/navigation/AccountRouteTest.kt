package com.usagelimits.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a status icon's or a widget row's `accountId` leads, by what the account store has said.
 * The cold launch is the case before the store's first answer: the link waits, it does not call
 * the account missing. An alert without an id has nothing to route and opens Overview as it is.
 */
class AccountRouteTest {

    @Test fun `a cold link waits for the store's first answer`() {
        assertNull(AccountRoute.resolve("a", loadedIds = null, storageError = false))
    }

    @Test fun `an account in the store opens its detail, whatever filter or tab is showing`() {
        assertEquals(AccountRoute.Detail("a"), AccountRoute.resolve("a", loadedIds = listOf("b", "a"), storageError = false))
    }

    @Test fun `an account the store does not hold is unavailable, not ignored`() {
        assertEquals(AccountRoute.Unavailable("gone"), AccountRoute.resolve("gone", loadedIds = listOf("a"), storageError = false))
        assertEquals(AccountRoute.Unavailable("gone"), AccountRoute.resolve("gone", loadedIds = emptyList(), storageError = false))
    }

    @Test fun `a store that could not be read is said to be unreadable, not that the account is gone`() {
        assertEquals(AccountRoute.Unreadable("a"), AccountRoute.resolve("a", loadedIds = null, storageError = true))
    }

    @Test fun `no id routes nowhere`() {
        assertNull(AccountRoute.resolve(null, loadedIds = listOf("a"), storageError = false))
        assertNull(AccountRoute.resolve("", loadedIds = listOf("a"), storageError = false))
    }
}
