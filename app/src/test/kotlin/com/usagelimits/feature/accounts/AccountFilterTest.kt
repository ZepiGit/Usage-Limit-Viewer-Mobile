package com.usagelimits.feature.accounts

import com.usagelimits.core.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountFilterTest {
    @Test fun `attention clears the selected provider`() {
        assertEquals(AccountFilter(onlyProblems = true), AccountFilter(provider = ProviderId.CLAUDE).toggleProblems())
    }

    @Test fun `selecting a provider clears attention`() {
        assertEquals(AccountFilter(provider = ProviderId.CODEX), AccountFilter(onlyProblems = true).toggleProvider(ProviderId.CODEX))
    }

    @Test fun `tapping the selected filter returns to all`() {
        assertEquals(AccountFilter(), AccountFilter(onlyProblems = true).toggleProblems())
        assertEquals(AccountFilter(), AccountFilter(provider = ProviderId.CLAUDE).toggleProvider(ProviderId.CLAUDE))
    }
}
