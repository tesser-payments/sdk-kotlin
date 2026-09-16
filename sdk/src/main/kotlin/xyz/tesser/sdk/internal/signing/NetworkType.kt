package xyz.tesser.sdk.internal.signing

import xyz.tesser.sdk.error.TesserError

/**
 * Maps a Tesser-style network identifier (the value of `step.from_network` /
 * `step.to_network`) to the Turnkey `TRANSACTION_TYPE_*` enum value used in
 * `parameters.type` of an `ACTIVITY_TYPE_SIGN_TRANSACTION_V2` activity.
 */
private val NETWORK_TO_TURNKEY_TYPE: Map<String, String> =
    mapOf(
        "BASE" to "TRANSACTION_TYPE_ETHEREUM",
        "BASE_SEPOLIA" to "TRANSACTION_TYPE_ETHEREUM",
        "ETHEREUM" to "TRANSACTION_TYPE_ETHEREUM",
        "ETHEREUM_SEPOLIA" to "TRANSACTION_TYPE_ETHEREUM",
        "POLYGON" to "TRANSACTION_TYPE_ETHEREUM",
        "POLYGON_AMOY" to "TRANSACTION_TYPE_ETHEREUM",
        "SOLANA" to "TRANSACTION_TYPE_SOLANA",
        "TEMPO" to "TRANSACTION_TYPE_TEMPO",
        "TEMPO_MODERATO" to "TRANSACTION_TYPE_TEMPO",
    )

internal fun networkToTurnkeyType(network: String): String =
    NETWORK_TO_TURNKEY_TYPE[network]
        ?: throw TesserError.ConfigError(
            "Unsupported network for step signing: '$network'. " +
                "Supported networks: ${NETWORK_TO_TURNKEY_TYPE.keys.sorted().joinToString()}.",
        )
