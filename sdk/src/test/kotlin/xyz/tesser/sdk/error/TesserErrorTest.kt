package xyz.tesser.sdk.error

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class TesserErrorTest {
    @Test
    fun `ConfigError carries message and cause`() {
        val cause = IllegalArgumentException("root")
        val err = TesserError.ConfigError("bad input", cause)
        err.message shouldBe "bad input"
        err.cause shouldBe cause
        err.shouldBeInstanceOf<TesserError>()
    }

    @Test
    fun `APIError exposes status, headers, requestId, errors list`() {
        val err =
            TesserError.APIError(
                message = "401 Unauthorized",
                status = 401,
                headers = mapOf("request-id" to listOf("req_abc")),
                requestId = "req_abc",
                errors = listOf(TesserErrorDetail(errorCode = "AUTH_INVALID", errorMessage = "bad token")),
            )
        err.status shouldBe 401
        err.requestId shouldBe "req_abc"
        err.headers["request-id"]!!.first() shouldBe "req_abc"
        err.errors.first().errorCode shouldBe "AUTH_INVALID"
    }

    @Test
    fun `APIError headers default to empty map`() {
        val err = TesserError.APIError(message = "no headers", status = 500)
        err.headers shouldBe emptyMap()
    }

    @Test
    fun `APIError errorCode helper returns first errorCode`() {
        val err =
            TesserError.APIError(
                message = "fail",
                status = 400,
                errors =
                    listOf(
                        TesserErrorDetail(errorCode = "FIRST", errorMessage = "x"),
                        TesserErrorDetail(errorCode = "SECOND", errorMessage = "y"),
                    ),
            )
        err.errorCode shouldBe "FIRST"
    }

    @Test
    fun `APIError hasCode returns true if any errorCode matches`() {
        val err =
            TesserError.APIError(
                message = "fail",
                status = 400,
                errors =
                    listOf(
                        TesserErrorDetail(errorCode = "A", errorMessage = ""),
                        TesserErrorDetail(errorCode = "B", errorMessage = ""),
                    ),
            )
        err.hasCode("B") shouldBe true
        err.hasCode("X", "B") shouldBe true
        err.hasCode("Y") shouldBe false
    }

    @Test
    fun `APIError errorCode is null when errors list is empty`() {
        val err = TesserError.APIError(message = "no body", status = 502)
        err.errorCode shouldBe null
        err.errors shouldBe emptyList()
    }

    @Test
    fun `SigningError is a TesserError`() {
        val err: TesserError = TesserError.SigningError("stamp blew up")
        err.shouldBeInstanceOf<TesserError.SigningError>()
        err.shouldBeInstanceOf<TesserError>()
    }

    @Test
    fun `sealed when matches without an else branch`() {
        // Smoke test: confirms the hierarchy is sealed exhaustively.
        val err: TesserError = TesserError.ConfigError("x")
        val tag: String =
            when (err) {
                is TesserError.ConfigError -> "config"
                is TesserError.APIError -> "api"
                is TesserError.ConnectionError -> "conn"
                is TesserError.TimeoutError -> "timeout"
                is TesserError.SigningError -> "signing"
            }
        tag shouldBe "config"
    }
}
