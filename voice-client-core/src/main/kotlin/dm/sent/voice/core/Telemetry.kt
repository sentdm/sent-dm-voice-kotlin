package dm.sent.voice.core

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** A value a telemetry payload field may hold. */
public sealed class TelemetryValue {
    public data class Text(val value: String) : TelemetryValue()
    public data class Decimal(val value: Double) : TelemetryValue()
    public data class Count(val value: Long) : TelemetryValue()
    public data class Flag(val value: Boolean) : TelemetryValue()

    internal fun json(): JsonElement = when (this) {
        is Text -> JsonPrimitive(value.take(Telemetry.MAX_TEXT_LENGTH))
        is Decimal -> JsonPrimitive(value)
        is Count -> JsonPrimitive(value)
        is Flag -> JsonPrimitive(value)
    }
}

/** One event the SDK reports. */
public data class TelemetryEvent(
    val type: TelemetryEventName,
    val callId: String? = null,
    val occurredAtMs: Long,
    val payload: Map<String, TelemetryValue> = emptyMap(),
) {
    internal fun json(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type.wire))
        callId?.let { put("call_id", JsonPrimitive(it)) }
        put("occurred_at", JsonPrimitive(Instant.ofEpochMilli(occurredAtMs).toString()))
        put("payload", JsonObject(payload.mapValues { (_, value) -> value.json() }))
    }
}

/**
 * Best-effort reporting to Sent: events queue, leave in batches a few seconds after the first one, and a
 * batch the API refuses or that never arrives is retried once with the next batch and then dropped.
 */
public class Telemetry(
    baseUrl: String = SentApi.DEFAULT_BASE_URL,
    private val sdkVersion: String = SentVoiceVersion.CURRENT,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val disabled: Boolean = false,
) {
    public companion object {
        public const val PATH: String = "/v3/voice/telemetry"
        /** A batch leaves this long after the first event queued for it. */
        public const val FLUSH_DELAY_MS: Long = 3_000
        /** A failed batch is retried with the next batch or, if nothing else happens, when this interval passes. */
        public const val FLUSH_INTERVAL_MS: Long = 30_000
        public const val MAX_EVENTS: Int = 100
        /** The Sent API refuses a body over 64 KiB. */
        public const val MAX_BODY_BYTES: Int = 60_000
        public const val MAX_TEXT_LENGTH: Int = 500

        internal fun body(entries: List<Entry>, sdkVersion: String): String = buildJsonObject {
            put("sdk_version", JsonPrimitive(sdkVersion))
            put("events", buildJsonArray { entries.forEach { add(it.event.json()) } })
        }.toString()

        /** Splits entries into batches that stay under the API's body limit. */
        internal fun split(entries: List<Entry>): List<List<Entry>> {
            val batches = mutableListOf<List<Entry>>()
            var current = mutableListOf<Entry>()
            var size = 0
            for (entry in entries) {
                val entrySize = entry.event.json().toString().toByteArray().size + 1
                if (current.isNotEmpty() && size + entrySize > MAX_BODY_BYTES) {
                    batches.add(current)
                    current = mutableListOf()
                    size = 0
                }
                current.add(entry)
                size += entrySize
            }
            if (current.isNotEmpty()) batches.add(current)
            return batches
        }
    }

    internal data class Entry(val event: TelemetryEvent, val retried: Boolean)

    private val url = SentApi.url(baseUrl, PATH)
    private val lock = Any()
    private var token: String? = null
    private var queue: MutableList<Entry> = mutableListOf()
    private var flushJob: Job? = null
    private var destroyed = false

    /** The voice token the reports are sent with. Events recorded before the first token wait for it. */
    public fun useToken(token: String) {
        synchronized(lock) { this.token = token }
        scheduleFlush(0)
    }

    public fun record(
        type: TelemetryEventName,
        callId: String? = null,
        payload: Map<String, TelemetryValue> = emptyMap(),
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (disabled) return
        val accepted = synchronized(lock) {
            if (destroyed) return@synchronized false
            queue.add(Entry(TelemetryEvent(type, callId, atMs, payload), retried = false))
            while (queue.size > MAX_EVENTS) queue.removeAt(0)
            true
        }
        if (accepted) scheduleFlush(FLUSH_DELAY_MS)
    }

    /** The events waiting to be sent, oldest first. */
    public val pending: List<TelemetryEvent>
        get() = synchronized(lock) { queue.map { it.event } }

    /** Sends every queued event now. Returns once every batch has been answered or has failed. */
    public suspend fun flush() {
        // Without a token the events stay queued: they are sent with the first token.
        val (current, entries) = synchronized(lock) {
            val held = this.token ?: return
            val taken = queue.toList()
            queue = mutableListOf()
            held to taken
        }
        if (entries.isEmpty()) return
        for (batch in split(entries)) send(current, batch)
    }

    /** Stops reporting; nothing queued after this is sent. */
    public fun destroy() {
        val job = synchronized(lock) {
            destroyed = true
            token = null
            queue = mutableListOf()
            flushJob.also { flushJob = null }
        }
        job?.cancel()
    }

    private fun scheduleFlush(delayMs: Long) {
        synchronized(lock) {
            if (destroyed || token == null || flushJob != null || queue.isEmpty()) return
            flushJob = scope.launch {
                if (delayMs > 0) delay(delayMs)
                synchronized(lock) { flushJob = null }
                flush()
                // Whatever is left (a retry) goes with the interval if nothing else happens.
                scheduleFlush(FLUSH_INTERVAL_MS)
            }
        }
    }

    private suspend fun send(token: String, batch: List<Entry>) {
        val request = HttpRequest(
            "POST",
            url,
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
            body(batch, sdkVersion).toByteArray(),
        )
        val ok = try {
            http.send(request).isSuccess
        } catch (e: Exception) {
            false
        }
        if (ok) return
        // Retried once, with the next batch; never after destroy.
        synchronized(lock) {
            if (destroyed) return
            val retries = batch.filter { !it.retried }.map { it.copy(retried = true) }
            queue = (retries + queue).takeLast(MAX_EVENTS).toMutableList()
        }
    }
}
