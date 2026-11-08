package xyz.tesser.sdk

import xyz.tesser.sdk.error.TesserError

/**
 * Wallet types Tesser supports. For [STABLECOIN_ETHEREUM] the SDK signs a
 * wallet-creation request fetched from the Tesser API. The other types are
 * signed locally from their account spec (curve, pathFormat, path,
 * addressFormat).
 */
public enum class WalletType(public val wireValue: String) {
    STABLECOIN_ETHEREUM("stablecoin_ethereum"),
    STABLECOIN_SOLANA("stablecoin_solana"),
    STABLECOIN_STELLAR("stablecoin_stellar"),
    ;

    public companion object {
        /**
         * Resolve a wire-format string to its [WalletType] enum constant.
         * Throws [TesserError.ConfigError] for unknown values.
         */
        public fun fromWireValue(value: String): WalletType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw TesserError.ConfigError("Unknown WalletType: '$value'")
    }
}
