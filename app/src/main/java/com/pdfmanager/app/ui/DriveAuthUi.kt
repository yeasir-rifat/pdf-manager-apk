package com.pdfmanager.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.pdfmanager.app.data.GoogleDriveAuth
import com.pdfmanager.app.data.OfficeHelpers

/**
 * Returns a function that gets a Google Drive access token for [scope] (drive.file unless the backup asks for
 * drive.appdata); [accountEmail] pins it to one Google account. Call it from a button's onClick, before the
 * background job starts. [onToken] gets the token; [onMessage] gets a short text when permission was not given.
 * If Google needs the user's agreement first, its own screen opens and the result comes back here.
 */
@Composable
fun rememberDriveAuthorizer(
    onToken: (String) -> Unit,
    onMessage: (String) -> Unit,
    scope: String = OfficeHelpers.DRIVE_FILE_SCOPE,
    accountEmail: String? = null
): () -> Unit {
    val context = LocalContext.current
    val tokenCallback = rememberUpdatedState(onToken)
    val messageCallback = rememberUpdatedState(onMessage)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            messageCallback.value(GoogleDriveAuth.MSG_NOT_GRANTED)
        } else {
            when (val outcome = GoogleDriveAuth.fromIntent(context, result.data)) {
                is GoogleDriveAuth.Outcome.Token -> tokenCallback.value(outcome.accessToken)
                is GoogleDriveAuth.Outcome.Failed -> messageCallback.value(outcome.message)
                is GoogleDriveAuth.Outcome.NeedsConsent -> messageCallback.value(GoogleDriveAuth.MSG_NOT_GRANTED)
            }
        }
    }

    val scopeNow = rememberUpdatedState(scope)
    val accountNow = rememberUpdatedState(accountEmail)
    return remember(launcher, context) {
        {
            GoogleDriveAuth.authorize(context, scopeNow.value, accountNow.value) { outcome ->
                when (outcome) {
                    is GoogleDriveAuth.Outcome.Token -> tokenCallback.value(outcome.accessToken)
                    is GoogleDriveAuth.Outcome.Failed -> messageCallback.value(outcome.message)
                    is GoogleDriveAuth.Outcome.NeedsConsent ->
                        launcher.launch(IntentSenderRequest.Builder(outcome.sender).build())
                }
            }
        }
    }
}
