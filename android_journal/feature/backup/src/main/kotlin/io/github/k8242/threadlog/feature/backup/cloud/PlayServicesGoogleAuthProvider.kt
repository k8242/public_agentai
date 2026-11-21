@file:Suppress("DEPRECATION")

package io.github.k8242.threadlog.feature.backup.cloud

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import io.github.k8242.threadlog.core.model.contract.GoogleAuthProvider
import io.github.k8242.threadlog.core.model.contract.GoogleAuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Play Services implementation of [GoogleAuthProvider] restricted strictly to the
 * `https://www.googleapis.com/auth/drive.file` OAuth scope.
 *
 * Gracefully degrades to [GoogleAuthState.NotConfigured] whenever [GoogleOAuthConfig.webClientId]
 * is blank, and never throws uncaught exceptions on offline/CI environments without Play Services.
 */
@Singleton
class PlayServicesGoogleAuthProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val googleOAuthConfig: GoogleOAuthConfig,
) : GoogleAuthProvider {

    private val authState = MutableStateFlow(computeCurrentAuthState())

    override fun isConfigured(): Boolean = googleOAuthConfig.webClientId.isNotBlank()

    override fun observeAuthState(): Flow<GoogleAuthState> = authState.asStateFlow()

    override suspend fun getAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            authState.value = GoogleAuthState.NotConfigured
            return@withContext Result.failure(
                IllegalStateException(
                    "Google Cloud Sync is not configured. Set GOOGLE_WEB_CLIENT_ID to enable cloud sync.",
                ),
            )
        }

        val lastAccount = runCatching { GoogleSignIn.getLastSignedInAccount(context) }.getOrNull()
        val androidAccount = lastAccount?.account
        if (lastAccount == null || androidAccount == null) {
            authState.value = GoogleAuthState.SignedOut
            return@withContext Result.failure(
                IllegalStateException("Not signed in to a Google account."),
            )
        }

        runCatching {
            val token = GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_FILE_SCOPE")
            authState.value = GoogleAuthState.Connected(email = lastAccount.email)
            token
        }.onFailure {
            // Keep state accurate if Play Services is unavailable or token retrieval requires re-auth.
            authState.value = computeCurrentAuthState()
        }
    }

    override suspend fun signOut() {
        if (!isConfigured()) {
            authState.value = GoogleAuthState.NotConfigured
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val client = GoogleSignIn.getClient(context, buildSignInOptions())
                client.signOut()
            }
        }
        authState.value = GoogleAuthState.SignedOut
    }

    /**
     * Builds an optional Google Sign-In [Intent] scoped strictly to `drive.file`,
     * or returns `null` if OAuth is not configured.
     */
    fun createSignInIntent(): Intent? {
        if (!isConfigured()) return null
        return runCatching {
            GoogleSignIn.getClient(context, buildSignInOptions()).signInIntent
        }.getOrNull()
    }

    /**
     * Refreshes [observeAuthState] after an interactive sign-in activity completes.
     */
    fun refreshAuthState() {
        authState.value = computeCurrentAuthState()
    }

    private fun computeCurrentAuthState(): GoogleAuthState {
        if (!isConfigured()) {
            return GoogleAuthState.NotConfigured
        }
        val account: GoogleSignInAccount? = runCatching {
            GoogleSignIn.getLastSignedInAccount(context)
        }.getOrNull()

        return if (account != null && GoogleSignIn.hasPermissions(account, Scope(DRIVE_FILE_SCOPE))) {
            GoogleAuthState.Connected(email = account.email)
        } else {
            GoogleAuthState.SignedOut
        }
    }

    private fun buildSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .requestIdToken(googleOAuthConfig.webClientId)
            .build()

    companion object {
        const val DRIVE_FILE_SCOPE: String = "https://www.googleapis.com/auth/drive.file"
    }
}
