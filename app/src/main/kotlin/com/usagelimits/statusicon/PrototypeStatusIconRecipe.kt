package com.usagelimits.statusicon

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.usagelimits.BuildConfig

/** Throwaway branch controls; quota readings are not semantically eligible Live Updates. */
internal enum class PrototypeStatusIconRecipe(
    val value: String,
    val channel: String,
    val grouped: Boolean = true,
    val promoted: Boolean = false,
    val legacyColorized: Boolean = false,
) {
    LOW("low", StatusIconPublisher.CHANNEL_ID),
    DEFAULT("default", "prototype_status_default"),
    PROMOTED("promoted", "prototype_status_promoted", promoted = true),
    PROMOTED_UNGROUPED("promoted-ungrouped", "prototype_status_promoted", grouped = false, promoted = true),
    PROMOTED16_COLORIZED("promoted16-colorized", "prototype_status_promoted16_colorized", promoted = true, legacyColorized = true),
    PROMOTED16_COLORIZED_UNGROUPED("promoted16-colorized-ungrouped", "prototype_status_promoted16_colorized", grouped = false, promoted = true, legacyColorized = true);

    val importance: Int get() = if (this == LOW) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT

    fun store(context: Context) {
        check(BuildConfig.DEBUG) { "Prototype recipes require a debug build" }
        check(!legacyColorized || Build.VERSION.SDK_INT == 36) { "Legacy colorized controls require API 36" }
        preferences(context).edit(commit = true) { putString(KEY, value) }
    }

    companion object {
        val channels: Set<String> = entries.map { it.channel }.toSet()

        fun read(context: Context): PrototypeStatusIconRecipe {
            if (!BuildConfig.DEBUG) return LOW
            val value = preferences(context).getString(KEY, null)
            return entries.firstOrNull { it.value == value } ?: LOW
        }

        private const val KEY = "prototype_recipe"
        private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("usage_limits_status_icons", Context.MODE_PRIVATE)
    }
}
