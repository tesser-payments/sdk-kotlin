package xyz.tesser.sdk.internal.signing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.SignStepOptions
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.StepForSigning
import xyz.tesser.sdk.error.TesserError
import java.util.Base64

class SignStepActivityTest {
    private val cfg =
        SigningConfig(
            publicKey = "02".repeat(33),
            privateKey = "01".repeat(32),
            enclaveId = "org_test_step",
        )

    private val step =
        StepForSigning(
            id = "step_abc",
            transferId = "reb_123",
            unsignedTransaction = "0x02ed81893a850165a0bc0085012a05f200825208949c4e7f2b1d8a4e6cb3f58a2d6e9b1c4f880de0b6b3a764000080c0",
            signWith = "0xb909cbe4a348754b17b474df9f12ab8842020165",
            network = "BASE_SEPOLIA",
        )

    private fun stubStamp(): Stamp =
        mockk<Stamp>().also {
            coEvery { it.stamp(any(), any()) } returns
                StampResult(
                    stampHeaderName = "X-Stamp",
                    stampHeaderValue = "FAKE_STAMP_VALUE",
                )
        }

    @Test
    fun `stamps a Turnkey ACTIVITY_TYPE_SIGN_TRANSACTION_V2 body`() =
        runTest {
            val bodySlot = slot<String>()
            val stamp =
                mockk<Stamp>().also {
                    coEvery { it.stamp(any(), capture(bodySlot)) } returns
                        StampResult("X-Stamp", "STAMP_VALUE")
                }
            signStepInternal(cfg, step, SignStepOptions(), stamp)

            val stampedBody = Json.parseToJsonElement(bodySlot.captured).jsonObject
            stampedBody["type"]!!.jsonPrimitive.content shouldBe "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
            stampedBody["organizationId"]!!.jsonPrimitive.content shouldBe cfg.enclaveId
            val params = stampedBody["parameters"]!!.jsonObject
            params["signWith"]!!.jsonPrimitive.content shouldBe step.signWith
            params["unsignedTransaction"]!!.jsonPrimitive.content shouldBe step.unsignedTransaction
            params["type"]!!.jsonPrimitive.content shouldBe "TRANSACTION_TYPE_ETHEREUM"
        }

    @Test
    fun `returns a base64 composite signature containing body and stamp`() =
        runTest {
            val result = signStepInternal(cfg, step, SignStepOptions(), stubStamp())
            val decoded = String(Base64.getDecoder().decode(result.signature))
            val composite = Json.parseToJsonElement(decoded).jsonObject
            composite["stamp"]!!.jsonPrimitive.content shouldBe "FAKE_STAMP_VALUE"
            val innerBody = Json.parseToJsonElement(composite["body"]!!.jsonPrimitive.content).jsonObject
            innerBody["type"]!!.jsonPrimitive.content shouldBe "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
        }

    @Test
    fun `echoes the unsignedTransaction on the result`() =
        runTest {
            val result = signStepInternal(cfg, step, SignStepOptions(), stubStamp())
            result.unsignedTransaction shouldBe step.unsignedTransaction
        }

    @Test
    fun `metadata threads through stamp header values and the stamped body`() =
        runTest {
            val result = signStepInternal(cfg, step, SignStepOptions(), stubStamp())
            result.metadata.stampHeaderName shouldBe "X-Stamp"
            result.metadata.stampHeaderValue shouldBe "FAKE_STAMP_VALUE"
            result.metadata.body shouldContain "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
            result.metadata.body shouldContain step.unsignedTransaction
        }

    @Test
    fun `Solana network maps to TRANSACTION_TYPE_SOLANA`() =
        runTest {
            val solanaStep =
                step.copy(
                    network = "SOLANA",
                    signWith = "9wXn6solBase58Address",
                )
            val bodySlot = slot<String>()
            val stamp =
                mockk<Stamp>().also {
                    coEvery { it.stamp(any(), capture(bodySlot)) } returns
                        StampResult("X-Stamp", "STAMP_VALUE")
                }
            signStepInternal(cfg, solanaStep, SignStepOptions(), stamp)
            val params = Json.parseToJsonElement(bodySlot.captured).jsonObject["parameters"]!!.jsonObject
            params["type"]!!.jsonPrimitive.content shouldBe "TRANSACTION_TYPE_SOLANA"
        }

    @Test
    fun `unknown network throws ConfigError before any stamping`() =
        runTest {
            val unknownNet = step.copy(network = "MARS_TESTNET")
            shouldThrow<TesserError.ConfigError> {
                signStepInternal(cfg, unknownNet, SignStepOptions(), stubStamp())
            }
        }
}
