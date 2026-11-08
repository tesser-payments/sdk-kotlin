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
import xyz.tesser.sdk.internal.signing.StubHttpClient
import xyz.tesser.sdk.internal.signing.StubHttpResponse
import xyz.tesser.sdk.internal.signing.UnsignedPayloadFixtures
import java.util.Base64

class LocalSignerTest {
    private val cfg =
        SigningConfig(
            publicKey = "02".repeat(33),
            privateKey = "01".repeat(32),
            enclaveId = UnsignedPayloadFixtures.ORGANIZATION_ID,
        )

    private val apiBaseUrl = "https://api.tesser.xyz"

    private val payloadClient =
        StubHttpClient.respondingWith(StubHttpResponse(200, UnsignedPayloadFixtures.loadEthereumResponse()))

    private fun signerWithStubs(): LocalSigner = LocalSigner(cfg, apiBaseUrl, payloadClient, stubStamp())

    private fun stubStamp(): Stamp =
        mockk<Stamp>().also {
            coEvery { it.stamp(any(), any()) } returns StampResult("X-Stamp", "STAMP_VALUE")
        }

    @Test
    fun `constructor accepts a fully-populated SigningConfig`() {
        LocalSigner(cfg, apiBaseUrl)
    }

    @Test
    fun `constructor rejects blank publicKey`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                LocalSigner(cfg.copy(publicKey = ""), apiBaseUrl)
            }
        ex.message!! shouldContain "publicKey"
    }

    @Test
    fun `constructor rejects blank privateKey`() {
        val ex = shouldThrow<IllegalArgumentException> { LocalSigner(cfg.copy(privateKey = ""), apiBaseUrl) }
        ex.message!! shouldContain "privateKey"
    }

    @Test
    fun `constructor rejects blank enclaveId`() {
        val ex = shouldThrow<IllegalArgumentException> { LocalSigner(cfg.copy(enclaveId = ""), apiBaseUrl) }
        ex.message!! shouldContain "enclaveId"
    }

    @Test
    fun `constructor rejects an apiBaseUrl without an http scheme`() {
        val ex = shouldThrow<IllegalArgumentException> { LocalSigner(cfg, "api.tesser.xyz") }
        ex.message!! shouldContain "apiBaseUrl"
    }

    @Test
    fun `signCreateWallet returns a SignedResult with a non-empty signature`() =
        runTest {
            val signer = signerWithStubs()
            val result =
                signer.signCreateWallet(
                    CreateWalletParams(UnsignedPayloadFixtures.WALLET_NAME, WalletType.STABLECOIN_ETHEREUM),
                )
            result.signature.isNotBlank() shouldBe true
            result.metadata.stampHeaderValue shouldBe "STAMP_VALUE"
        }

    @Test
    fun `signCreateWallet payload includes the wallet name verbatim`() =
        runTest {
            val signer = signerWithStubs()
            val result =
                signer.signCreateWallet(
                    CreateWalletParams(UnsignedPayloadFixtures.WALLET_NAME, WalletType.STABLECOIN_ETHEREUM),
                )
            val composite = String(Base64.getDecoder().decode(result.signature))
            val body = Json.parseToJsonElement(composite).jsonObject["body"]!!.jsonPrimitive.content
            body shouldContain UnsignedPayloadFixtures.WALLET_NAME
            body shouldContain "ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS"
        }

    @Test
    fun `signCreateWallet for Solana uses ed25519 curve in payload`() =
        runTest {
            val signer = signerWithStubs()
            val result =
                signer.signCreateWallet(
                    CreateWalletParams("sol", WalletType.STABLECOIN_SOLANA),
                )
            result.metadata.body shouldContain "CURVE_ED25519"
            result.metadata.body shouldContain "ADDRESS_FORMAT_SOLANA"
        }
}
