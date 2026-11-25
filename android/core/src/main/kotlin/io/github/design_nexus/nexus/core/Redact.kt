package io.github.design_nexus.nexus.core

/** Removes credentials from text before it's logged or put in a diagnostics report. */
object Redact {
    private val patterns = listOf(
        Regex("""(?i)(X-Plex-Token[=:]\s*)[^&\s"',;]+"""),
        Regex("""(?i)("?(?:authToken|accessToken|token)"?\s*[=:]\s*"?)[^"&\s,;}]+"""),
        Regex("""(?i)(Authorization:\s*(?:Bearer\s+)?)\S+"""),
        Regex("""(?i)(://[^/\s:@]+:)[^@\s/]+(@)"""),
    )

    fun text(s: String): String {
        var out = s
        for (p in patterns) out = p.replace(out) { m -> m.groupValues[1] + "••••" + (m.groupValues.getOrNull(2) ?: "") }
        return out
    }
}
