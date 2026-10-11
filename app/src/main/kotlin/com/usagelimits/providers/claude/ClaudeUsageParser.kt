package com.usagelimits.providers.claude

import com.usagelimits.core.model.planLabel

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Claude
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull

/** Parses quota identity, consumption and model scope from the usage response. */
object ClaudeUsageParser {

    private const val FIVE_HOUR_SECONDS = 18_000L
    private const val SEVEN_DAY_SECONDS = 604_800L

    private const val FIVE_HOUR_ID = "five-hour"
    private const val SEVEN_DAY_ID = "seven-day"

    /**
     * Top-level objects that look like windows but are not.
     *
     * The discovery gate asks whether a key declares `resets_at`, and `extra_usage` is one
     * field away from passing it — it already carries a `utilization`. It is a CREDIT BALANCE:
     * folding it in would grade an account by money spent, and spending credits would trip an
     * exhaustion alert. Named explicitly rather than inferred, because the shape that
     * distinguishes them today is one upstream field away from being ambiguous.
     */
    private val NON_WINDOW_KEYS = setOf(
        "extra_usage",
        "spend",
        "seven_day_breakdown",
        "limits",
        "organization",
        "account",
    )

    /** A label is a row on a phone, and a server-supplied key has no length limit. */
    private const val MAX_LABEL_LENGTH = 48

    /**
     * Truncates without splitting a character in half.
     *
     * `take` counts UTF-16 code units, so cutting a label whose 48th and 49th units are the two
     * halves of one emoji leaves a lone surrogate — a malformed string that renders as a
     * replacement box and compares unequal to anything sensible. Swift's `prefix` cuts on
     * grapheme boundaries and never had this; the two must agree, and Swift is right.
     */
    private fun String.takeLabel(): String {
        // Counted in grapheme clusters, which is what Swift's `prefix` counts. The previous
        // version counted UTF-16 units and then dropped a trailing lone surrogate, which kept
        // the string well-formed but cut it one character short of the Swift twin whenever an
        // emoji sat at the boundary — its own comment said Swift was right and still disagreed.
        val boundaries = java.text.BreakIterator.getCharacterInstance().also { it.setText(this) }
        var end = 0
        var count = 0
        var next = boundaries.next()
        while (next != java.text.BreakIterator.DONE && count < MAX_LABEL_LENGTH) {
            end = next
            count++
            next = boundaries.next()
        }
        return substring(0, end)
    }

    /**
     * A `utilization` of 21.0 means 21 % consumed, not 21 % of one.
     *
     * Worth stating because the field is named `utilization` rather than `percent`, and a
     * fractional reading would put every fallback number out by a factor of a hundred while
     * still looking like a number. A live account settles it: `five_hour.utilization` was 21.0
     * and the `limits` entry for the same quota reported `percent: 21`. The test suite pins
     * that equivalence, so if the scale ever changes it fails loudly rather than quietly.
     */
    fun parse(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val primary = parseLimits(payload)
        val supplemental = parseFlatWindows(payload) + discoverUnknownWindows(payload)
        // Equal reset times do not establish equal identity or model scope.
        val byId = linkedMapOf<String, UsageWindow>()
        for (window in primary + supplemental) {
            val previous = byId[window.id]
            if (previous == null || (previous.remainingPercent == null && window.remainingPercent != null)) {
                byId[window.id] = window
            }
        }
        return byId.values.toList()

    }

    /** Distinguishes malformed explicit measurements from legitimately missing values. */
    fun hasInvalidUsage(payload: JsonObject): Boolean {
        fun invalid(entry: JsonObject, key: String): Boolean {
            val value = entry[key] ?: return false
            return value != JsonNull && JsonSupport.double(entry, key)?.let { it in 0.0..100.0 } != true
        }
        if (JsonSupport.array(payload, "limits").any { it is JsonObject && invalid(it, "percent") }) return true
        return payload.any { (key, value) ->
            key !in NON_WINDOW_KEYS && value is JsonObject && invalid(value, "utilization")
        }
    }

