package com.usagelimits.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.usagelimits.R
import com.usagelimits.core.model.ProviderId

/** The look of a logo variant. These are words of the app and are translated; the brand is not. */
enum class IconStyle(@StringRes val labelRes: Int) {
    COLOR(R.string.icon_style_color),
    MONOCHROME(R.string.icon_style_monochrome),
    WORDMARK(R.string.icon_style_wordmark),
}

/**
 * One logo a provider can show. [brand] is a name and stays as written; [style] is null for the
 * single default variant of a brand, which is labelled by the brand alone.
 */
data class ProviderIconChoice(val id: String, val brand: String, val style: IconStyle?, val drawable: Int) {
    /** "Claude · Color" in English, "Claude · Farbe" in German: the brand, then the style word. */
    fun label(resources: Resources): String =
        style?.let { resources.getString(R.string.icon_label_with_style, brand, resources.getString(it.labelRes)) }
            ?: brand
}

object ProviderIconCatalog {
    fun choices(provider: ProviderId): List<ProviderIconChoice> = when (provider) {
        ProviderId.CLAUDE -> listOf(
            ProviderIconChoice("claudecode-color", "Claude Code", IconStyle.COLOR, R.drawable.icon_claudecode_color),
            ProviderIconChoice("claudecode", "Claude Code", IconStyle.MONOCHROME, R.drawable.icon_claudecode),
            ProviderIconChoice("claudecode-text", "Claude Code", IconStyle.WORDMARK, R.drawable.icon_claudecode_text),
            ProviderIconChoice("claude-color", "Claude", IconStyle.COLOR, R.drawable.icon_claude_color),
            ProviderIconChoice("claude", "Claude", IconStyle.MONOCHROME, R.drawable.icon_claude),
            ProviderIconChoice("claude-text", "Claude", IconStyle.WORDMARK, R.drawable.icon_claude_text),
        )
        ProviderId.CODEX -> listOf(
            ProviderIconChoice("openai", "OpenAI", null, R.drawable.icon_openai),
            ProviderIconChoice("openai-text", "OpenAI", IconStyle.WORDMARK, R.drawable.icon_openai_text),
        )
        ProviderId.XAI -> listOf(
            ProviderIconChoice("grok", "Grok", null, R.drawable.icon_grok),
            ProviderIconChoice("grok-text", "Grok", IconStyle.WORDMARK, R.drawable.icon_grok_text),
        )
        ProviderId.ANTIGRAVITY -> listOf(
            ProviderIconChoice("gemini-color", "Gemini", IconStyle.COLOR, R.drawable.icon_gemini_color),
            ProviderIconChoice("antigravity-color", "Antigravity", IconStyle.COLOR, R.drawable.icon_antigravity_color),
            ProviderIconChoice("gemini", "Gemini", IconStyle.MONOCHROME, R.drawable.icon_gemini),
            ProviderIconChoice("antigravity", "Antigravity", IconStyle.MONOCHROME, R.drawable.icon_antigravity),
            ProviderIconChoice("gemini-text", "Gemini", IconStyle.WORDMARK, R.drawable.icon_gemini_text),
            ProviderIconChoice("antigravity-text", "Antigravity", IconStyle.WORDMARK, R.drawable.icon_antigravity_text),
        )
        ProviderId.KIMI -> listOf(
            ProviderIconChoice("kimi-color", "Kimi", IconStyle.COLOR, R.drawable.icon_kimi_color),
            ProviderIconChoice("kimi", "Kimi", IconStyle.MONOCHROME, R.drawable.icon_kimi),
            ProviderIconChoice("kimi-text", "Kimi", IconStyle.WORDMARK, R.drawable.icon_kimi_text),
        )
        ProviderId.DEVIN -> listOf(
            ProviderIconChoice("devin-color", "Devin", IconStyle.COLOR, R.drawable.icon_devin_color),
            ProviderIconChoice("devin", "Devin", IconStyle.MONOCHROME, R.drawable.icon_devin),
        )
        ProviderId.META -> listOf(
            ProviderIconChoice("meta-color", "Meta Muse", IconStyle.COLOR, R.drawable.icon_meta_color),
            ProviderIconChoice("meta", "Meta Muse", IconStyle.MONOCHROME, R.drawable.icon_meta),
        )
        ProviderId.ZAI -> listOf(
            ProviderIconChoice("zai", "Z.ai", IconStyle.MONOCHROME, R.drawable.icon_zai),
            ProviderIconChoice("zai-text", "Z.ai", IconStyle.WORDMARK, R.drawable.icon_zai_text),
            ProviderIconChoice("chatglm-color", "ChatGLM", IconStyle.COLOR, R.drawable.icon_chatglm_color),
        )
        ProviderId.OPENCODE -> listOf(
            ProviderIconChoice("opencode", "OpenCode", IconStyle.MONOCHROME, R.drawable.icon_opencode),
            ProviderIconChoice("opencode-text", "OpenCode", IconStyle.WORDMARK, R.drawable.icon_opencode_text),
        )
    }
    fun selected(provider: ProviderId, id: String?): ProviderIconChoice =
        choices(provider).firstOrNull { it.id == id } ?: choices(provider).first()
}
