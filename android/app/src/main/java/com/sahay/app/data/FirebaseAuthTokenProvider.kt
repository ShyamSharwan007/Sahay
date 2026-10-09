package com.sahay.app.data

import com.google.firebase.auth.FirebaseAuth
import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.SahayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase-backed [AuthTokenProvider]. Never throws: offline or failing refreshes fall back to the last
 * token we saw for the same user, then to null (callers treat null as "send without auth / queue").
 */
@Singleton
class FirebaseAuthTokenProvider @Inject constructor(
    private val auth: FirebaseAuth,
) : AuthTokenProvider {

    private data class CachedToken(val uid: String, val token: String)

    @Volatile private var cached: CachedToken? = null

    override val uid: String? get() = auth.currentUser?.uid

    override suspend fun idToken(forceRefresh: Boolean): String? {
        val user = auth.currentUser ?: return null
        val last = cached?.takeIf { it.uid == user.uid }
        return try {
            val token = withTimeout(SahayConfig.NETWORK_TIMEOUT_MS) { user.getIdToken(forceRefresh).await().token }
            if (token != null) cached = CachedToken(user.uid, token)
            token ?: last?.token
        } catch (e: CancellationException) {
            // withTimeout throws a CancellationException subtype; only rethrow real cancellation.
            if (e is kotlinx.coroutines.TimeoutCancellationException) last?.token else throw e
        } catch (_: Exception) {
            last?.token
        }
    }
}
