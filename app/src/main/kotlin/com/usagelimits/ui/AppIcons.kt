package com.usagelimits.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Material glyphs this app uses that the CORE icon artifact does not ship.
 *
 * Drawn from their published 24dp path data rather than pulled from `material-icons-extended`,
 * which carries several thousand icons for the sake of these few and was the largest single
 * dependency in the debug APK — the build a tester actually installs. R8 strips the unused
 * ones from a release, so this is a build-and-debug-size saving, not a shipped one, and it is
 * stated as such.
 *
 * Same viewport, fill and tint behaviour as the library's own: `Icon(...)` applies
 * `LocalContentColor` over the black fill exactly as it does for `Icons.Default.*`.
 */
object AppIcons {
    val DragHandle: ImageVector by lazy { icon("DragHandle", "M3 8h18v2H3zm0 6h18v2H3z") }
    /** Material "chevron_right". */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z")
    }

    /** Material "account_balance_wallet". */
    val Wallet: ImageVector by lazy {
        icon("Wallet", "M21 18v1c0 1.1-.9 2-2 2H5c-1.1 0-2-.9-2-2V5c0-1.1.9-2 2-2h14c1.1 0 2 .9 2 2v1h-9c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h9zm-9-2h10V8H12v8zm3-2.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z")
    }

    /** Material "schedule". */
    val Schedule: ImageVector by lazy {
        icon(
            "Schedule",
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2z" +
                "M12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 " +
                "3.15.75-1.23-4.5-2.67z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
