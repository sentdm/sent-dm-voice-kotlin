package dm.sent.voice.core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One HTTP request, as the core makes them. */
public data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

/** The answer to one HTTP request. */
public data class HttpResponse(val status: Int, val body: ByteArray) {
    public val isSuccess: Boolean get() = status in 200..299
}

/** The one HTTP call the core makes, so tests and the app can supply their own transport. */
public interface HttpClient {
    public suspend fun send(request: HttpRequest): HttpResponse
}

/** The default transport, on the platform's [HttpURLConnection]. */
public class UrlConnectionHttpClient(private val timeoutMs: Int = 15_000) : HttpClient {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

/** Where the SDK talks to Sent. */
public object SentApi {
    public const val DEFAULT_BASE_URL: String = "https://api.sent.dm"

    public fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path
}
