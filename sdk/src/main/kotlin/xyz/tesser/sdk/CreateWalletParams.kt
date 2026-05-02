package xyz.tesser.sdk

/**
 * Parameters for [LocalSigner.signCreateWallet].
 *
 * @property name Human-readable wallet name surfaced in the Tesser dashboard and
 *   Turnkey activity log. No constraints beyond what Tesser's API enforces.
 * @property type Which wallet type (curve + path + addressFormat) to derive.
 */
public data class CreateWalletParams(
    val name: String,
    val type: WalletType,
)
