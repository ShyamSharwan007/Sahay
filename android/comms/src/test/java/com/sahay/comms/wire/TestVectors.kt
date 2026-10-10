package com.sahay.comms.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** docs/wire_test_vectors.json, copied into test resources by the `copyWireVectors` Gradle task. Read-only. */
object TestVectors {
    data class Case(val wire: String, val valid: Boolean, val note: String)

    /** The vectors were signed at 1760000000; "now" is one minute later. */
    const val NOW_SEC = 1_760_000_060L
    val clock: Clock = Clock.fixed(Instant.ofEpochSecond(NOW_SEC), ZoneOffset.UTC)

    val publicKeyB64: String
    val cases: List<Case>

    init {
        val text = checkNotNull(TestVectors::class.java.getResource("/wire_test_vectors.json")) {
            "wire_test_vectors.json missing from test resources (Gradle task copyWireVectors)"
        }.readText(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject
        publicKeyB64 = root.getValue("publicKeyB64").jsonPrimitive.content
        cases = root.getValue("cases").jsonArray.map {
            val o = it.jsonObject
            Case(
                wire = o.getValue("wire").jsonPrimitive.content,
                valid = o.getValue("valid").jsonPrimitive.boolean,
                note = o.getValue("note").jsonPrimitive.content,
            )
        }
    }

    fun firstValid(type: String): String = cases.first { it.valid && it.wire.startsWith("SH1*$type*") }.wire

    fun verifier() = SignatureVerifier(activeKeyB64 = { publicKeyB64 }, fallbackKeyB64 = "")

    fun reader() = WireReader(WireCodec(clock), verifier())
}
