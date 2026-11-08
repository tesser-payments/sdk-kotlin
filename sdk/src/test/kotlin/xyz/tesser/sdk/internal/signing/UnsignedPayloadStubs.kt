package xyz.tesser.sdk.internal.signing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

internal class StubHttpClient(
    private val respond: (HttpRequest) -> CompletableFuture<HttpResponse<String>>,
) : HttpClient() {
    val requests: MutableList<HttpRequest> = mutableListOf()

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = throw UnsupportedOperationException("StubHttpClient only supports sendAsync")

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        requests += request
        @Suppress("UNCHECKED_CAST")
        return respond(request) as CompletableFuture<HttpResponse<T>>
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)

    companion object {
        fun respondingWith(response: HttpResponse<String>): StubHttpClient = StubHttpClient { CompletableFuture.completedFuture(response) }

        fun failingWith(error: Throwable): StubHttpClient = StubHttpClient { CompletableFuture.failedFuture(error) }
    }
}

internal class StubHttpResponse(
    private val status: Int,
    private val responseBody: String,
    private val responseHeaders: Map<String, List<String>> = emptyMap(),
) : HttpResponse<String> {
    override fun statusCode(): Int = status

    override fun request(): HttpRequest = throw UnsupportedOperationException("StubHttpResponse has no request")

    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(responseHeaders) { _, _ -> true }

    override fun body(): String = responseBody

    override fun sslSession(): Optional<SSLSession> = Optional.empty()

    override fun uri(): URI = URI.create("https://api.tesser.xyz")

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

internal object UnsignedPayloadFixtures {
    const val ORGANIZATION_ID = "846abdbb-d019-4d97-9404-0f7cfc113b2c"
    const val WALLET_NAME = "sdk-kotlin-stg-1794113032"
    private const val ETHEREUM_RESPONSE = "/fixtures/unsigned-payload/create-wallet-accounts-ethereum.json"

    fun loadEthereumResponse(): String = UnsignedPayloadFixtures::class.java.getResource(ETHEREUM_RESPONSE)!!.readText(Charsets.UTF_8)

    fun loadEthereumBody(): String {
        val data = Json.parseToJsonElement(loadEthereumResponse()).jsonObject["data"]!!.jsonObject
        return data["body"]!!.jsonPrimitive.content
    }

    fun responseWithBody(body: String): String =
        buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("organization_id", ORGANIZATION_ID)
                    put("body", body)
                },
            )
        }.toString()
}
