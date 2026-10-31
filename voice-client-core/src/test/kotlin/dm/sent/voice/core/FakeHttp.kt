package dm.sent.voice.core

/** An HTTP client that answers from a script and records what it was asked. */
internal class FakeHttp(answers: List<Result<HttpResponse>>) : HttpClient {
    private val answers = answers.toMutableList()
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        val next = if (answers.isEmpty()) Result.success(HttpResponse(200, "{}".toByteArray())) else answers.removeAt(0)
        return next.getOrThrow()
    }

    companion object {
        fun ok(json: String, status: Int = 200): Result<HttpResponse> = Result.success(HttpResponse(status, json.toByteArray()))
        fun unreachable(): Result<HttpResponse> = Result.failure(Unreachable())
    }
}

internal class Unreachable : Exception("unreachable")

internal const val EXCHANGE_BODY = """{"data":{"credential":"eyJ.credential","identity":"alice","number":"+15555550100","prefix":"acme_","issued_at":"2026-10-31T10:00:00.000Z","expires_at":"2026-10-31T11:00:00.000Z"}}"""

internal suspend fun expectCode(code: ErrorCode, body: suspend () -> Unit) {
    try {
        body()
        throw AssertionError("expected ${code.wire}")
    } catch (e: SentVoiceException) {
        if (e.code != code) throw AssertionError("expected ${code.wire}, got ${e.code.wire}: ${e.message}")
    }
}
