package com.sahay.comms.wire

import android.util.Log
import javax.inject.Inject

/**
 * The one entry point for incoming wires from any channel (internet, SMS, mesh): parses the shape, then
 * verifies the signature of signed types. Returns null for anything that must be dropped.
 */
class WireReader @Inject constructor(
    private val codec: WireCodec,
    private val verifier: SignatureVerifier,
) {
    fun read(wire: String): WireMessage? {
        val trimmed = wire.trim()
        val message = when (val result = codec.parse(trimmed)) {
            is WireParseResult.Ok -> result.message
            is WireParseResult.Rejected -> {
                Log.w(TAG, "Dropped wire: ${result.error} ${result.detail}")
                return null
            }
        }
        if (message is WireMessage.Signed && !verifier.verify(trimmed)) {
            Log.w(TAG, "Dropped wire: bad signature")
            return null
        }
        return message
    }

    private companion object {
        const val TAG = "WireReader"
    }
}
