package xyz.tesser.sdk.internal.signing

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.WalletType

class WalletTypeAccountsTest {
    @Test
    fun `every WalletType has at least one account spec`() {
        WalletType.entries.forEach { wt ->
            val specs = WALLET_TYPE_ACCOUNTS[wt]
            assert(specs != null && specs.isNotEmpty()) { "Missing account spec for $wt" }
        }
    }

    @Test
    fun `Ethereum spec uses secp256k1 + Ethereum address format`() {
        val specs = WALLET_TYPE_ACCOUNTS[WalletType.STABLECOIN_ETHEREUM]!!
        specs shouldHaveSize 1
        specs[0].curve shouldBe "CURVE_SECP256K1"
        specs[0].pathFormat shouldBe "PATH_FORMAT_BIP32"
        specs[0].path shouldBe "m/44'/60'/0'/0/0"
        specs[0].addressFormat shouldBe "ADDRESS_FORMAT_ETHEREUM"
    }

    @Test
    fun `Solana spec uses ed25519 + Solana address format`() {
        val spec = WALLET_TYPE_ACCOUNTS[WalletType.STABLECOIN_SOLANA]!!.single()
        spec.curve shouldBe "CURVE_ED25519"
        spec.addressFormat shouldBe "ADDRESS_FORMAT_SOLANA"
    }

    @Test
    fun `Stellar spec uses ed25519 + XLM address format`() {
        val spec = WALLET_TYPE_ACCOUNTS[WalletType.STABLECOIN_STELLAR]!!.single()
        spec.curve shouldBe "CURVE_ED25519"
        spec.addressFormat shouldBe "ADDRESS_FORMAT_XLM"
    }
}
