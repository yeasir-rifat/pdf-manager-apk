package com.pdfmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pdfmanager.app.R
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfmanager.app.data.AiBlock
import com.pdfmanager.app.data.AiMarkdownParser
import com.pdfmanager.app.data.AiSpan

/**
 * An answer from Gemini drawn with the app's own text styles, so Bangla and mixed text use the app fonts.
 * Every style keeps a line height of 1.4 times the font size or more, so Bangla marks are never clipped.
 * `[p. 12]` becomes a small chip, and a tap on it calls [onPage] with 12.
 */
@Composable
fun AiMarkdown(text: String, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(text) { AiMarkdownParser.parse(stripPageRefs(text)) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) BlockView(b, onPage)
    }
}

/** Answers carry no page references. Removes any `[p. 12]` the model still writes, and a half-streamed `[p.` at the end. */
fun stripPageRefs(text: String): String =
    text.replace(Regex("""\s*\[p\.?\s*\d*\]"""), "").replace(Regex("""\s*\[p?\.?\s*\d*$"""), "")

private fun TextStyle.roomy(): TextStyle = if (fontSize.isSp) copy(lineHeight = fontSize * 1.5f) else this

@Composable
private fun BlockView(b: AiBlock, onPage: (Int) -> Unit) {
    val body = MaterialTheme.typography.bodyLarge.roomy()
    when (b) {
        is AiBlock.Heading -> {
            val style = when (b.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }.roomy()
            SpanText(b.spans.map { if (it is AiSpan.Run) it.copy(bold = true) else it }, style, onPage, Modifier.padding(top = 4.dp))
        }
        is AiBlock.Paragraph -> SpanText(b.spans, body, onPage)
        is AiBlock.Bullet -> Row(Modifier.padding(start = (b.level.coerceAtMost(4) * 16).dp)) {
            Text(if (b.level == 0) "\u2022" else "\u2013", Modifier.width(18.dp), style = body)
            SpanText(b.spans, body, onPage, Modifier.weight(1f))
        }
        is AiBlock.Numbered -> Row(Modifier.padding(start = (b.level.coerceAtMost(4) * 16).dp)) {
            Text("${b.number}.", Modifier.widthIn(min = 26.dp), style = body, maxLines = 1)
            SpanText(b.spans, body, onPage, Modifier.weight(1f))
        }
        is AiBlock.Code -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState()).padding(12.dp)
        ) {
            Text(
                b.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp),
                softWrap = false,
            )
        }
        is AiBlock.Table -> TableView(b, onPage)
        AiBlock.Rule -> Box(Modifier.fillMaxWidth().height(AppDimens.hairline).background(AppTheme.colors.cardBorder))
    }
}

/** A simple table as aligned rows. Wide tables scroll sideways inside their own box. */
@Composable
private fun TableView(t: AiBlock.Table, onPage: (Int) -> Unit) {
    val cols = maxOf(t.header?.size ?: 0, t.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    val style = MaterialTheme.typography.bodyMedium.roomy()
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(AppDimens.hairline, AppTheme.colors.cardBorder, shape)
            .horizontalScroll(rememberScrollState())
    ) {
        t.header?.let { header ->
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                for (c in 0 until cols) {
                    val spans = (header.getOrNull(c) ?: emptyList()).map { if (it is AiSpan.Run) it.copy(bold = true) else it }
                    Box(Modifier.width(132.dp).padding(horizontal = 10.dp, vertical = 8.dp)) { SpanText(spans, style, onPage) }
                }
            }
        }
        t.rows.forEachIndexed { r, row ->
            if (r > 0 || t.header != null) Box(Modifier.fillMaxWidth().height(AppDimens.hairline).background(AppTheme.colors.cardBorder))
            Row {
                for (c in 0 until cols) {
                    Box(Modifier.width(132.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        SpanText(row.getOrNull(c) ?: emptyList(), style, onPage)
                    }
                }
            }
        }
    }
}

/** Width of a page chip in sp, so the line leaves room for it. */
private fun chipWidth(page: Int) = (page.toString().length * 8 + 30).sp

@Composable
private fun SpanText(
    spans: List<AiSpan>,
    style: TextStyle,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    // The app fonts are single files, so a weight or style inside a line cannot be picked by Compose.
    // Bold uses the bold font file (the family of the title styles), italic is a slant of the same glyphs.
    val boldFamily = MaterialTheme.typography.titleMedium.fontFamily
    val pages = LinkedHashSet<Int>()
    val annotated = buildAnnotatedString {
        for (sp in spans) when (sp) {
            is AiSpan.Run -> withStyle(
                SpanStyle(
                    fontWeight = if (sp.bold) FontWeight.Bold else null,
                    fontFamily = when {
                        sp.code -> FontFamily.Monospace
                        sp.bold -> boldFamily
                        else -> null
                    },
                    textGeometricTransform = if (sp.italic) TextGeometricTransform(scaleX = 1f, skewX = -0.22f) else null,
                    background = if (sp.code) codeBackground else Color.Unspecified,
                )
            ) { append(sp.text) }
            is AiSpan.PageRef -> { pages += sp.number; appendInlineContent("p${sp.number}", "[p. ${sp.number}]") }
        }
    }
    val inline = pages.associate { n ->
        "p$n" to InlineTextContent(
            Placeholder(width = chipWidth(n), height = 24.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
        ) { PageChip(n, onPage) }
    }
    Text(annotated, modifier, color = color, style = style, inlineContent = inline)
}

/** The small tappable chip for a page the answer mentions: `labelMedium`, 12 dp corners, `primaryContainer`. */
@Composable
private fun PageChip(page: Int, onPage: (Int) -> Unit) {
    val pageLabel = stringResource(R.string.ai_tag_page, page)
    Box(
        Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button) { onPage(page) }
            .semantics { contentDescription = pageLabel },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "p. $page", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1,
        )
    }
}
