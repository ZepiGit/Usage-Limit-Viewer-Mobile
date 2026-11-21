package com.usagelimits.core.settings

/** Names are saved in DataStore; the choice is independent of Android's night mode. */
enum class AppTheme {
    WARM_DARK,
    PURE_BLACK,
    COOL_GRAPHITE,
    LIGHT_PAPER;

    val isLight: Boolean get() = this == LIGHT_PAPER

    companion object {
        internal fun fromStored(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: PURE_BLACK
    }
}
