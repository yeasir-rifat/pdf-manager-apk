package com.pdfmanager.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfmanager.app.R

/** The five main tabs, in bar order. [route] is the navigation route each tab opens. */
enum class MainTab(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    Home("home", R.string.nav_home, AppIcons.Home),
    Files("files", R.string.nav_files, AppIcons.Folder),
    Tools("tools", R.string.nav_tools, AppIcons.Handyman),
    Scanner("scan", R.string.nav_scanner, AppIcons.DocumentScanner),
    Settings("settings", R.string.nav_settings, AppIcons.Settings);

    companion object {
        fun fromRoute(route: String?): MainTab? = entries.firstOrNull { it.route == route }
    }
}

/** Height of the bar's content, without the system gesture area below it. */
private val BarHeight = 80.dp
private val PillWidth = 64.dp
private val PillHeight = 36.dp

/**
 * The app-wide bottom navigation: five evenly spaced items, an icon above its label, and a soft pill
 * behind the selected icon. Its background also fills the gesture area under the bar.
 * Shown once by MainActivity, only on the five main tabs.
 */
@Composable
fun CustomBottomNavigationBar(
    selected: MainTab?,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AppTheme.colors.navBar)
            .navigationBarsPadding()
            .height(BarHeight),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainTab.entries.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NavItem(tab: MainTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    val label = stringResource(tab.labelRes)
    val source = remember { MutableInteractionSource() }
    val tint = if (selected) colors.navActive else colors.navInactive
    // The whole item is the touch target; the ripple is drawn on the pill only
    Column(
        modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = source,
                indication = null
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(width = PillWidth, height = PillHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selected) colors.navPill else Color.Transparent)
                .indication(source, LocalIndication.current),
            contentAlignment = Alignment.Center
        ) {
            // The label below names the tab, so the icon needs no description of its own
            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(
            label,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
