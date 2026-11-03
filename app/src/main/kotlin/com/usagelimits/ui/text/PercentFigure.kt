package com.usagelimits.ui.text

import android.content.res.Resources
import com.usagelimits.R
import com.usagelimits.core.model.PERCENT_TOKEN_UNKNOWN

/**
 * A figure token with its percent sign, laid out by the language's template: "73%" in English,
 * "73 %" in German.
 *
 * The locale-aware form of [com.usagelimits.core.model.withPercentSign], the English reference that
 * needs no resources. Lives here rather than beside it because the model package is free of
 * Android. An unknown figure is the bare dash in every language, never "— %".
 */
fun percentFigure(token: String, resources: Resources): String =
    if (token == PERCENT_TOKEN_UNKNOWN) token else resources.getString(R.string.percent_figure, token)
