package xyz.tesser.sdk.internal.signing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.WalletType
import xyz.tesser.sdk.error.TesserError
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class EnvironmentDetectionTest {
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

    private val productionHost = URI(PRODUCTION_API_BASE_URL).host
    private val sandboxHost = URI(SANDBOX_API_BASE_URL).host
    private val stampCount = AtomicInteger()
    private val stampedMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val stamp: Stamp =
        mockk<Stamp>().also {
            coEvery { it.stamp(any(), capture(stampedMessages)) } answers {
                StampResult("X-Stamp", "STAMP_${stampCount.incrementAndGet()}")
            }
        }

    private fun okResponse(): HttpResponse<String> = StubHttpResponse(200, UnsignedPayloadFixtures.loadEthereumResponse())

    private fun okResponseWithBody(body: String): HttpResponse<String> =
        StubHttpResponse(
            200,
            UnsignedPayloadFixtures.responseWithBody(body),
        )

    private fun errorResponse(
        status: Int,
        errorCode: String?,
        requestId: String,
    ): HttpResponse<String> {
        var responseBody = "Error"
        if (errorCode != null) {
            responseBody = """{"errors":[{"error_code":"$errorCode","error_message":"Rejected"}]}"""
        }
        return StubHttpResponse(status, responseBody, mapOf("x-request-id" to listOf(requestId)))
    }

    private fun signingKeyRejected(requestId: String): HttpResponse<String> = errorResponse(401, "signing-4010", requestId)

    private fun routingByHost(responses: Map<String, () -> CompletableFuture<HttpResponse<String>>>): StubHttpClient =
        StubHttpClient { request ->
            val respond = responses[request.uri().host] ?: error("Unexpected host ${request.uri().host}")
            respond()
        }

    private fun answering(response: HttpResponse<String>): () -> CompletableFuture<HttpResponse<String>> =
        { CompletableFuture.completedFuture(response) }

    private fun failing(error: Throwable): () -> CompletableFuture<HttpResponse<String>> = { CompletableFuture.failedFuture(error) }

    private fun hosts(httpClient: StubHttpClient): List<String> = httpClient.requests.map { it.uri().host }

    private fun timestampMs(request: HttpRequest): String {
        val pair = request.uri().rawQuery.split("&").single { it.startsWith("timestampMs=") }
        return URLDecoder.decode(pair.removePrefix("timestampMs="), Charsets.UTF_8)
    }

    private suspend fun signEthereum(api: TesserApiConnection) = signCreateWalletInternal(cfg, ethereumParams, stamp, api)

    @Test
    fun `without apiBaseUrl a production key is fetched once from production and remembered`() =
        runTest {
            val httpClient = routingByHost(mapOf(productionHost to answering(okResponse())))
            val api = TesserApiConnection.create(null, httpClient)
            signEthereum(api)
            hosts(httpClient) shouldBe listOf(productionHost)
            api.resolvedBaseUrl shouldBe PRODUCTION_API_BASE_URL
        }

    @Test
    fun `a signing key rejected by production is fetched from sandbox with a fresh query and stamp`() =
        runTest {
            val httpClient =
                routingByHost(
                    mapOf(
                        productionHost to answering(signingKeyRejected("req_prod")),
                        sandboxHost to answering(okResponse()),
                    ),
                )
            val api = TesserApiConnection.create(null, httpClient)
            val result = signEthereum(api)
            hosts(httpClient) shouldBe listOf(productionHost, sandboxHost)
            val (productionRequest, sandboxRequest) = httpClient.requests
            productionRequest.headers().firstValue("X-Stamp").get() shouldBe "STAMP_1"
            sandboxRequest.headers().firstValue("X-Stamp").get() shouldBe "STAMP_2"
            stampedMessages[0] shouldBe canonicalMessage(timestampMs(productionRequest))
            stampedMessages[1] shouldBe canonicalMessage(timestampMs(sandboxRequest))
            result.metadata.body shouldBe UnsignedPayloadFixtures.loadEthereumBody()
            api.resolvedBaseUrl shouldBe SANDBOX_API_BASE_URL
        }

    private fun canonicalMessage(timestampMs: String): String =
        "{\"organizationId\":\"${UnsignedPayloadFixtures.ORGANIZATION_ID}\"," +
            "\"name\":\"${UnsignedPayloadFixtures.WALLET_NAME}\"," +
            "\"type\":\"stablecoin_ethereum\"," +
            "\"timestampMs\":\"$timestampMs\"}"

    @Test
    fun `a remembered environment is the only host called on later requests`() =
        runTest {
            val httpClient =
                routingByHost(
                    mapOf(
                        productionHost to answering(signingKeyRejected("req_prod")),
                        sandboxHost to answering(okResponse()),
                    ),
                )
            val api = TesserApiConnection.create(null, httpClient)
            signEthereum(api)
            signEthereum(api)
            hosts(httpClient) shouldBe listOf(productionHost, sandboxHost, sandboxHost)
        }

    @Test
    fun `production 401 with another error code does not fall back`() =
        assertProductionErrorNotRetried(
            errorResponse(401, "auth-4010", "req_prod"),
        )

    @Test
    fun `production 401 without an error envelope does not fall back`() =
        assertProductionErrorNotRetried(
            errorResponse(401, null, "req_prod"),
        )

    @Test
    fun `production 403 with the signing key error code does not fall back`() =
        assertProductionErrorNotRetried(errorResponse(403, "signing-4010", "req_prod"))

    @Test
    fun `production 429 does not fall back`() = assertProductionErrorNotRetried(errorResponse(429, null, "req_prod"))

    @Test
    fun `production 500 does not fall back`() = assertProductionErrorNotRetried(errorResponse(500, null, "req_prod"))

    @Test
    fun `production 502 does not fall back`() = assertProductionErrorNotRetried(errorResponse(502, null, "req_prod"))

    private fun assertProductionErrorNotRetried(response: HttpResponse<String>) =
        runTest {
            val httpClient = routingByHost(mapOf(productionHost to answering(response)))
            val api = TesserApiConnection.create(null, httpClient)
            val ex = shouldThrow<TesserError.APIError> { signEthereum(api) }
            ex.status shouldBe response.statusCode()
            ex.suppressed shouldHaveSize 0
            hosts(httpClient) shouldBe listOf(productionHost)
            api.resolvedBaseUrl.shouldBeNull()
        }

    @Test
    fun `production timeout does not fall back`() =
        runTest {
            val httpClient = routingByHost(mapOf(productionHost to failing(HttpTimeoutException("timed out"))))
            val api = TesserApiConnection.create(null, httpClient)
            shouldThrow<TesserError.TimeoutError> { signEthereum(api) }
            hosts(httpClient) shouldBe listOf(productionHost)
            api.resolvedBaseUrl.shouldBeNull()
        }

    @Test
    fun `production connection failure does not fall back`() =
        runTest {
            val httpClient = routingByHost(mapOf(productionHost to failing(IOException("connection reset"))))
            val api = TesserApiConnection.create(null, httpClient)
            shouldThrow<TesserError.ConnectionError> { signEthereum(api) }
            hosts(httpClient) shouldBe listOf(productionHost)
            api.resolvedBaseUrl.shouldBeNull()
        }

    @Test
    fun `production body that does not match the request is rejected without fallback`() =
        runTest {
            val mismatchedBody = UnsignedPayloadFixtures.loadEthereumBody().replace(UnsignedPayloadFixtures.WALLET_NAME, "other-name")
            val httpClient = routingByHost(mapOf(productionHost to answering(okResponseWithBody(mismatchedBody))))
            val api = TesserApiConnection.create(null, httpClient)
            shouldThrow<TesserError.SigningError> { signEthereum(api) }
            hosts(httpClient) shouldBe listOf(productionHost)
            api.resolvedBaseUrl.shouldBeNull()
        }

    @Test
    fun `a signing key rejected by both environments throws the production error`() =
        runTest {
            val httpClient =
                routingByHost(
                    mapOf(
                        productionHost to answering(signingKeyRejected("req_prod")),
                        sandboxHost to answering(signingKeyRejected("req_sandbox")),
                    ),
                )
            val api = TesserApiConnection.create(null, httpClient)
            val ex = shouldThrow<TesserError.APIError> { signEthereum(api) }
            ex.requestId shouldBe "req_prod"
            ex.headers["x-request-id"] shouldBe listOf("req_prod")
            ex.suppressed shouldHaveSize 1
            ex.suppressed[0].shouldBeInstanceOf<TesserError.APIError>().requestId shouldBe "req_sandbox"
            hosts(httpClient) shouldBe listOf(productionHost, sandboxHost)
            api.resolvedBaseUrl.shouldBeNull()
        }

    @Test
    fun `another sandbox error after a production rejection throws the sandbox error`() =
        runTest {
            val httpClient =
                routingByHost(
                    mapOf(
                        productionHost to answering(signingKeyRejected("req_prod")),
                        sandboxHost to answering(errorResponse(503, null, "req_sandbox")),
                    ),
                )
            val api = TesserApiConnection.create(null, httpClient)
            val ex = shouldThrow<TesserError.APIError> { signEthereum(api) }
            ex.status shouldBe 503
            ex.requestId shouldBe "req_sandbox"
            ex.suppressed shouldHaveSize 1
            ex.suppressed[0].shouldBeInstanceOf<TesserError.APIError>().requestId shouldBe "req_prod"
            api.resolvedBaseUrl.shouldBeNull()
        }

    @Test
    fun `an explicit apiBaseUrl is the only host called even when it is production`() =
        runTest {
            val httpClient =
                routingByHost(
                    mapOf(
                        productionHost to answering(signingKeyRejected("req_prod")),
                        sandboxHost to answering(okResponse()),
                    ),
                )
            val api = TesserApiConnection.create("https://api.tesser.xyz/v1", httpClient)
            val ex = shouldThrow<TesserError.APIError> { signEthereum(api) }
            ex.requestId shouldBe "req_prod"
            hosts(httpClient) shouldBe listOf(productionHost)
        }

    @Test
    fun `a remembered environment that later rejects the signing key does not fall back`() =
        runTest {
            val productionResponses = ArrayDeque(listOf(okResponse(), signingKeyRejected("req_prod")))
            val httpClient =
                routingByHost(
                    mapOf(
                        productionHost to { CompletableFuture.completedFuture(productionResponses.removeFirst()) },
                        sandboxHost to answering(okResponse()),
                    ),
                )
            val api = TesserApiConnection.create(null, httpClient)
            signEthereum(api)
            shouldThrow<TesserError.APIError> { signEthereum(api) }
            hosts(httpClient) shouldBe listOf(productionHost, productionHost)
            api.resolvedBaseUrl shouldBe PRODUCTION_API_BASE_URL
        }

    @Test
    fun `concurrent first requests both succeed and remember sandbox`() =
        runTest {
            val httpClient =
                routingByHost(
                    mapOf(
                        productionHost to answering(signingKeyRejected("req_prod")),
                        sandboxHost to answering(okResponse()),
                    ),
                )
            val api = TesserApiConnection.create(null, httpClient)
            val results =
                withContext(Dispatchers.Default) {
                    List(2) { async { signEthereum(api) } }.awaitAll()
                }
            results.forEach { it.metadata.body shouldBe UnsignedPayloadFixtures.loadEthereumBody() }
            api.resolvedBaseUrl shouldBe SANDBOX_API_BASE_URL
        }
}