    /**
     * Reads the plan label the profile endpoint reports, for the account subtitle.
     *
     * `organization.rate_limit_tier` is preferred over the `has_claude_max` boolean because the
     * boolean cannot tell a Max 5× subscription from a Max 20× one — a fivefold difference in
     * the very quantity this app exists to show. A live profile reports
     * `default_claude_max_5x`, so the multiplier is right there; the booleans are the fallback
     * for a payload that omits the tier.
     */
    fun parsePlan(profile: JsonObject): String? {
        val organization = JsonSupport.obj(profile, "organization")
        val tier = JsonSupport.string(organization, "rate_limit_tier", "rateLimitTier")
        planFromTier(tier)?.let { return it }

        val account = JsonSupport.obj(profile, "account") ?: return null
        return when {
            JsonSupport.boolean(account, "has_claude_max", "hasClaudeMax") == true -> "Max"
            JsonSupport.boolean(account, "has_claude_pro", "hasClaudePro") == true -> "Pro"
            else -> null
        }
    }

    /**
     * Turns `default_claude_max_5x` into `Max 5×`.
     *
     * Read structurally rather than from a table of known tiers: Anthropic adds tiers, and a
     * table would render a new one as no plan at all. An unrecognised tier still yields
     * something readable, which beats a blank subtitle.
     */
    private fun planFromTier(tier: String?): String? = planLabel(
        // The vendor prefixes are Anthropic's alone, so they are stripped here rather than in
        // the shared formatter.
        tier?.trim()?.lowercase()?.removePrefix("default_")?.removePrefix("claude_"),
    )

    /**
     * Maps the `limits` array one entry to one window.
     *
     * An entry whose `kind` is unrecognised is kept rather than dropped: `group` still says
     * whether it is a session or a weekly quota, and a window with no known duration is more
     * useful than a missing one. Entries are deduplicated by id, first occurrence winning.
     */
    private fun parseLimits(payload: JsonObject): List<UsageWindow> {
        data class Entry(val payload: JsonObject, val kind: String?, val model: String?, val modelKey: String?) {
            val identity: String get() = JsonSupport.string(payload, "id")
                ?: if (kind != null || modelKey != null) "${kind.orEmpty()}|${modelKey.orEmpty()}" else payload.toString()
            val baseId: String get() = when (kind) {
                "session" -> FIVE_HOUR_ID
                "weekly_all" -> SEVEN_DAY_ID
                "weekly_scoped" -> "$SEVEN_DAY_ID-${slug(modelKey ?: "scoped")}"
                else -> slug(kind ?: "limit")
            }
        }
        val entries = JsonSupport.array(payload, "limits").mapNotNull { value ->
            val limit = value as? JsonObject ?: return@mapNotNull null
            val kind = JsonSupport.string(limit, "kind")
            if (kind == null && !limit.containsKey("percent")) return@mapNotNull null
            val model = JsonSupport.obj(JsonSupport.obj(limit, "scope"), "model")
            val displayName = JsonSupport.string(model, "display_name", "displayName")
            Entry(limit, kind, displayName, JsonSupport.string(model, "id") ?: displayName)
        }.distinctBy { it.identity }
        // Suffix colliding slugs by sorted raw identity, never by array position.
        val identities = entries.groupBy { it.baseId }.mapValues { (_, values) -> values.map { it.identity }.sorted() }
        return entries.map { entry ->
            val limit = entry.payload
            val kind = entry.kind
            val group = JsonSupport.string(limit, "group")
            val rank = identities.getValue(entry.baseId).indexOf(entry.identity)
            val id = if (rank == 0) entry.baseId else "${entry.baseId}-${rank + 1}"
            val periodSeconds = when {
                kind == "session" || group == "session" -> FIVE_HOUR_SECONDS
                kind?.startsWith("weekly") == true || group == "weekly" -> SEVEN_DAY_SECONDS
                else -> null
            }
            window(
                id = id,
                label = when (kind) {
                    "session" -> "5h limit"
                    "weekly_all" -> "Weekly"
                    "weekly_scoped" -> entry.model?.let { "Weekly ($it)" } ?: "Weekly (scoped)"
                    else -> humanize(kind ?: "Limit").takeLabel()
                },
                usedPercent = JsonSupport.double(limit, "percent"),
                periodSeconds = periodSeconds,
                resetsAt = JsonSupport.string(limit, "resets_at", "resetsAt"),
                scope = when {
                    kind == "weekly_scoped" || JsonSupport.obj(JsonSupport.obj(limit, "scope"), "model") != null -> UsageScope.MODEL
                    kind == "session" || kind == "weekly_all" -> UsageScope.GLOBAL
                    else -> UsageScope.UNKNOWN
                },
            )
        }
    }

