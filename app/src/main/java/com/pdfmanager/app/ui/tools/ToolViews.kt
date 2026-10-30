package com.pdfmanager.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.pdfmanager.app.ui.AppIcons
import com.pdfmanager.app.ui.AppTheme

private val CardShape = RoundedCornerShape(20.dp)

/**
 * The tools as a grid of cards: three columns on phones, as many as fit (about 120dp each) on wide
 * screens and in landscape. Every item has a stable key (the tool id).
 */
@Composable
fun ToolGrid(
    tools: List<ToolDef>,
    onToolClick: (ToolDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    LazyVerticalGrid(
        columns = if (compact) GridCells.Fixed(3) else GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(tools, key = { it.id }) { tool ->
            ToolCard(tool, onClick = { onToolClick(tool) })
        }
    }
}

/**
 * One tool: category-colored icon on a light lavender card, name below. The label area always
 * reserves two lines, so every card has the same height and the same icon position, whether
 * the name takes one line or two.
 */
@Composable
fun ToolCard(tool: ToolDef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 108.dp)
            .clip(CardShape)
            .background(colors.toolCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            tool.icon,
            contentDescription = null,
            tint = colors.glyph(tool.category.accent),
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.height(10.dp))
        CardLabel(
            stringResource(tool.nameRes),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.textPrimary
        )
    }
}

/**
 * Two-line, centered card label. If the name would need a third line, or a word would be split in
 * the middle (large font size, narrow screen), the text shrinks a little at a time (down to 75%)
 * so the name stays whole. The two-line height is always reserved, so all cards stay equal.
 */
@Composable
private fun CardLabel(text: String, style: TextStyle, color: Color) {
    val cfg = LocalConfiguration.current
    var scale by remember(text, cfg.screenWidthDp, cfg.fontScale) { mutableFloatStateOf(1f) }
    val atMin = scale <= 0.751f
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = if (atMin) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { r ->
            val splitWord = (0 until r.lineCount - 1).any { i ->
                val end = r.getLineEnd(i, visibleEnd = false)
                end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
            }
            if ((r.hasVisualOverflow || splitWord) && !atMin) scale = (scale * 0.92f).coerceAtLeast(0.75f)
        }
    )
}

/**
 * The same tools as category sections: a heading, then one row per tool (colored icon tile, name,
 * arrow). [tools] is already filtered; grouping follows the catalog's category order.
 */
@Composable
fun ToolList(
    tools: List<ToolDef>,
    onToolClick: (ToolDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = remember(tools) { ToolCatalog.groupByCategory(tools) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEachIndexed { index, (category, rows) ->
            item(key = "header-${category.name}") {
                Column {
                    if (index > 0) HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = AppTheme.colors.divider
                    )
                    ToolCategoryHeader(stringResource(category.titleRes))
                }
            }
            items(rows, key = { it.id }) { tool -> ToolListItem(tool, onClick = { onToolClick(tool) }) }
        }
    }
}

@Composable
fun ToolCategoryHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun ToolListItem(tool: ToolDef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val accent = tool.category.accent
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.tile(accent)),
            contentAlignment = Alignment.Center
        ) {
            Icon(tool.icon, contentDescription = null, tint = colors.onTile(accent), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(tool.nameRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Icon(AppIcons.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary)
    }
}
