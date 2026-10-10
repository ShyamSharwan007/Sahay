package com.sahay.app.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.sahay.R
import com.sahay.core.contracts.SahayConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** The signed-in Firebase user, reduced to what onboarding needs. */
data class AuthUser(
    val uid: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
    val isAnonymous: Boolean,
)

sealed interface SignInResult {
    data class Success(val user: AuthUser) : SignInResult
    /** The user closed the Google sheet. Not an error, so the screen shows nothing alarming. */
    data object Cancelled : SignInResult
    data object NoGoogleAccount : SignInResult
    data object NoInternet : SignInResult
    data object Failed : SignInResult
}

interface AuthRepository {
    /** Already signed-in user (Google or guest), or null. Works offline. */
    val currentUser: AuthUser?
    /** [activityContext] must be an Activity: Credential Manager shows its sheet on top of it. */
    suspend fun signInWithGoogle(activityContext: Context): SignInResult
    suspend fun signInAsGuest(): SignInResult
    /** Signs out locally; works offline. */
    fun signOut()
}

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    @ApplicationContext private val appContext: Context,
) : AuthRepository {

    override val currentUser: AuthUser? get() = auth.currentUser?.toAuthUser()

    override suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        if (!isOnline()) return SignInResult.NoInternet
        return try {
            val idToken = requestGoogleIdToken(activityContext)
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val user = withTimeout(SahayConfig.NETWORK_TIMEOUT_MS) { auth.signInWithCredential(credential).await().user }
            user?.let { SignInResult.Success(it.toAuthUser()) } ?: SignInResult.Failed
        } catch (_: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (_: NoCredentialException) {
            SignInResult.NoGoogleAccount
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) SignInResult.NoInternet else throw e
        } catch (_: FirebaseNetworkException) {
            SignInResult.NoInternet
        } catch (_: GetCredentialException) {
            SignInResult.Failed
        } catch (_: GoogleIdTokenParsingException) {
            SignInResult.Failed
        } catch (_: Exception) {
            SignInResult.Failed
        }
    }

    override suspend fun signInAsGuest(): SignInResult {
        if (!isOnline()) return SignInResult.NoInternet
        return try {
            val user = withTimeout(SahayConfig.NETWORK_TIMEOUT_MS) { auth.signInAnonymously().await().user }
            user?.let { SignInResult.Success(it.toAuthUser()) } ?: SignInResult.Failed
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) SignInResult.NoInternet else throw e
        } catch (_: FirebaseNetworkException) {
            SignInResult.NoInternet
        } catch (_: Exception) {
            SignInResult.Failed
        }
    }

    override fun signOut() = auth.signOut()

    private suspend fun requestGoogleIdToken(activityContext: Context): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(appContext.getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
        val data = response.credential.data
        return GoogleIdTokenCredential.createFrom(data).idToken
    }

    private fun isOnline(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun FirebaseUser.toAuthUser() = AuthUser(
        uid = uid,
        displayName = displayName,
        email = email,
        photoUrl = photoUrl?.toString(),
        isAnonymous = isAnonymous,
    )
}
