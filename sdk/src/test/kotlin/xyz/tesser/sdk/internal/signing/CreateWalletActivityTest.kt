package xyz.tesser.sdk.internal.signing

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.WalletType
import java.util.Base64

class CreateWalletActivityTest {
    private val cfg =
        SigningConfig(
            publicKey = "02".repeat(33),
            privateKey = "01".repeat(32),
            enclaveId = "org_test_123",
        )

    private fun mockStamp(): Stamp =
        mockk<Stamp>().also {
            coEvery { it.stamp(any(), any()) } returns
                StampResult(
                    stampHeaderName = "X-Stamp",
                    stampHeaderValue = "FAKE_STAMP_VALUE",
                )
        }

    @Test
    fun `signCreateWallet builds an ACTIVITY_TYPE_CREATE_WALLET payload`() =
        runTest {
            val result =
                signCreateWalletInternal(
                    signing = cfg,
                    params = CreateWalletParams(name = "test wallet", type = WalletType.STABLECOIN_ETHEREUM),
                    stamp = mockStamp(),
                )
            val body = Json.parseToJsonElement(result.metadata.body).jsonObject
            body["type"]?.jsonPrimitive?.content shouldBe "ACTIVITY_TYPE_CREATE_WALLET"
            body["organizationId"]?.jsonPrimitive?.content shouldBe "org_test_123"
            body["timestampMs"]?.jsonPrimitive?.content!!.toLong() // is a number-string
        }

    @Test
    fun `signCreateWallet payload parameters carry walletName and accounts`() =
        runTest {
            val result =
                signCreateWalletInternal(
                    signing = cfg,
                    params = CreateWalletParams(name = "alpha", type = WalletType.STABLECOIN_ETHEREUM),
                    stamp = mockStamp(),
                )
            val params = Json.parseToJsonElement(result.metadata.body).jsonObject["parameters"]!!.jsonObject
            params["walletName"]?.jsonPrimitive?.content shouldBe "alpha"
            val accounts = params["accounts"]!!.jsonArray
            accounts shouldHaveSize 1
            val first = accounts[0].jsonObject
            first["curve"]?.jsonPrimitive?.content shouldBe "CURVE_SECP256K1"
            first["addressFormat"]?.jsonPrimitive?.content shouldBe "ADDRESS_FORMAT_ETHEREUM"
        }

    @Test
    fun `signCreateWallet returns Base64-encoded composite signature containing body and stamp`() =
        runTest {
            val result =
                signCreateWalletInternal(
                    signing = cfg,
                    params = CreateWalletParams(name = "alpha", type = WalletType.STABLECOIN_ETHEREUM),
                    stamp = mockStamp(),
                )
            val decoded = String(Base64.getDecoder().decode(result.signature))
            val composite = Json.parseToJsonElement(decoded).jsonObject
            composite["body"]!!.jsonPrimitive.content shouldContain "ACTIVITY_TYPE_CREATE_WALLET"
            composite["stamp"]!!.jsonPrimitive.content shouldBe "FAKE_STAMP_VALUE"
        }

    @Test
    fun `signCreateWallet metadata threads through stamp header values`() =
        runTest {
            val result =
                signCreateWalletInternal(
                    signing = cfg,
                    params = CreateWalletParams(name = "alpha", type = WalletType.STABLECOIN_ETHEREUM),
                    stamp = mockStamp(),
                )
            result.metadata.stampHeaderName shouldBe "X-Stamp"
            result.metadata.stampHeaderValue shouldBe "FAKE_STAMP_VALUE"
        }
}
