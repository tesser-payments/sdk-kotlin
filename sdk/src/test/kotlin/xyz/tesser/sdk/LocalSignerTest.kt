package xyz.tesser.sdk

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.internal.signing.Stamp
import xyz.tesser.sdk.internal.signing.StampResult
import xyz.tesser.sdk.internal.signing.TurnkeyClient
import java.util.Base64

class LocalSignerTest {
    private val cfg =
        SigningConfig(
            publicKey = "02".repeat(33),
            privateKey = "01".repeat(32),
            enclaveId = "org_local_signer_test",
        )

    private fun stubStamp(): Stamp =
        mockk<Stamp>().also {
            coEvery { it.stamp(any(), any()) } returns StampResult("X-Stamp", "STAMP_VALUE")
        }

    // signCreateWallet doesn't touch Turnkey, but the LocalSigner constructor
    // still requires a TurnkeyClient — relaxed mock keeps these tests focused
    // on the create-wallet path.
    private fun unusedTurnkey(): TurnkeyClient = mockk(relaxed = true)

    @Test
    fun `constructor accepts a fully-populated SigningConfig`() {
        LocalSigner(cfg) // no exception
    }

    @Test
    fun `constructor rejects blank publicKey`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                LocalSigner(cfg.copy(publicKey = ""))
            }
        ex.message!! shouldContain "publicKey"
    }

    @Test
    fun `constructor rejects blank privateKey`() {
        val ex = shouldThrow<IllegalArgumentException> { LocalSigner(cfg.copy(privateKey = "")) }
        ex.message!! shouldContain "privateKey"
    }

    @Test
    fun `constructor rejects blank enclaveId`() {
        val ex = shouldThrow<IllegalArgumentException> { LocalSigner(cfg.copy(enclaveId = "")) }
        ex.message!! shouldContain "enclaveId"
    }

    @Test
    fun `signCreateWallet returns a SignedResult with a non-empty signature`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp(), unusedTurnkey())
            val result =
                signer.signCreateWallet(
                    CreateWalletParams("my wallet", WalletType.STABLECOIN_ETHEREUM),
                )
            result.signature.isNotBlank() shouldBe true
            result.metadata.stampHeaderValue shouldBe "STAMP_VALUE"
        }

    @Test
    fun `signCreateWallet payload includes the wallet name verbatim`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp(), unusedTurnkey())
            val result =
                signer.signCreateWallet(
                    CreateWalletParams("verbatim-name-123", WalletType.STABLECOIN_ETHEREUM),
                )
            val composite = String(Base64.getDecoder().decode(result.signature))
            val body = Json.parseToJsonElement(composite).jsonObject["body"]!!.jsonPrimitive.content
            body shouldContain "verbatim-name-123"
        }

    @Test
    fun `signCreateWallet for Solana uses ed25519 curve in payload`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp(), unusedTurnkey())
            val result =
                signer.signCreateWallet(
                    CreateWalletParams("sol", WalletType.STABLECOIN_SOLANA),
                )
            result.metadata.body shouldContain "CURVE_ED25519"
            result.metadata.body shouldContain "ADDRESS_FORMAT_SOLANA"
        }
}
