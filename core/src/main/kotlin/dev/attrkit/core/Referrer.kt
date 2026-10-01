package dev.attrkit.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object PlayInstallReferrerParser {
    private val uuidPattern = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Accepts the decoded Play value, a URL-encoded equivalent, or a `referrer=...`
     * wrapper. Extra campaign parameters are ignored; duplicate/missing AttriKit keys
     * fail closed. Output is the exact canonical shape the worker parser accepts.
     */
    fun normalize(raw: String): String? {
        if (raw.isBlank() || raw.toByteArray(StandardCharsets.UTF_8).size > 1_024) return null
        val values = parseValues(decodedCandidate(raw.trim().removePrefix("?")))
        val campaign = singleUuid(values, "attrkit_c") ?: return null
        val click = singleUuid(values, "attrkit_k") ?: return null
        return "attrkit_c=$campaign&attrkit_k=$click"
    }

    private fun decodedCandidate(raw: String): String {
        var candidate = raw
        repeat(2) {
            if (candidate.startsWith("referrer=", ignoreCase = true) &&
                !candidate.substringAfter('=').contains("referrer=", ignoreCase = true)
            ) {
                candidate = decode(candidate.substringAfter('='))
            } else if (!candidate.contains('&') && candidate.contains("%")) {
                candidate = decode(candidate)
            }
        }
        return candidate
    }

    private fun parseValues(candidate: String): Map<String, MutableList<String>> {
        val values = linkedMapOf<String, MutableList<String>>()
        for (part in candidate.split('&')) {
            if (part.isEmpty()) continue
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val key = decode(part.substring(0, separator))
            val value = decode(part.substring(separator + 1))
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        return values
    }

    private fun singleUuid(values: Map<String, MutableList<String>>, key: String): String? {
        val matches = values[key] ?: return null
        return matches.singleOrNull()?.let(::normalizeUuid)
    }

    fun uuidOrNull(raw: String?): UUID? {
        val normalized = raw?.trim()?.lowercase() ?: return null
        if (!uuidPattern.matches(normalized) ||
            normalized == "00000000-0000-0000-0000-000000000000"
        ) return null
        return runCatching { UUID.fromString(normalized) }.getOrNull()
    }

    private fun normalizeUuid(raw: String): String? =
        uuidOrNull(raw)?.toString()?.lowercase()

    /**
     * Decodes through the `(String, String)` overload, which Android has carried since API 1.
     * The `(String, Charset)` overload this used to call arrived on Android only at API 33, and
     * `runCatching` catches Throwable, so on every older device the resulting NoSuchMethodError
     * was swallowed and `getOrDefault` handed back the still-encoded input: percent-encoded and
     * `referrer=`-wrapped values were never decoded, `normalize` returned null, and the install
     * lost its attribution silently. Nothing in this package pins a minSdk that would rule those
     * devices out. The name is taken from StandardCharsets rather than written as a literal so a
     * typo cannot compile.
     */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
