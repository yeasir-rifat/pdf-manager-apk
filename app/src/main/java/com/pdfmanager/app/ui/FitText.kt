package com.pdfmanager.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Text that always stays on ONE line. When it does not fit the width it shrinks (down to
 * [minScale] of its size) and only then is cut with "...". It never wraps to a second line.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    minScale: Float = 0.6f
) {
    val base = style.fontSize.takeIf { it.isSpecified } ?: 14.sp
    var scale by remember(text, base) { mutableFloatStateOf(1f) }
    val atMin = scale <= minScale + 0.001f
    Text(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        style = style.copy(
            fontSize = base * scale,
            lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight
        ),
        maxLines = 1,
        softWrap = false,
        overflow = if (atMin) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { r ->
            if (r.didOverflowWidth && !atMin) scale = (scale * 0.9f).coerceAtLeast(minScale)
        }
    )
}
