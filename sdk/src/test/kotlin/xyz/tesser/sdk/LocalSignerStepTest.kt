package xyz.tesser.sdk

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.internal.signing.Stamp
import xyz.tesser.sdk.internal.signing.StampResult
import xyz.tesser.sdk.internal.signing.TurnkeyClient

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

    private fun stubTurnkey(signedTx: String = "0xfeedface"): TurnkeyClient =
        mockk<TurnkeyClient>().also {
            coEvery { it.signTransaction(any(), any()) } returns signedTx
        }

    @Test
    fun `signStep returns the Turnkey signed transaction as the signature`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp(), stubTurnkey("0xabc123"))
            val result = signer.signStep(step)
            result.signature shouldBe "0xabc123"
            result.metadata.stampHeaderValue shouldBe "STAMP_VALUE"
        }

    @Test
    fun `signStep stamps a Turnkey ACTIVITY_TYPE_SIGN_TRANSACTION_V2 body`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp(), stubTurnkey())
            val result = signer.signStep(step)
            result.metadata.body shouldContain "ACTIVITY_TYPE_SIGN_TRANSACTION_V2"
            result.metadata.body shouldContain step.unsignedTransaction
            result.metadata.body shouldContain step.signWith
        }

    @Test
    fun `signStep echoes the unsignedTransaction back on the result`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp(), stubTurnkey())
            val result = signer.signStep(step)
            result.unsignedTransaction shouldBe step.unsignedTransaction
        }

    @Test
    fun `signStep accepts default SignStepOptions`() =
        runTest {
            val signer = LocalSigner(cfg, stubStamp(), stubTurnkey())
            // No `opts` argument; default ctor should be used.
            signer.signStep(step)
        }
}
