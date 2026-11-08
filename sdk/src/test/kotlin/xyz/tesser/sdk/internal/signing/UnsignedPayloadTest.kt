package xyz.tesser.sdk.internal.signing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.WalletType
import xyz.tesser.sdk.error.TesserError
import java.io.IOException
import java.net.ConnectException
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.Base64

class UnsignedPayloadTest {
    private val cfg =
        SigningConfig(
            publicKey = "02".repeat(33),
            privateKey = "01".repeat(32),
            enclaveId = UnsignedPayloadFixtures.ORGANIZATION_ID,
        )

    private val ethereumParams =
        CreateWalletParams(
            name = UnsignedPayloadFixtures.WALLET_NAME,
            type = WalletType.STABLECOIN_ETHEREUM,
        )

    private val stampedMessages = mutableListOf<String>()

    private val stamp: Stamp =
        mockk<Stamp>().also {
            coEvery { it.stamp(any(), capture(stampedMessages)) } returns StampResult("X-Stamp", "STAMP_VALUE")
        }

    private fun connection(httpClient: HttpClient): TesserApiConnection = TesserApiConnection.create("https://api.tesser.xyz", httpClient)

    private fun clientReturningEthereumFixture(): StubHttpClient =
        StubHttpClient.respondingWith(StubHttpResponse(200, UnsignedPayloadFixtures.loadEthereumResponse()))

    private fun clientReturningBody(body: String): StubHttpClient =
        StubHttpClient.respondingWith(StubHttpResponse(200, UnsignedPayloadFixtures.responseWithBody(body)))

    private fun queryParameters(request: HttpRequest): Map<String, String> =
        request.uri().rawQuery.split("&").associate { pair ->
            val (key, value) = pair.split("=", limit = 2)
            key to URLDecoder.decode(value, Charsets.UTF_8)
        }

    @Test
    fun `Ethereum stamps the returned body byte for byte`() =
        runTest {
            val result = signCreateWalletInternal(cfg, ethereumParams, stamp, connection(clientReturningEthereumFixture()))
            val expectedBody = UnsignedPayloadFixtures.loadEthereumBody()
            result.metadata.body shouldBe expectedBody
            stampedMessages[1] shouldBe expectedBody
            val composite = Json.parseToJsonElement(String(Base64.getDecoder().decode(result.signature))).jsonObject
            composite["body"]!!.jsonPrimitive.content shouldBe expectedBody
            composite["stamp"]!!.jsonPrimitive.content shouldBe "STAMP_VALUE"
        }

    @Test
    fun `Ethereum sends one stamped GET to the unsigned-payload endpoint`() =
        runTest {
            val httpClient = clientReturningEthereumFixture()
            signCreateWalletInternal(cfg, ethereumParams, stamp, connection(httpClient))
            httpClient.requests shouldHaveSize 1
            val request = httpClient.requests.single()
            request.method() shouldBe "GET"
            request.uri().path shouldBe "/v1/accounts/wallets/unsigned-payload"
            request.timeout().get() shouldBe Duration.ofSeconds(10)
            request.headers().firstValue("X-Stamp").get() shouldBe "STAMP_VALUE"
            request.headers().firstValue("User-Agent").get() shouldMatch Regex("tesser-sdk-kotlin/\\d+\\.\\d+\\.\\d+.*")
            val query = queryParameters(request)
            query.keys shouldBe setOf("organizationId", "name", "type", "timestampMs")
            query["organizationId"] shouldBe UnsignedPayloadFixtures.ORGANIZATION_ID
            query["name"] shouldBe UnsignedPayloadFixtures.WALLET_NAME
            query["type"] shouldBe "stablecoin_ethereum"
            query["timestampMs"]!! shouldMatch Regex("[1-9][0-9]{12}")
        }

    @Test
    fun `Ethereum stamps the canonical message with the query values`() =
        runTest {
            val httpClient = clientReturningEthereumFixture()
            signCreateWalletInternal(cfg, ethereumParams, stamp, connection(httpClient))
            val timestampMs = queryParameters(httpClient.requests.single())["timestampMs"]
            stampedMessages[0] shouldBe
                "{\"organizationId\":\"${UnsignedPayloadFixtures.ORGANIZATION_ID}\"," +
                "\"name\":\"${UnsignedPayloadFixtures.WALLET_NAME}\"," +
                "\"type\":\"stablecoin_ethereum\"," +
                "\"timestampMs\":\"$timestampMs\"}"
        }

