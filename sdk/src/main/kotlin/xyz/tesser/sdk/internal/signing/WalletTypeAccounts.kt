package xyz.tesser.sdk.internal.signing

import xyz.tesser.sdk.WalletType

/**
 * Turnkey activity-payload account spec — one per `accounts[]` entry in the
 * `ACTIVITY_TYPE_CREATE_WALLET` activity.
 *
 * @property curve Turnkey curve constant: `CURVE_SECP256K1`, `CURVE_ED25519`, etc.
 * @property pathFormat `PATH_FORMAT_BIP32` or `PATH_FORMAT_BIP44`.
 * @property path BIP32 derivation path.
 * @property addressFormat Turnkey address format: `ADDRESS_FORMAT_ETHEREUM`, etc.
 */
internal data class AccountSpec(
    val curve: String,
    val pathFormat: String,
    val path: String,
    val addressFormat: String,
)

/**
 * Wallet type -> account specs lookup. Ethereum is verified end-to-end against
 * Tesser staging via the TS SDK's Phase A. Solana and Stellar specs are
 * best-guesses inherited from the TS SDK pending their first staging run
 * (spec section 10 Open Item 2).
 */
internal val WALLET_TYPE_ACCOUNTS: Map<WalletType, List<AccountSpec>> =
    mapOf(
        WalletType.STABLECOIN_ETHEREUM to
            listOf(
                AccountSpec(
                    curve = "CURVE_SECP256K1",
                    pathFormat = "PATH_FORMAT_BIP32",
                    path = "m/44'/60'/0'/0/0",
                    addressFormat = "ADDRESS_FORMAT_ETHEREUM",
                ),
            ),
        WalletType.STABLECOIN_SOLANA to
            listOf(
                AccountSpec(
                    curve = "CURVE_ED25519",
                    pathFormat = "PATH_FORMAT_BIP32",
                    path = "m/44'/501'/0'/0'",
                    addressFormat = "ADDRESS_FORMAT_SOLANA",
                ),
            ),
        WalletType.STABLECOIN_STELLAR to
            listOf(
                AccountSpec(
                    curve = "CURVE_ED25519",
                    pathFormat = "PATH_FORMAT_BIP32",
                    path = "m/44'/148'/0'",
                    addressFormat = "ADDRESS_FORMAT_XLM",
                ),
            ),
    )
