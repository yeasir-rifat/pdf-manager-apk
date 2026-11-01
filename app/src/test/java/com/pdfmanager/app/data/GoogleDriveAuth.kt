package com.pdfmanager.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Asks the user for permission to use their own Google Drive (scope drive.file only: the app sees just the
 * files it makes itself). The access token lives in memory only, it is never saved. No client ID or secret
 * is needed: Google identifies the app by package name and signing key (SHA-1), so an Android OAuth client
 * with exactly that package name and SHA-1 must exist in Google Cloud Console.
 */
object GoogleDriveAuth {

    private const val TAG = "DriveAuth"

    /** What one authorize call came back with. */
    sealed interface Outcome {
        /** Ready to use, valid for about an hour. */
        class Token(val accessToken: String) : Outcome

        /** The user must first agree on Google's own screen; launch [sender] and give the result to [fromIntent]. */
        class NeedsConsent(val sender: IntentSender) : Outcome

        /** Nothing to use. [message] is safe to show. */
        class Failed(val message: String) : Outcome
    }

    const val MSG_NOT_GRANTED = "Google access was not granted"
    const val MSG_NO_PLAY_SERVICES = "Google Play services are required"

    // Status codes from com.google.android.gms.common.api.CommonStatusCodes
    private const val CODE_NETWORK_ERROR = 7
    private const val CODE_DEVELOPER_ERROR = 10

    fun playServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(OfficeHelpers.DRIVE_FILE_SCOPE)))
            .build()

    /**
     * Turns an exception into a short message that still starts with [MSG_NOT_GRANTED] but also tells the
     * real reason, so a wrong OAuth setup can be told apart from a network problem or a cancelled screen.
     */
    internal fun failureMessage(e: Throwable?): String {
        if (e != null) Log.e(TAG, "Google authorization failed", e)
        val code = (e as? ApiException)?.statusCode ?: return MSG_NOT_GRANTED
        return when (code) {
            CODE_DEVELOPER_ERROR ->
                "$MSG_NOT_GRANTED (code $code: package name or SHA-1 does not match the OAuth client in Google Cloud)"
            CODE_NETWORK_ERROR ->
                "$MSG_NOT_GRANTED (code $code: no internet connection)"
            else -> "$MSG_NOT_GRANTED (code $code)"
        }
    }

    private fun outcomeOf(result: AuthorizationResult): Outcome {
        val pending = result.pendingIntent
        val token = result.accessToken
        return when {
            result.hasResolution() && pending != null -> Outcome.NeedsConsent(pending.intentSender)
            token != null -> Outcome.Token(token)
            else -> {
                Log.w(TAG, "Authorization returned neither a token nor a consent screen")
                Outcome.Failed(MSG_NOT_GRANTED)
            }
        }
    }

    /**
     * Gets an access token. If the user already agreed once this is silent; otherwise the answer is
     * [Outcome.NeedsConsent]. [onResult] is called on the main thread.
     */
    fun authorize(context: Context, onResult: (Outcome) -> Unit) {
        if (!playServicesAvailable(context)) {
            onResult(Outcome.Failed(MSG_NO_PLAY_SERVICES))
            return
        }
        try {
            Identity.getAuthorizationClient(context).authorize(request())
                .addOnSuccessListener { result -> onResult(outcomeOf(result)) }
                .addOnFailureListener { e -> onResult(Outcome.Failed(failureMessage(e))) }
        } catch (e: Exception) {
            onResult(Outcome.Failed(failureMessage(e)))
        }
    }

    /**
     * A token for use on a background thread: waits for the answer, so never call it on the main thread.
     * Used when Google answered 401 in the middle of a conversion. Returns null when the user would have to agree again.
     */
    fun tokenBlocking(context: Context): String? {
        if (!playServicesAvailable(context)) return null
        return try {
            val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 30, TimeUnit.SECONDS)
            (outcomeOf(result) as? Outcome.Token)?.accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Silent token refresh failed", e)
            null
        }
    }

    /** Reads the answer that came back from Google's consent screen. */
    fun fromIntent(context: Context, data: Intent?): Outcome =
        try {
            outcomeOf(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
        } catch (e: Exception) {
            Outcome.Failed(failureMessage(e))
        }
}