    @Test
    fun `query values are percent-encoded`() =
        runTest {
            val name = "my wallet+1"
            val body = UnsignedPayloadFixtures.loadEthereumBody().replace(UnsignedPayloadFixtures.WALLET_NAME, name)
            val httpClient = clientReturningBody(body)
            signCreateWalletInternal(cfg, ethereumParams.copy(name = name), stamp, connection(httpClient))
            httpClient.requests.single().uri().rawQuery shouldContain "name=my%20wallet%2B1"
        }

    @Test
    fun `payload with another activity type is rejected before stamping`() =
        assertBodyRejected(
            UnsignedPayloadFixtures.loadEthereumBody().replace(
                "ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS",
                "ACTIVITY_TYPE_CREATE_WALLET",
            ),
        )

    @Test
    fun `payload for another organization is rejected before stamping`() =
        assertBodyRejected(
            UnsignedPayloadFixtures.loadEthereumBody().replace(UnsignedPayloadFixtures.ORGANIZATION_ID, "other-org"),
        )

    @Test
    fun `payload with another account name is rejected before stamping`() =
        assertBodyRejected(
            UnsignedPayloadFixtures.loadEthereumBody().replace(UnsignedPayloadFixtures.WALLET_NAME, "other-name"),
        )

    @Test
    fun `payload with a non-Ethereum address format is rejected before stamping`() =
        assertBodyRejected(
            UnsignedPayloadFixtures.loadEthereumBody().replace("ADDRESS_FORMAT_ETHEREUM", "ADDRESS_FORMAT_SOLANA"),
        )

    @Test
    fun `payload with two accounts is rejected before stamping`() {
        val body = UnsignedPayloadFixtures.loadEthereumBody()
        val account = body.substring(body.indexOf("[") + 1, body.lastIndexOf("]"))
        assertBodyRejected(body.replace(account, "$account,$account"))
    }

    @Test
    fun `payload that is not JSON is rejected before stamping`() = assertBodyRejected("not json")

    private fun assertBodyRejected(body: String) =
        runTest {
            val ex =
                shouldThrow<TesserError.SigningError> {
                    signCreateWalletInternal(cfg, ethereumParams, stamp, connection(clientReturningBody(body)))
                }
            ex.message!! shouldContain "Unsigned payload rejected"
            stampedMessages shouldHaveSize 1
        }

    @Test
    fun `non-2xx response becomes APIError with status, request id and error codes`() =
        runTest {
            val response =
                StubHttpResponse(
                    status = 401,
                    responseBody = """{"errors":[{"error_code":"signing-4010","error_message":"Invalid stamp"}]}""",
                    responseHeaders = mapOf("x-request-id" to listOf("req_123")),
                )
            val ex =
                shouldThrow<TesserError.APIError> {
                    signCreateWalletInternal(cfg, ethereumParams, stamp, connection(StubHttpClient.respondingWith(response)))
                }
            ex.status shouldBe 401
            ex.requestId shouldBe "req_123"
            ex.errorCode shouldBe "signing-4010"
            ex.message!! shouldContain "Invalid stamp"
            stampedMessages shouldHaveSize 1
        }

    @Test
    fun `APIError request id prefers the gateway zp-rid header`() =
        runTest {
            val response =
                StubHttpResponse(
                    status = 401,
                    responseBody = """{"errors":[{"error_code":"signing-4010","error_message":"Invalid stamp"}]}""",
                    responseHeaders =
                        mapOf(
                            "zp-rid" to listOf("622a8b5e-c713-4564-894f-a160e321e6f3"),
                            "x-request-id" to listOf("req_123"),
                        ),
                )
            val ex =
                shouldThrow<TesserError.APIError> {
                    signCreateWalletInternal(cfg, ethereumParams, stamp, connection(StubHttpClient.respondingWith(response)))
                }
            ex.requestId shouldBe "622a8b5e-c713-4564-894f-a160e321e6f3"
        }

