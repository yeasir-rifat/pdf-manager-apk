package com.pdfmanager.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.data.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface StageState {
    object Loading : StageState
    object Failed : StageState
    class Ready(val bitmap: Bitmap) : StageState
}

/**
 * A page of the PDF shown as it looks on screen. [content] draws on top of it and gets the pixel size of the
 * page area, so overlays (watermark, signature, marks) can be placed in fractions of the page.
 */
@Composable
fun PageStage(uri: Uri, page: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.(IntSize) -> Unit = {}) {
    val context = LocalContext.current
    val state by produceState<StageState>(StageState.Loading, uri, page) {
        val r = withContext(Dispatchers.IO) { PdfOps.renderPreview(context, uri, page, 900) }
        value = if (r == null) StageState.Failed else StageState.Ready(r.first)
    }
    when (val s = state) {
        StageState.Loading -> Box(modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        StageState.Failed -> FitText(
            "Preview is not available. The file may be locked.",
            modifier, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.error
        )
        is StageState.Ready -> {
            val bmp = s.bitmap
            val shape = RoundedCornerShape(6.dp)
            var size by remember { mutableStateOf(IntSize.Zero) }
            BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ratio = bmp.width.toFloat() / bmp.height
                val maxHeight = 460.dp
                val w = if (maxWidth / ratio <= maxHeight) maxWidth else maxHeight * ratio
                Box(
                    Modifier.width(w).aspectRatio(ratio).clip(shape).background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .onSizeChanged { size = it }
                ) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    content(size)
                }
            }
        }
    }
}

/** < Page 3 of 12 > */
@Composable
fun PageNav(page: Int, pages: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(enabled = page > 0, onClick = { onChange(page - 1) }) { Icon(AppIcons.KeyboardArrowLeft, "Previous page") }
        FitText(
            "Page ${page + 1} of $pages", Modifier.widthIn(min = 120.dp),
            MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center
        )
        IconButton(enabled = page < pages - 1, onClick = { onChange(page + 1) }) { Icon(AppIcons.KeyboardArrowRight, "Next page") }
    }
}

/** Label and value on one line, slider below. */
@Composable
fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FitText(label, Modifier.weight(1f), MaterialTheme.typography.bodyMedium)
            FitText(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value, onChange, valueRange = range)
    }
}
