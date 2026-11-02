package com.pdfmanager.app.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional Google sign-in: name, email and photo of the account, nothing more. The app works the
 * same when nobody is signed in. Google identifies the app by package name and signing key, the
 * same way it does for [GoogleDriveAuth].
 */
object GoogleAccount {
    class Profile(val name: String, val email: String, val photoUrl: String?)

    /** [profile] is set on success. [error] is set on a real failure and is null when the person simply backed out. */
    class SignInResult(val profile: Profile?, val error: String?)

    private const val CANCELED = 12501
    private const val CANCELED_ALT = 16

    private fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().build()

    fun client(c: Context) = GoogleSignIn.getClient(c, options())

    private val state = MutableStateFlow<Profile?>(null)
    @Volatile private var loaded = false

    /** The signed-in account, shared by every screen (Home button, Settings backup) so they never disagree. */
    fun profile(c: Context): StateFlow<Profile?> {
        if (!loaded) { state.value = current(c); loaded = true }
        return state
    }

    fun current(c: Context): Profile? = runCatching { GoogleSignIn.getLastSignedInAccount(c)?.toProfile() }.getOrNull()

    fun fromIntent(data: Intent?): SignInResult =
        try {
            val p = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java).toProfile()
            state.value = p
            loaded = true
            SignInResult(p, null)
        } catch (e: ApiException) {
            val quiet = e.statusCode == CANCELED || e.statusCode == CANCELED_ALT
            SignInResult(null, if (quiet) null else "Sign-in failed (code ${e.statusCode})")
        } catch (e: Exception) {
            SignInResult(null, if (data == null) null else "Sign-in failed")
        }

    fun signOut(c: Context, done: () -> Unit) {
        runCatching {
            client(c).signOut().addOnCompleteListener { state.value = null; loaded = true; done() }
        }.onFailure { state.value = null; loaded = true; done() }
    }

    private fun GoogleSignInAccount.toProfile() =
        Profile(displayName?.takeIf { it.isNotBlank() } ?: email.orEmpty(), email.orEmpty(), photoUrl?.toString())
}
