package com.usagelimits.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.usagelimits.core.time.CountdownTexts

/*
 * Composition-side entry points to the resource-backed texts, so a screen asks for words the way
 * `stringResource` does and a change of language recomposes it. Reading `LocalConfiguration` is
 * what registers that dependency: the resources object alone does not notify the composition.
 */

/** [percentFigure] for a composable: "73%" in English, "73 %" in German, the unknown dash bare. */
@Composable
@ReadOnlyComposable
fun percentFigureText(token: String): String {
    LocalConfiguration.current
    return percentFigure(token, LocalContext.current.resources)
}

/** The [CountdownTexts] every [com.usagelimits.core.time.Countdown] call on a screen passes. */
@Composable
fun rememberCountdownTexts(): CountdownTexts {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { ResourceCountdownTexts(context.resources) }
}
