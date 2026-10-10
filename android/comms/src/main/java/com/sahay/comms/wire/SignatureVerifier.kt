package com.sahay.comms.wire

import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Verify
import com.sahay.comms.BuildConfig
import com.sahay.core.contracts.PackRepository
import java.security.GeneralSecurityException
import java.util.Base64
import javax.inject.Inject

/**
 * Checks the Ed25519 signature of server-signed wires (docs/CONTRACTS.md §4).
 *
 * Key = the active pack's `publicKeyB64` when it is not empty, otherwise [fallbackKeyB64] (the production key).
 * [activeKeyB64] is read on every call because the active pack can change at any time.
 */
class SignatureVerifier(
    private val activeKeyB64: () -> String?,
    private val fallbackKeyB64: String,
) {

    @Inject constructor(packRepository: PackRepository) : this(
        activeKeyB64 = { packRepository.activePack.value?.publicKeyB64 },
        fallbackKeyB64 = BuildConfig.PRODUCTION_PUBLIC_KEY_B64,
    )

    // Building a verifier decodes the key; keep the last one (the key almost never changes).
    @Volatile private var cached: Pair<String, Ed25519Verify?>? = null

    /** True only if [wire]'s signature is valid for the signed portion of the string. Never throws. */
    fun verify(wire: String): Boolean {
        val star = wire.lastIndexOf('*')
        if (star < 0 || star == wire.length - 1) return false
        val verifier = verifierFor(currentKey()) ?: return false
        return try {
            val encoded = wire.substring(star + 1)
            val signature = Base64.getUrlDecoder().decode(encoded)
            // Refuse non-canonical base64 (spare bits set): one signature must have exactly one spelling.
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(signature) != encoded) return false
            val signed = WireCodec.signedPortion(wire).toByteArray(Charsets.UTF_8)
            verifier.verify(signature, signed)
            true
        } catch (e: GeneralSecurityException) {
            false                       // wrong signature
        } catch (e: IllegalArgumentException) {
            false                       // signature is not base64url
        }
    }

    private fun currentKey(): String = activeKeyB64()?.trim().takeUnless { it.isNullOrEmpty() } ?: fallbackKeyB64

    private fun verifierFor(keyB64: String): Ed25519Verify? {
        cached?.let { (key, verifier) -> if (key == keyB64) return verifier }
        val verifier = try {
            Ed25519Verify(Base64.getDecoder().decode(keyB64))
        } catch (e: IllegalArgumentException) {
            null.also { Log.w(TAG, "Public key is not valid base64 or not 32 bytes") }
        } catch (e: GeneralSecurityException) {
            null.also { Log.w(TAG, "Public key rejected: ${e.message}") }
        }
        cached = keyB64 to verifier
        return verifier
    }

    private companion object {
        const val TAG = "SignatureVerifier"
    }
}