    /** A declared flat window remains visible when its consumption is unknown. */
    private fun parseFlatWindows(payload: JsonObject): List<UsageWindow> =
        Claude.USAGE_WINDOW_KEYS.mapNotNull { (key, label) ->
            val entry = JsonSupport.obj(payload, key) ?: return@mapNotNull null
            val used = JsonSupport.double(entry, "utilization")

            window(
                id = key.replace('_', '-'),
                label = label,
                usedPercent = used,
                periodSeconds = periodSecondsForKey(key),
                resetsAt = JsonSupport.string(entry, "resets_at", "resetsAt"),
                scope = if (key == "five_hour" || key == "seven_day") UsageScope.GLOBAL else UsageScope.MODEL,
            )
        }

    /** Discovers declared windows without guessing that their scope or duration is global. */
    private fun discoverUnknownWindows(payload: JsonObject): List<UsageWindow> {
        val knownKeys = Claude.USAGE_WINDOW_KEYS.map { it.first }.toSet()

        val candidates: List<UsageWindow> = payload.entries.mapNotNull { (key, value) ->
            if (key in knownKeys || key in NON_WINDOW_KEYS) return@mapNotNull null

            val entry = value as? JsonObject ?: return@mapNotNull null
            if (!entry.containsKey("resets_at") && !entry.containsKey("resetsAt")) {
                return@mapNotNull null
            }

            if (!entry.containsKey("utilization")) return@mapNotNull null
            val used = JsonSupport.double(entry, "utilization")

            val id = key.replace('_', '-').trim('-')
            if (id.isBlank()) return@mapNotNull null

            window(
                id = id,
                label = humanize(key).takeLabel(),
                usedPercent = used,
                // The duration of a window nobody has documented is unknown, and OTHER says so.
                // It cannot be inferred from the time left before its reset either: a weekly
                // window observed two hours before it rolls over would infer two hours, which
                // is a confidently wrong category rather than an honest unknown.
                periodSeconds = null,
                resetsAt = JsonSupport.string(entry, "resets_at", "resetsAt"),
                scope = UsageScope.UNKNOWN,
            )
        }.sortedBy { it.id }

        return candidates
    }

    /**
     * Only `five_hour` is a rolling five-hour window; every other key in the registry is a
     * seven-day window. The payload does not state either, so it comes from the key.
     */
    private fun periodSecondsForKey(key: String): Long =
        if (key == "five_hour") FIVE_HOUR_SECONDS else SEVEN_DAY_SECONDS

    private fun window(
        id: String,
        label: String,
        usedPercent: Double?,
        periodSeconds: Long?,
        resetsAt: String?,
        scope: UsageScope,
        group: String? = null,
    ) = UsageWindow(
        id = id,
        label = label,
        category = WindowCategory.fromPeriodSeconds(periodSeconds),
        usedPercent = usedPercent?.takeIf { it.isFinite() && it in 0.0..100.0 },
        periodSeconds = periodSeconds,
        resetAt = Instants.parse(resetsAt),
        exhausted = usedPercent == 100.0,
        scope = scope,
        group = group,
    )

    /** `nimbus_quill` reads as "Nimbus Quill" — the only label an undocumented key can have. */
    private fun humanize(key: String): String =
        key.split('_', '-')
            .filter { it.isNotBlank() }
            // The full Unicode mapping, as above and as Swift does.
            .joinToString(" ") { part -> part.first().uppercase() + part.drop(1) }

    private fun slug(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "window" }
}
