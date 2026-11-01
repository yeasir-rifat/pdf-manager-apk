package com.pdfmanager.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.pdfmanager.app.data.Thumbnails
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Icon on a softly tinted rounded square. Used everywhere so icons look consistent. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * First-page preview of a PDF (or an image). Shows a soft placeholder while it loads,
 * and the result is cached in memory and on disk.
 */
@Composable
fun PdfThumb(
    uri: String,
    modifier: Modifier = Modifier,
    stamp: String = "",
    isPdf: Boolean = true,
    widthPx: Int = 260,
    page: Int = 0,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)
) {
    val context = LocalContext.current
    // Render at exactly the size it is drawn at, so the picture is never scaled (scaling is what makes it blurry)
    var box by remember { mutableStateOf(IntSize.Zero) }
    val ready = box.width > 0 && box.height > 0
    val key = remember(uri, stamp, box, page) { Thumbnails.key(uri, stamp, box.width, box.height, page) }
    // Stays false until the load has ended, so a file that cannot be rendered shows the icon instead of shimmering forever
    var finished by remember(key) { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(if (ready) Thumbnails.peek(key) else null, key) {
        value = if (!ready) null
        else Thumbnails.peek(key) ?: Thumbnails.load(context, Uri.parse(uri), isPdf, box.width, box.height, stamp, page)
        if (ready) finished = true
    }
    Box(
        modifier.onSizeChanged { box = it }.clip(shape).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
        } else if (!ready || !finished) {
            Box(Modifier.fillMaxSize().shimmer())
        } else {
            Icon(
                if (isPdf) AppIcons.PictureAsPdf else AppIcons.Image,
                contentDescription = null,
                tint = Accent.Red.copy(alpha = 0.55f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** The one card style of the app: flat tonal surface, 20dp corners, thin hairline border (1.5dp primary border when selected). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(AppDimens.cardRadius)
    val colors = CardDefaults.cardColors(
        containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    )
    val border = if (highlighted) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    else BorderStroke(AppDimens.hairline, AppTheme.colors.cardBorder)
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Card(
            onClick = onClick, modifier = modifier.pressScale(interaction), shape = shape, colors = colors,
            border = border, interactionSource = interaction, content = content
        )
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Same size and shape as [PrimaryButton], for a second, less important action. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Friendly empty screen: icon on soft concentric circles, a title and one line of help. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String = "", modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(136.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)))
            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (message.isNotEmpty()) Spacer(Modifier.height(8.dp))
        if (message.isNotEmpty()) Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(28.dp))
            action()
        }
    }
}

/** A row of single-choice chips; scrolls sideways if it does not fit. */
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = selected == value
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label) },
                leadingIcon = if (isSelected) ({ Icon(AppIcons.Check, null, Modifier.size(18.dp)) }) else null,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

/**
 * Choose-a-file area. Empty: a dashed drop-zone style invitation. Filled: a card with the file's preview.
 */
@Composable
fun FilePickCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    accent: Color,
    detail: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    if (subtitle == null) {
        val line = accent.copy(alpha = 0.55f)
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(AppDimens.cardRadius))
                .clickable(onClick = onClick)
                .background(accent.copy(alpha = 0.06f))
                .drawBehind {
                    drawRoundRect(
                        line,
                        cornerRadius = CornerRadius(AppDimens.cardRadius.toPx()),
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
                    )
                }
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconBadge(icon, accent, size = 56.dp)
            Spacer(Modifier.height(12.dp))
            Text("Choose $title", style = MaterialTheme.typography.titleSmall)
        }
    } else {
        AppCard(Modifier.fillMaxWidth().animateContentSize(), onClick = onClick) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) leading() else IconBadge(icon, accent)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!detail.isNullOrBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(AppIcons.SwapHoriz, contentDescription = "Change file", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
