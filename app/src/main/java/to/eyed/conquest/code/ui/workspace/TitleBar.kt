package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.ui.theme.LocalZedTheme

// Zed: max(1.75rem, 34px) — 34 at the default 16px rem
// (crates/ui/src/utils/constants.rs:17-19).
private val TitleBarHeight = 34.dp

/** One entry in the menu: what it does, and the chord that also does it. */
data class MenuAction(
    val label: String,
    val shortcut: String?,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The workspace title bar, in the shape Zed uses: a menu button on the left,
 * then what you have open.
 *
 * Project- and file-level commands live here rather than in the status bar.
 * The status bar is for *state* — where the cursor is, what language, what
 * the panel is doing — and the top bar is for *actions*, which is both Zed's
 * split and the one that survives the soft keyboard covering the bottom of
 * the screen.
 */
@Composable
fun TitleBar(
    projectName: String?,
    filePath: String?,
    isDirty: Boolean,
    menuGroups: List<List<MenuAction>>,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(theme.color("title_bar.background", theme.color("tab_bar.background")))
            // Left only, as Zed's `pl_2`: the right end is a button group
            // that brings its own padding (title_bar/src/title_bar.rs:417).
            .padding(start = 8.dp),
    ) {
        Box {
            Text(
                text = "☰",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { menuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
            ) {
                // Scrollable, and it has to be: the menu has outgrown the
                // screen. Material's DropdownMenu clips to the window and does
                // not scroll on its own, so the last entries — settings, and
                // removing the userland — were simply unreachable on a
                // 674dp-tall window, and worse on a phone.
                // Bounded *and* scrollable, in that order: DropdownMenu
                // measures its content with an infinite maximum height, and a
                // scroller inside that throws. Capping it against the window
                // is what gives the scroller something finite to work with —
                // and without the cap the menu simply ran off the bottom of
                // the screen, taking settings and "remove userland" with it.
                val maxMenuHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
                Column(
                    modifier = Modifier
                        .widthIn(min = 260.dp)
                        .heightIn(max = maxMenuHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    menuGroups.forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        for (action in group) {
                            MenuRow(action) { menuOpen = false }
                        }
                    }
                }
            }
        }

        if (projectName != null) {
            Text(
                text = projectName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 10.dp),
            )
        }
        if (filePath != null) {
            Text(
                text = if (isDirty) "$filePath •" else filePath,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun MenuRow(action: MenuAction, onChosen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (action.enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable {
                            onChosen()
                            action.onClick()
                        }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (action.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (action.shortcut != null) {
            Text(
                text = action.shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
