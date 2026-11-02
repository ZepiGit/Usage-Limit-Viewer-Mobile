package com.usagelimits.ui

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provider icon picker's labels: the brand as written, the style word from resources (R4).
 *
 * The 28 English labels the catalog shipped with are listed here so that moving the style words
 * into resources is proved not to have changed one of them.
 */
private val SHIPPED_ENGLISH_LABELS = mapOf(
    "claudecode-color" to "Claude Code \u00B7 Color",
    "claudecode" to "Claude Code \u00B7 Monochrome",
    "claudecode-text" to "Claude Code \u00B7 Wordmark",
    "claude-color" to "Claude \u00B7 Color",
    "claude" to "Claude \u00B7 Monochrome",
    "claude-text" to "Claude \u00B7 Wordmark",
    "openai" to "OpenAI",
    "openai-text" to "OpenAI \u00B7 Wordmark",
    "grok" to "Grok",
    "grok-text" to "Grok \u00B7 Wordmark",
    "gemini-color" to "Gemini \u00B7 Color",
    "antigravity-color" to "Antigravity \u00B7 Color",
    "gemini" to "Gemini \u00B7 Monochrome",
    "antigravity" to "Antigravity \u00B7 Monochrome",
    "gemini-text" to "Gemini \u00B7 Wordmark",
    "antigravity-text" to "Antigravity \u00B7 Wordmark",
    "kimi-color" to "Kimi \u00B7 Color",
    "kimi" to "Kimi \u00B7 Monochrome",
    "kimi-text" to "Kimi \u00B7 Wordmark",
    "devin-color" to "Devin \u00B7 Color",
    "devin" to "Devin \u00B7 Monochrome",
    "meta-color" to "Meta Muse \u00B7 Color",
    "meta" to "Meta Muse \u00B7 Monochrome",
    "zai" to "Z.ai \u00B7 Monochrome",
    "zai-text" to "Z.ai \u00B7 Wordmark",
    "chatglm-color" to "ChatGLM \u00B7 Color",
    "opencode" to "OpenCode \u00B7 Monochrome",
    "opencode-text" to "OpenCode \u00B7 Wordmark",
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class ProviderIconLabelsEnglishTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val choices = ProviderId.entries.flatMap { ProviderIconCatalog.choices(it) }

    @Test
    fun `every label equals the one the catalog shipped with`() {
        assertEquals(SHIPPED_ENGLISH_LABELS.size, choices.size)
        for (choice in choices) {
            assertEquals(choice.id, SHIPPED_ENGLISH_LABELS.getValue(choice.id), choice.label(resources))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class ProviderIconLabelsGermanTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun label(provider: ProviderId, id: String) =
        ProviderIconCatalog.choices(provider).first { it.id == id }.label(resources)

    @Test
    fun `the style words are German and the brand stays as written`() {
        assertEquals("Claude Code \u00B7 Farbe", label(ProviderId.CLAUDE, "claudecode-color"))
        assertEquals("Claude Code \u00B7 Einfarbig", label(ProviderId.CLAUDE, "claudecode"))
        assertEquals("Claude Code \u00B7 Wortmarke", label(ProviderId.CLAUDE, "claudecode-text"))
        assertEquals("Meta Muse \u00B7 Einfarbig", label(ProviderId.META, "meta"))
        assertEquals("Z.ai \u00B7 Wortmarke", label(ProviderId.ZAI, "zai-text"))
    }

    @Test
    fun `a brand-only choice has no style word`() {
        assertEquals("OpenAI", label(ProviderId.CODEX, "openai"))
        assertEquals("Grok", label(ProviderId.XAI, "grok"))
    }

    @Test
    fun `no label carries an English style word`() {
        for (choice in ProviderId.entries.flatMap { ProviderIconCatalog.choices(it) }) {
            val text = choice.label(resources)
            for (english in listOf("Color", "Monochrome", "Wordmark")) {
                assertFalse("${choice.id}: $text", text.contains(english))
            }
        }
    }
}
