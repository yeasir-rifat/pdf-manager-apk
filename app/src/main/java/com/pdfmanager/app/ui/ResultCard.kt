package com.pdfmanager.app.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Name and size of a result file, read from its Uri. */
class ResultFileInfo(val name: String?, val sizeBytes: Long?)

/** Reads the display name and size of [uri]. Works for content:// and file:// Uris. */
fun readResultFileInfo(context: android.content.Context, uri: Uri): ResultFileInfo {
    if (uri.scheme == "file") {
        val f = uri.path?.let { File(it) }
        return ResultFileInfo(f?.name, f?.length())
    }
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) ResultFileInfo(
                c.getString(0),
                if (c.isNull(1)) null else c.getLong(1)
            ) else null
        }
    }.getOrNull() ?: ResultFileInfo(null, null)
}

/**
 * The finished-job card used by every tool: a green tick that scales in, the file name, the size
 * and three buttons (Open, Share, Done).
 *
 * [originalSizeBytes] is only set by Compress. Then the size line reads "4.2 MB to 1.1 MB",
 * otherwise it shows just the new size. [onOpen] and [onShare] may be null when the result cannot
 * be opened or shared; those buttons are then left out.
 */
@Composable
fun ResultCard(
    fileName: String?,
    sizeBytes: Long?,
    originalSizeBytes: Long?,
    message: String?,
    savedIn: String? = null,
    onOpen: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tint = Accent.Green
    val tickScale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        tickScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val sizeLine = when {
        sizeBytes == null -> null
        originalSizeBytes != null && originalSizeBytes > 0 ->
            context.getString(
                R.string.result_size_change,
                Formatter.formatShortFileSize(context, originalSizeBytes), Formatter.formatShortFileSize(context, sizeBytes)
            )
        else -> Formatter.formatShortFileSize(context, sizeBytes)
    }
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppDimens.cardRadius),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(AppDimens.space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp)
                        .graphicsLayer { scaleX = tickScale.value; scaleY = tickScale.value }
                        .clip(CircleShape).background(tint.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Check, null, tint = tint, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        fileName ?: stringResource(R.string.result_done),
                        style = AppText.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (sizeLine != null) {
                        FitText(
                            sizeLine,
                            style = AppText.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (!message.isNullOrBlank()) {
                Text(message, style = AppText.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The place has its own short line instead of making the message wrap
            if (savedIn != null) {
                FitText(
                    stringResource(R.string.result_saved_in, savedIn),
                    style = AppText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppDimens.space8)) {
                if (onOpen != null) {
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = AppDimens.space8)
                    ) { FitText(stringResource(R.string.result_open), style = MaterialTheme.typography.labelLarge) }
                }
                if (onShare != null) {
                    FilledTonalButton(
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = AppDimens.space8)
                    ) { FitText(stringResource(R.string.result_share), style = MaterialTheme.typography.labelLarge) }
                }
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = AppDimens.space8)
                ) { FitText(stringResource(R.string.result_done), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
