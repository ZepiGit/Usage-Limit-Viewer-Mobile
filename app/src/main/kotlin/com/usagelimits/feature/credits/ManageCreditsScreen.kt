package com.usagelimits.feature.credits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.feature.overview.AddCreditAction
import com.usagelimits.feature.overview.creditCards
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageMotion

@Composable
internal fun ManageCreditsScreen(
    credits: List<CreditEntry>,
    nowMs: Long,
    staleAfterMs: Long,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val motion = UsageMotion.current
    LazyColumn(
        Modifier.fillMaxSize().background(UsageColors.Ground),
        contentPadding = PaddingValues(16.dp),
    ) {
        item(key = "title") {
            ScreenTitle(stringResource(R.string.credit_manage), action = { AddCreditAction(onAdd) })
        }
        creditCards(credits, nowMs, staleAfterMs, motion, onSelect, onRetry)
    }
}
