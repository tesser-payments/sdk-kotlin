package xyz.tesser.sdk.internal.signing

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.WalletType

class WalletTypeAccountsTest {
    @Test
    fun `every locally-built WalletType has at least one account spec`() {
        WalletType.entries.filter { it != WalletType.STABLECOIN_ETHEREUM }.forEach { wt ->
            val specs = WALLET_TYPE_ACCOUNTS[wt]
            specs.shouldNotBeNull().shouldNotBeEmpty()
        }
    }

    @Test
    fun `Ethereum has no local account spec`() {
        WALLET_TYPE_ACCOUNTS[WalletType.STABLECOIN_ETHEREUM].shouldBeNull()
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
