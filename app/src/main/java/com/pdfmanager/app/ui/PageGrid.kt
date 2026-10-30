package com.pdfmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

private const val COLUMNS = 3
private const val PAGE_RATIO = 0.72f
private val CellShape = RoundedCornerShape(8.dp)

/** The grids sit inside a scrolling tool screen, so they get a fixed height: about half the screen. */
@Composable
private fun gridHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

/** One page thumbnail with its number in the corner. [overlay] draws on top (selection mark etc.). */
@Composable
private fun PageCell(
    uri: String,
    page: Int,
    label: Int,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier.fillMaxWidth().aspectRatio(PAGE_RATIO)) {
        PdfThumb(uri, Modifier.fillMaxSize(), page = page, shape = CellShape)
        Box(
            Modifier.align(Alignment.BottomStart).padding(4.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            FitText("$label", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        overlay()
    }
}

/** Thumbnails of every page; tap to select. Selected pages get a colored frame and a mark. */
@Composable
fun PageSelectGrid(
    uri: String,
    pageCount: Int,
    selected: Set<Int>,
    tint: Color,
    modifier: Modifier = Modifier,
    markIcon: ImageVector = AppIcons.Check,
    onToggle: (Int) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        modifier = modifier.fillMaxWidth().height(gridHeight()),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(pageCount, key = { it }) { p ->
            val on = p in selected
            PageCell(uri, p, p + 1, Modifier.clip(CellShape).clickable { onToggle(p) }) {
                if (on) {
                    Box(
                        Modifier.fillMaxSize().clip(CellShape)
                            .background(tint.copy(alpha = 0.22f)).border(2.dp, tint, CellShape)
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                            .clip(CircleShape).background(tint),
                        contentAlignment = Alignment.Center
                    ) { Icon(markIcon, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
}

/**
 * Thumbnails in the current order. Long-press a page and drag it to a new place; the other pages
 * slide aside. [order] holds the original 0-based page numbers in their new order.
 */
@Composable
fun PageReorderGrid(uri: String, order: SnapshotStateList<Int>, modifier: Modifier = Modifier) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var dragKey by remember { mutableStateOf<Int?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }

    fun itemAt(pos: Offset) = state.layoutInfo.visibleItemsInfo.firstOrNull {
        pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width &&
            pos.y >= it.offset.y && pos.y < it.offset.y + it.size.height
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        modifier = modifier.fillMaxWidth().height(gridHeight()).pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val hit = itemAt(pos)
                    if (hit != null) {
                        dragKey = hit.key as Int
                        pointer = pos
                        grab = pos - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    val key = dragKey
                    if (key != null) {
                        pointer += amount
                        val target = itemAt(pointer)
                        if (target != null && target.key != key) {
                            val from = order.indexOf(key)
                            val to = order.indexOf(target.key as Int)
                            if (from >= 0 && to >= 0) order.add(to, order.removeAt(from))
                        }
                        val edge = 72.dp.toPx()
                        if (pointer.y < edge) scope.launch { state.scrollBy(-26f) }
                        else if (pointer.y > size.height - edge) scope.launch { state.scrollBy(26f) }
                    }
                },
                onDragEnd = { dragKey = null },
                onDragCancel = { dragKey = null }
            )
        },
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(order.size, key = { order[it] }) { index ->
            val page = order[index]
            val dragging = dragKey == page
            val itemModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer {
                    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == page }
                    translationX = pointer.x - grab.x - (info?.offset?.x ?: 0)
                    translationY = pointer.y - grab.y - (info?.offset?.y ?: 0)
                    scaleX = 1.06f
                    scaleY = 1.06f
                    alpha = 0.92f
                }
            } else {
                Modifier.animateItem()
            }
            PageCell(uri, page, index + 1, itemModifier.clip(CellShape)) {
                if (page != index) {
                    // Small dot on pages that are no longer where they started
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(9.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}
