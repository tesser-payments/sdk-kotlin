package xyz.tesser.sdk

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
import java.util.Base64

class LocalSignerStepTest {
    private val cfg =
        SigningConfig(
            publicKey = "02".repeat(33),
            privateKey = "01".repeat(32),
            enclaveId = "org_local_signer_step_test",
        )

    private val step =
        StepForSigning(
            id = "step_abc",
            transferId = "reb_123",
            unsignedTransaction = "0x02ed81893a850165a0bc",
            signWith = "0xb909cbe4a348754b17b474df9f12ab8842020165",
            network = "BASE_SEPOLIA",
        )

    private fun stubStamp(): Stamp =
        mockk<Stamp>().also {
            coEvery { it.stamp(any(), any()) } returns StampResult("X-Stamp", "STAMP_VALUE")
        }

    @Test
    fun `signStep returns a non-empty base64 signature`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp())
            val result = signer.signStep(step)
            result.signature.isNotBlank() shouldBe true
            result.metadata.stampHeaderValue shouldBe "STAMP_VALUE"
        }

    @Test
    fun `signStep payload body is a Turnkey activity referencing the unsigned transaction and signWith`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp())
            val result = signer.signStep(step)
            val composite = String(Base64.getDecoder().decode(result.signature))
            val body = Json.parseToJsonElement(composite).jsonObject["body"]!!.jsonPrimitive.content
            body shouldContain "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
            body shouldContain step.unsignedTransaction
            body shouldContain step.signWith
        }

    @Test
    fun `signStep echoes the unsignedTransaction back on the result`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp())
            val result = signer.signStep(step)
            result.unsignedTransaction shouldBe step.unsignedTransaction
        }

    @Test
    fun `signStep accepts default SignStepOptions`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp())
            // No `opts` argument; default ctor should be used.
            signer.signStep(step)
        }
}
