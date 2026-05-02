package xyz.tesser.sdk.internal.util

private val SECRET_KEY_PATTERN =
    Regex(
        "(authorization|x-stamp|api[_-]?key|secret|token)",
        RegexOption.IGNORE_CASE,
    )

/**
 * Redacts secret-bearing keys from a structured log payload by replacing
 * matching values with `"***"`. Used at every SDK log site that emits a
 * structured map. Phase B's HTTP layer is the primary consumer.
 */
internal fun redact(payload: Map<String, Any?>): Map<String, Any?> =
    payload.mapValues { (k, v) -> if (SECRET_KEY_PATTERN.containsMatchIn(k)) "***" else v }
