package xyz.tesser.sdk

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SignedStepResultTest {
    @Test
    fun `data class exposes signature, unsignedTransaction, and metadata`() {
        val meta = SignedStepResultMetadata("X-Stamp", "STAMP_VALUE", "0xdeadbeef")
        val result = SignedStepResult(signature = "BASE64_SIG", unsignedTransaction = "0xdeadbeef", metadata = meta)
        result.signature shouldBe "BASE64_SIG"
        result.unsignedTransaction shouldBe "0xdeadbeef"
        result.metadata shouldBe meta
    }

    @Test
    fun `metadata is structurally equal when all three fields match`() {
        val a = SignedStepResultMetadata("X-Stamp", "v", "b")
        val b = SignedStepResultMetadata("X-Stamp", "v", "b")
        val c = SignedStepResultMetadata("X-Stamp", "v", "different")
        (a == b) shouldBe true
        (a == c) shouldBe false
    }
}
