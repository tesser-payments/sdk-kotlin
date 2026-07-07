package xyz.tesser.sdk.internal.signing

import xyz.tesser.sdk.error.TesserError

/**
 * Maps a Tesser-style network identifier (the value of `step.from_network` /
 * `step.to_network`) to the Turnkey `TRANSACTION_TYPE_*` enum value used in
 * `parameters.type` of an `ACTIVITY_TYPE_SIGN_TRANSACTION_V2` activity.
 *
 * EVM-family networks all map to `TRANSACTION_TYPE_ETHEREUM` because Turnkey
 * keys the type by signing scheme, not by chain ID.
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
    )

internal fun networkToTurnkeyType(network: String): String =
    NETWORK_TO_TURNKEY_TYPE[network]
        ?: throw TesserError.ConfigError(
            "Unsupported network for step signing: '$network'. " +
                "Supported networks: ${NETWORK_TO_TURNKEY_TYPE.keys.sorted().joinToString()}.",
        )
