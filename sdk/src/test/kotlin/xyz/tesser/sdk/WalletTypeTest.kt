package xyz.tesser.sdk

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.error.TesserError

class WalletTypeTest {
    @Test
    fun `wireValue returns the canonical string for each variant`() {
        WalletType.STABLECOIN_ETHEREUM.wireValue shouldBe "stablecoin_ethereum"
        WalletType.STABLECOIN_SOLANA.wireValue shouldBe "stablecoin_solana"
        WalletType.STABLECOIN_STELLAR.wireValue shouldBe "stablecoin_stellar"
    }

    @Test
    fun `fromWireValue resolves canonical strings`() {
        WalletType.fromWireValue("stablecoin_ethereum") shouldBe WalletType.STABLECOIN_ETHEREUM
        WalletType.fromWireValue("stablecoin_solana") shouldBe WalletType.STABLECOIN_SOLANA
        WalletType.fromWireValue("stablecoin_stellar") shouldBe WalletType.STABLECOIN_STELLAR
    }

    @Test
    fun `fromWireValue throws ConfigError for unknown values`() {
        val ex =
            shouldThrow<TesserError.ConfigError> {
                WalletType.fromWireValue("stablecoin_dogecoin")
            }
        ex.message shouldBe "Unknown WalletType: 'stablecoin_dogecoin'"
    }
}
