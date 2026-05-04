package xyz.tesser.sdk.error

/**
 * One entry from Tesser's documented `errors[]` response envelope.
 *
 * The wire format uses snake_case; this type uses camelCase for Kotlin idiom.
 * Deserialization maps `error_code` to [errorCode], `error_message` to
 * [errorMessage], and `ui_message` to [uiMessage].
 *
 * See https://docs.tesser.xyz/overviews/errors for the canonical envelope.
 */
public data class TesserErrorDetail(
    val errorCode: String,
    val errorMessage: String,
    val uiMessage: String? = null,
)
