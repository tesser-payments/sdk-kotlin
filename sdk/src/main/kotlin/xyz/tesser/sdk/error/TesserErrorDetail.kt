package xyz.tesser.sdk.error

/**
 * One entry from Tesser's documented `errors[]` response envelope.
 * Wire format uses snake_case; this type uses camelCase for Kotlin idiom —
 * deserialization will map `error_code` → [errorCode] etc. when HTTP lands in Phase B.
 *
 * See https://docs.tesser.xyz/overviews/errors for the canonical envelope.
 */
public data class TesserErrorDetail(
    val errorCode: String,
    val errorMessage: String,
    val uiMessage: String? = null,
)
