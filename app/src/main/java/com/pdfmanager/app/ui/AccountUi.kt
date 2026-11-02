package com.pdfmanager.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.R
import com.pdfmanager.app.data.GoogleAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** Profile photos already downloaded in this run, so they are not fetched again. */
private object PhotoCache {
    private val map = HashMap<String, Bitmap>()

    suspend fun load(url: String): Bitmap? {
        map[url]?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        if (bmp != null) map[url] = bmp
        return bmp
    }
}

/** Round photo of the signed-in account, or its first letter while there is no photo. */
@Composable
private fun AccountAvatar(profile: GoogleAccount.Profile, size: Dp) {
    val photo by produceState<Bitmap?>(null, profile.photoUrl) { value = profile.photoUrl?.let { PhotoCache.load(it) } }
    val bmp = photo
    if (bmp != null) {
        Image(
            bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                profile.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Returns a function that opens Google sign-in. The account is kept in [GoogleAccount.profile], so every
 * screen that shows it updates by itself. A real failure shows a short message; backing out shows nothing.
 */
@Composable
fun rememberGoogleSignIn(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val r = GoogleAccount.fromIntent(result.data)
        if (r.profile == null && r.error != null) Toast.makeText(ctx, r.error, Toast.LENGTH_LONG).show()
    }
    return remember(launcher, ctx) { { launcher.launch(GoogleAccount.client(ctx).signInIntent) } }
}

/**
 * Round button for the top right of the Home header. Signed out: tapping it opens Google sign-in.
 * Signed in: it shows the account photo, and tapping it opens the account with Sign out.
 */
@Composable
fun ProfileButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val profile by GoogleAccount.profile(ctx).collectAsState()
    var showAccount by remember { mutableStateOf(false) }
    val startSignIn = rememberGoogleSignIn()
    val p = profile
    val colors = AppTheme.colors
    Box(
        modifier.size(40.dp).clip(CircleShape)
            .clickable(role = Role.Button) {
                if (p == null) startSignIn() else showAccount = true
            },
        contentAlignment = Alignment.Center
    ) {
        if (p != null) {
            AccountAvatar(p, 36.dp)
        } else {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(colors.toolCard)
                    .border(AppDimens.hairline, colors.cardBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Person, contentDescription = stringResource(R.string.account_sign_in),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)
                )
            }
        }
    }
    if (showAccount && p != null) {
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { showAccount = false },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { AccountAvatar(p, 72.dp) },
            title = { Text(p.name, textAlign = TextAlign.Center) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        p.email, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccount = false
                    GoogleAccount.signOut(ctx) { }
                }) { Text(stringResource(R.string.account_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { showAccount = false }) { Text(stringResource(R.string.account_close)) } }
        )
    }
}
