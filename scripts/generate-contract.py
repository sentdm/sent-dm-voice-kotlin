#!/usr/bin/env python3
"""Generates voice-client-core/src/main/kotlin/dm/sent/voice/core/Contract.kt from contract/voice-client.json.

Every state, event name, error code, timing value and telemetry name the SDK uses
comes from here, so the Kotlin SDK cannot drift from the other Voice Client SDKs.
Run it after changing the contract; CI runs it and fails on a diff.
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONTRACT = ROOT / "contract" / "voice-client.json"
OUT = ROOT / "voice-client-core" / "src" / "main" / "kotlin" / "dm" / "sent" / "voice" / "core" / "Contract.kt"


def const(name: str) -> str:
    """An UPPER_SNAKE Kotlin entry name for a contract value such as `noAnswer`, `register.completed` or `TOKEN_EXPIRED`."""
    if name.isupper():
        return name
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    return re.sub(r"[.\-]", "_", words).upper()


def enum(name: str, values, doc: str) -> str:
    lines = [f"/** {doc} */", f"public enum class {name}(public val wire: String) {{"]
    lines += [f'    {const(v)}("{v}"),' for v in values]
    lines.append(";")
    lines.append("")
    lines.append("    public companion object {")
    lines.append(f"        public fun fromWire(wire: String): {name}? = entries.firstOrNull {{ it.wire == wire }}")
    lines.append("    }")
    lines.append("}")
    return "\n".join(lines)


def main() -> None:
    c = json.loads(CONTRACT.read_text())
    version = c["version"]
    errors, client, call, token = c["errors"], c["client"], c["call"], c["token"]
    out = [
        f"// Generated from contract/voice-client.json {version} by scripts/generate-contract.py. Do not edit.",
        "",
        "package dm.sent.voice.core",
        "",
        "/** The Voice Client contract this SDK implements: the vocabulary every Sent Voice Client SDK shares. */",
        "public object Contract {",
        f'    public const val VERSION: String = "{version}"',
        "",
        "    /** Which call states a call may move to from each state. */",
        "    public val callTransitions: Map<CallState, List<CallState>> = mapOf(",
    ]
    for state, nexts in call["transitions"].items():
        out.append(f"        CallState.{const(state)} to listOf({', '.join('CallState.' + const(n) for n in nexts)}),")
    out += [
        "    )",
        "",
        "    /** The error classes the contract names, with the category and retriability of each. */",
        "    public val errorClasses: List<ErrorClass> = listOf(",
    ]
    for cls in errors["classes"]:
        out.append(
            f'        ErrorClass("{cls["name"]}", ErrorCode.{const(cls["code"])}, '
            f'ErrorCategory.{const(cls["category"])}, retriable = {str(cls["retriable"]).lower()}),'
        )
    out += [
        "    )",
        "",
        "    /** The fields a telemetry payload may carry. */",
        "    public val telemetryPayloadFields: List<String> = listOf("
        + ", ".join(f'"{f}"' for f in c["telemetry"]["payloadFields"])
        + ")",
        "}",
        "",
        enum("ClientState", client["states"], "The states a client moves through."),
        "",
        enum("ClientEventName", client["events"], "The events a client emits."),
        "",
        enum("CallState", call["states"], "The states a call moves through."),
        "",
        "/** The state a call starts in, by direction. */",
        "public fun CallState.Companion.initial(direction: CallDirection): CallState = when (direction) {",
        f"    CallDirection.INBOUND -> CallState.{const(call['initialState']['inbound'])}",
        f"    CallDirection.OUTBOUND -> CallState.{const(call['initialState']['outbound'])}",
        "}",
        "",
        "/** Whether the contract allows a call in this state to move to [next]. */",
        "public fun CallState.canTransitionTo(next: CallState): Boolean = Contract.callTransitions[this]?.contains(next) ?: false",
        "",
        "/** A state no call leaves. */",
        "public val CallState.isTerminal: Boolean get() = Contract.callTransitions[this]?.isEmpty() ?: true",
        "",
        "/** Who placed the call. */",
        'public enum class CallDirection(public val wire: String) { INBOUND("inbound"), OUTBOUND("outbound") }',
        "",
        enum("CallEventName", call["events"], "The events a call emits."),
        "",
        enum("InviteEventName", c["invite"]["events"], "The events an incoming-call invite emits."),
        "",
        enum("AudioEventName", c["audio"]["events"], "The events the audio layer emits."),
        "",
        enum("ErrorCode", errors["codes"], "The error codes an SDK error carries."),
        "",
        enum("ErrorCategory", errors["categories"], "The categories an SDK error belongs to."),
        "",
        "/** An error class the contract names. */",
        "public data class ErrorClass(val name: String, val code: ErrorCode, val category: ErrorCategory, val retriable: Boolean)",
        "",
        "/** When a voice token is refreshed and how failed fetches are retried, in milliseconds. */",
        "public object TokenTiming {",
        f"    public const val REFRESH_SHARE: Double = {token['refreshShare']}",
        f"    public const val REFRESH_MARGIN_MS: Long = {token['refreshMarginMs']}L",
        f"    public const val INITIAL_RETRY_DELAY_MS: Long = {token['initialRetryDelayMs']}L",
        f"    public const val MAX_RETRY_DELAY_MS: Long = {token['maxRetryDelayMs']}L",
        f"    public const val OFFLINE_RETRY_INTERVAL_MS: Long = {token['offlineRetryIntervalMs']}L",
        "}",
        "",
        enum("TelemetryEventName", c["telemetry"]["events"], "The events the SDK reports to Sent."),
        "",
    ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out))
    print(f"wrote {OUT.relative_to(ROOT)} from contract {version}")


if __name__ == "__main__":
    main()
