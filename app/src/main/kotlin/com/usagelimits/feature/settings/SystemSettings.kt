package com.usagelimits.feature.settings

import android.content.Context
import android.content.Intent

/** Some devices omit a settings screen; try its app-details fallback before giving up. */
internal fun Context.startFirstAvailable(vararg intents: Intent) {
    for (intent in intents) {
        if (runCatching { startActivity(intent) }.isSuccess) return
    }
}
