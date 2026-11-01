package com.pdfmanager.app.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.R
import com.pdfmanager.app.data.PageCountCache
import com.pdfmanager.app.data.PdfEntity
import com.pdfmanager.app.data.RecentHelpers
import com.pdfmanager.app.data.RecentHelpers.AgoUnit

/** Page count of [pdf]: null while it is being read, 0 when the file cannot be read. Remembered across screens. */
@Composable
internal fun rememberPdfPageCount(pdf: PdfEntity): State<Int?> {
    val context = LocalContext.current.applicationContext
    val stamp = "${pdf.modifiedSec}-${pdf.sizeBytes}"
    return produceState(PageCountCache.peek(pdf.uri, stamp), pdf.uri, stamp) {
        value = PageCountCache.peek(pdf.uri, stamp) ?: PageCountCache.load(context, pdf.uri, stamp)
    }
}

/** "2 days ago", with plurals and digits taken from the current language. */
private fun agoText(res: Resources, ago: RecentHelpers.Ago): String = when (ago.unit) {
    AgoUnit.NOW -> res.getString(R.string.home_ago_now)
    AgoUnit.MINUTES -> res.getQuantityString(R.plurals.home_ago_minutes, ago.count, ago.count)
    AgoUnit.HOURS -> res.getQuantityString(R.plurals.home_ago_hours, ago.count, ago.count)
    AgoUnit.DAYS -> res.getQuantityString(R.plurals.home_ago_days, ago.count, ago.count)
    AgoUnit.WEEKS -> res.getQuantityString(R.plurals.home_ago_weeks, ago.count, ago.count)
    AgoUnit.MONTHS -> res.getQuantityString(R.plurals.home_ago_months, ago.count, ago.count)
    AgoUnit.YEARS -> res.getQuantityString(R.plurals.home_ago_years, ago.count, ago.count)
}

/** The small line under the name: "2 days ago · 12 pages". The pages part joins in once the count is known. */
private fun recentMeta(res: Resources, ago: RecentHelpers.Ago, pages: Int?): String {
    val time = agoText(res, ago)
    if (pages == null || pages <= 0) return time
    return res.getString(R.string.home_recent_meta, time, res.getQuantityString(R.plurals.home_pages, pages, pages))
}

/**
 * One row of the Recent files list on Home: thumbnail, one-line name, and a small line with how long
 * ago it was opened and its page count. Star and the same file menu as the Files screen sit on the right.
 */
@Composable
internal fun RecentFileCard(
    pdf: PdfEntity,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onUseInTool: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = AppTheme.colors
    val res = LocalContext.current.resources
    val config = LocalConfiguration.current
    val pages by rememberPdfPageCount(pdf)
    val meta = remember(pdf.lastOpened, pdf.modifiedSec, pages, config) {
        val opened = if (pdf.lastOpened > 0) pdf.lastOpened else pdf.modifiedSec
        recentMeta(res, RecentHelpers.ago(System.currentTimeMillis() / 1000, opened), pages)
    }
    var menu by remember { mutableStateOf(false) }

    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(start = AppDimens.space12, top = 10.dp, bottom = 10.dp, end = AppDimens.space4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PdfThumb(pdf.uri, Modifier.size(width = 52.dp, height = 68.dp), stamp = "${pdf.modifiedSec}-${pdf.sizeBytes}")
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    pdf.name,
                    style = AppText.subtitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(AppDimens.space4))
                FitText(meta, style = AppText.caption, color = colors.textSecondary)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (pdf.favorite) AppIcons.Star else AppIcons.StarBorder,
                    contentDescription = stringResource(if (pdf.favorite) R.string.home_fav_remove else R.string.home_fav_add),
                    tint = if (pdf.favorite) Accent.Amber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.home_more))
                }
                PdfMenu(menu, { menu = false }, onShare, onRename, onMove, onUseInTool, onPrint, onDelete)
            }
        }
    }
}
