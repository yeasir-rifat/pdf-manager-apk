package com.pdfmanager.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Asks the user for permission to use their own Google Drive (scope drive.file only: the app sees just the
 * files it makes itself). The access token lives in memory only, it is never saved. No client ID or secret
 * is needed: Google identifies the app by package name and signing key.
 */
object GoogleDriveAuth {

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

    fun playServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(OfficeHelpers.DRIVE_FILE_SCOPE)))
            .build()

    private fun outcomeOf(result: AuthorizationResult): Outcome {
        val pending = result.pendingIntent
        val token = result.accessToken
        return when {
            result.hasResolution() && pending != null -> Outcome.NeedsConsent(pending.intentSender)
            token != null -> Outcome.Token(token)
            else -> Outcome.Failed(MSG_NOT_GRANTED)
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
                .addOnFailureListener { onResult(Outcome.Failed(MSG_NOT_GRANTED)) }
        } catch (e: Exception) {
            onResult(Outcome.Failed(MSG_NOT_GRANTED))
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
            null
        }
    }

    /** Reads the answer that came back from Google's consent screen. */
    fun fromIntent(context: Context, data: Intent?): Outcome =
        try {
            outcomeOf(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
        } catch (e: Exception) {
            Outcome.Failed(MSG_NOT_GRANTED)
        }
}