    @Test
    fun `non-JSON error response becomes APIError without error codes`() =
        runTest {
            val response = StubHttpResponse(status = 502, responseBody = "Bad Gateway")
            val ex =
                shouldThrow<TesserError.APIError> {
                    signCreateWalletInternal(cfg, ethereumParams, stamp, connection(StubHttpClient.respondingWith(response)))
                }
            ex.status shouldBe 502
            ex.errors.shouldBeEmpty()
        }

    @Test
    fun `2xx response without a body field becomes APIError`() =
        runTest {
            val response = StubHttpResponse(status = 200, responseBody = """{"data":{"organization_id":"x"}}""")
            shouldThrow<TesserError.APIError> {
                signCreateWalletInternal(cfg, ethereumParams, stamp, connection(StubHttpClient.respondingWith(response)))
            }.status shouldBe 200
        }

    @Test
    fun `timeout becomes TimeoutError`() =
        runTest {
            val httpClient = StubHttpClient.failingWith(HttpTimeoutException("request timed out"))
            shouldThrow<TesserError.TimeoutError> {
                signCreateWalletInternal(cfg, ethereumParams, stamp, connection(httpClient))
            }
        }

    @Test
    fun `transport failure becomes ConnectionError`() =
        runTest {
            val httpClient = StubHttpClient.failingWith(IOException("connection reset"))
            shouldThrow<TesserError.ConnectionError> {
                signCreateWalletInternal(cfg, ethereumParams, stamp, connection(httpClient))
            }
        }

    @Test
    fun `transport failure without a message names the underlying failure`() =
        runTest {
            val unresolvedHost = ConnectException().apply { initCause(UnresolvedAddressException()) }
            val httpClient = StubHttpClient.failingWith(unresolvedHost)
            val ex =
                shouldThrow<TesserError.ConnectionError> {
                    signCreateWalletInternal(cfg, ethereumParams, stamp, connection(httpClient))
                }
            ex.message shouldBe "Unsigned payload request failed: UnresolvedAddressException"
        }

    @Test
    fun `Solana builds the v1 body locally without HTTP`() =
        runTest {
            val httpClient = clientReturningEthereumFixture()
            val result =
                signCreateWalletInternal(
                    cfg,
                    CreateWalletParams("sol", WalletType.STABLECOIN_SOLANA),
                    stamp,
                    connection(httpClient),
                )
            httpClient.requests.shouldBeEmpty()
            result.metadata.body shouldContain "ACTIVITY_TYPE_CREATE_WALLET\""
        }

    @Test
    fun `apiBaseUrl drops a trailing slash and v1 segment`() {
        normalizeApiBaseUrl("https://api.tesser.xyz") shouldBe "https://api.tesser.xyz"
        normalizeApiBaseUrl("https://api.tesser.xyz/") shouldBe "https://api.tesser.xyz"
        normalizeApiBaseUrl("https://api-sandbox.tesser.xyz/v1") shouldBe "https://api-sandbox.tesser.xyz"
        normalizeApiBaseUrl("https://api-sandbox.tesser.xyz/v1/") shouldBe "https://api-sandbox.tesser.xyz"
    }

    @Test
    fun `apiBaseUrl without an http scheme is rejected`() {
        shouldThrow<IllegalArgumentException> { normalizeApiBaseUrl("api.tesser.xyz") }
        shouldThrow<IllegalArgumentException> { normalizeApiBaseUrl("") }
    }

    @Test
    fun `apiBaseUrl over plain http is rejected unless the host is loopback`() {
        shouldThrow<IllegalArgumentException> { normalizeApiBaseUrl("http://api.tesser.xyz") }
        normalizeApiBaseUrl("http://localhost:3001/v1") shouldBe "http://localhost:3001"
        normalizeApiBaseUrl("http://127.0.0.1:3001") shouldBe "http://127.0.0.1:3001"
        normalizeApiBaseUrl("http://[::1]:3001") shouldBe "http://[::1]:3001"
    }
}
