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
    @Deprecated(
        "ui_message is meant for Tesser's dashboard. The Tesser API will stop returning it to " +
            "API-key requests on a date to be announced, and this will then be null. " +
            "Use errorMessage instead, and errorCode for logic.",
    )
    val uiMessage: String? = null,
)
