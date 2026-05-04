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

    private fun stubTurnkey(signedTx: String = "0xdeadbeef"): TurnkeyClient =
        mockk<TurnkeyClient>().also {
            coEvery { it.signTransaction(any(), any()) } returns signedTx
        }

    @Test
    fun `stamps the Turnkey activity body, not the raw unsigned transaction`() =
        runTest {
            val bodySlot = slot<String>()
            val stamp =
                mockk<Stamp>().also {
                    coEvery { it.stamp(any(), capture(bodySlot)) } returns
                        StampResult("X-Stamp", "STAMP_VALUE")
                }
            signStepInternal(cfg, step, SignStepOptions(), stamp, stubTurnkey())

            val stampedBody = Json.parseToJsonElement(bodySlot.captured).jsonObject
            stampedBody["type"]!!.jsonPrimitive.content shouldBe "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
            stampedBody["organizationId"]!!.jsonPrimitive.content shouldBe cfg.enclaveId
            val params = stampedBody["parameters"]!!.jsonObject
            params["signWith"]!!.jsonPrimitive.content shouldBe step.signWith
            params["unsignedTransaction"]!!.jsonPrimitive.content shouldBe step.unsignedTransaction
            params["type"]!!.jsonPrimitive.content shouldBe "TRANSACTION_TYPE_ETHEREUM"
        }

    @Test
    fun `forwards the stamped body and stamp header to Turnkey`() =
        runTest {
            val bodySlot = slot<String>()
            val stampSlot = slot<StampResult>()
            val turnkey =
                mockk<TurnkeyClient>().also {
                    coEvery { it.signTransaction(capture(bodySlot), capture(stampSlot)) } returns "0xabc"
                }
            signStepInternal(cfg, step, SignStepOptions(), stubStamp(), turnkey)

            bodySlot.captured shouldContain "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
            bodySlot.captured shouldContain step.unsignedTransaction
            stampSlot.captured.stampHeaderName shouldBe "X-Stamp"
            stampSlot.captured.stampHeaderValue shouldBe "FAKE_STAMP_VALUE"
        }

    @Test
    fun `returns the Turnkey signed transaction as the signature`() =
        runTest {
            val result =
                signStepInternal(cfg, step, SignStepOptions(), stubStamp(), stubTurnkey("0xfeedface"))
            result.signature shouldBe "0xfeedface"
        }

    @Test
    fun `echoes the unsignedTransaction on the result`() =
        runTest {
            val result = signStepInternal(cfg, step, SignStepOptions(), stubStamp(), stubTurnkey())
            result.unsignedTransaction shouldBe step.unsignedTransaction
        }

    @Test
    fun `metadata threads through stamp header values and the stamped body`() =
        runTest {
            val result = signStepInternal(cfg, step, SignStepOptions(), stubStamp(), stubTurnkey())
            result.metadata.stampHeaderName shouldBe "X-Stamp"
            result.metadata.stampHeaderValue shouldBe "FAKE_STAMP_VALUE"
            // body in metadata is the Turnkey activity, not the raw unsigned tx
            result.metadata.body shouldContain "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
            result.metadata.body shouldContain step.unsignedTransaction
        }

    @Test
    fun `Solana network maps to TRANSACTION_TYPE_SOLANA`() =
        runTest {
            val solanaStep =
                step.copy(
                    network = "SOLANA",
                    signWith = "9wXn6...solBase58Address",
                )
            val bodySlot = slot<String>()
            val stamp =
                mockk<Stamp>().also {
                    coEvery { it.stamp(any(), capture(bodySlot)) } returns
                        StampResult("X-Stamp", "STAMP_VALUE")
                }
            signStepInternal(cfg, solanaStep, SignStepOptions(), stamp, stubTurnkey())
            val params = Json.parseToJsonElement(bodySlot.captured).jsonObject["parameters"]!!.jsonObject
            params["type"]!!.jsonPrimitive.content shouldBe "TRANSACTION_TYPE_SOLANA"
        }

    @Test
    fun `unknown network throws ConfigError before any stamping or HTTP`() =
        runTest {
            val unknownNet = step.copy(network = "MARS_TESTNET")
            shouldThrow<TesserError.ConfigError> {
                signStepInternal(cfg, unknownNet, SignStepOptions(), stubStamp(), stubTurnkey())
            }
        }
}
