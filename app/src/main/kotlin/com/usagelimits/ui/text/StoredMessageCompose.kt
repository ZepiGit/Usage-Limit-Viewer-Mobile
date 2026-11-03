package com.usagelimits.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** [storedMessageText] for a composable; reading `LocalConfiguration` recomposes it on a language change. */
@Composable
@ReadOnlyComposable
fun storedMessageText(message: String): String {
    LocalConfiguration.current
    return storedMessageText(LocalContext.current.resources, message)
}
