package xyz.tesser.sdk

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepForSigningTest {
    @Test
    fun `data class round-trips through copy with field overrides`() {
        val step =
            StepForSigning(
                id = "step_abc",
                transferId = "reb_123",
                unsignedTransaction = "0x02ed81893a850165a0bc",
                signWith = "0xb909cbe4a348754b17b474df9f12ab8842020165",
                network = "BASE_SEPOLIA",
            )
        val renamed = step.copy(id = "step_xyz")
        renamed.id shouldBe "step_xyz"
        renamed.transferId shouldBe "reb_123"
        renamed.unsignedTransaction shouldBe "0x02ed81893a850165a0bc"
        renamed.signWith shouldBe "0xb909cbe4a348754b17b474df9f12ab8842020165"
        renamed.network shouldBe "BASE_SEPOLIA"
    }

    @Test
    fun `equality is structural across all fields`() {
        val a = StepForSigning("step_1", "reb_1", "0xaa", "0xb0", "ETHEREUM")
        val b = StepForSigning("step_1", "reb_1", "0xaa", "0xb0", "ETHEREUM")
        val c = StepForSigning("step_1", "reb_1", "0xaa", "0xb0", "BASE_SEPOLIA")
        (a == b) shouldBe true
        (a == c) shouldBe false
    }
}
