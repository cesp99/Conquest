package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.R
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** One panel's button: which panel, whether its dock is showing it, and the tap. */
data class PanelButton(
    val panel: WorkspacePanel,
    val isOpen: Boolean,
    val onClick: () -> Unit,
)

/**
 * Zed-style status bar: **state, not actions**.
 *
 * Zed splits these deliberately — the title bar holds commands, the status bar
 * reports where you are and which panels are up. Everything that *does*
 * something to a project or a file lives in the title-bar menu, which also
 * keeps it reachable when the soft keyboard covers the bottom of the screen.
 *
 * The panel buttons follow their docks, exactly as Zed's do: the left dock's
 * buttons at the left end of the bar, the right dock's at the right end, and
 * the bottom dock's — the terminal — at the right after them
 * (`workspace.rs:1757-1759`). Move a panel across in settings and its button
 * moves with it, which is the only arrangement in which the button says where
 * the panel will appear.
 */
@Composable
fun StatusBar(
    cursorRow: Int,
    cursorCol: Int,
    modifier: Modifier = Modifier,
    language: String? = null,
    hasFile: Boolean = false,
    /** Panels docked left, in the order they appear in the enum. */
    leftPanels: List<PanelButton> = emptyList(),
    rightPanels: List<PanelButton> = emptyList(),
    isTerminalOpen: Boolean = false,
    onToggleTerminal: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 30 = a 22px default button plus 4px of padding on each side,
            // which is how Zed's status bar gets its height rather than by
            // declaring one (crates/workspace/src/status_bar.rs:153).
            .height(30.dp)
            .background(theme.color("status_bar.background"))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (button in leftPanels) {
            PanelStatusButton(button)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (hasFile) {
            // Zed writes the caret as line:column.
            Text(
                text = "${cursorRow + 1}:${cursorCol + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (language != null) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        for (button in rightPanels) {
            PanelStatusButton(button)
        }
        if (onToggleTerminal != null) {
            // The touch twin of Ctrl+`, and the only way to reach a terminal
            // on a device with no keyboard attached. At the right end, where
            // Zed puts its bottom dock's buttons.
            StatusIconAction(
                icon = R.drawable.ic_ui_terminal,
                label = "Toggle the terminal",
                emphasised = isTerminalOpen,
                onClick = onToggleTerminal,
            )
        }
    }
}

@Composable
private fun PanelStatusButton(button: PanelButton) {
    StatusIconAction(
        icon = button.panel.icon,
        label = if (button.isOpen) "Close the ${button.panel.title}" else button.panel.title,
        emphasised = button.isOpen,
        onClick = button.onClick,
    )
}

@Composable
private fun StatusIconAction(
    icon: Int,
    label: String,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    Image(
        painter = painterResource(icon),
        contentDescription = label,
        colorFilter = ColorFilter.tint(
            if (emphasised) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .size(14.dp),
    )
}
