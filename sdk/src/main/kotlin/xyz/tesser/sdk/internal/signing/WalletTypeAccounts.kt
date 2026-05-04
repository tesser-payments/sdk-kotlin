package xyz.tesser.sdk.internal.signing

import xyz.tesser.sdk.WalletType

/**
 * Activity-payload account spec. One entry per `accounts[]` element in the
 * `ACTIVITY_TYPE_CREATE_WALLET` activity.
 *
 * @property curve Curve constant: `CURVE_SECP256K1`, `CURVE_ED25519`, etc.
 * @property pathFormat `PATH_FORMAT_BIP32` or `PATH_FORMAT_BIP44`.
 * @property path BIP32 derivation path.
 * @property addressFormat Address format: `ADDRESS_FORMAT_ETHEREUM`, etc.
 */
internal data class AccountSpec(
    val curve: String,
    val pathFormat: String,
    val path: String,
    val addressFormat: String,
)

/**
 * Wallet type to account specs lookup. The Ethereum spec is verified
 * end-to-end against Tesser staging. The Solana and Stellar specs are not yet
 * verified against the live API; if either fails for a spec-related reason,
 * file the API response in this repo's issue tracker so the spec can be
 * adjusted.
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
