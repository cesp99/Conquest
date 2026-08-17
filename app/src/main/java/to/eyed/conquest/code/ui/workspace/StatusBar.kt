package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/**
 * Zed-style status bar: **state, not actions**.
 *
 * Zed splits these deliberately — the title bar holds commands, the status
 * bar reports where you are. Left: the panel toggle and search, which are
 * view state. Right: cursor position and language. Everything that *does*
 * something to a project or a file moved to the title-bar menu, which also
 * keeps it reachable when the soft keyboard covers the bottom of the screen.
 */
@Composable
fun StatusBar(
    cursorRow: Int,
    cursorCol: Int,
    modifier: Modifier = Modifier,
    language: String? = null,
    hasFile: Boolean = false,
    isPanelVisible: Boolean = true,
    onToggleProjectPanel: (() -> Unit)? = null,
    onFindFile: (() -> Unit)? = null,
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
        if (onToggleProjectPanel != null) {
            StatusAction(
                glyph = "▤",
                emphasised = isPanelVisible,
                onClick = onToggleProjectPanel,
            )
        }
        if (onFindFile != null) {
            StatusAction(glyph = "⌕", onClick = onFindFile)
        }
        if (onToggleTerminal != null) {
            // The touch twin of Ctrl+`, and the only way to reach a terminal
            // on a device with no keyboard attached.
            StatusAction(
                glyph = "❯_",
                emphasised = isTerminalOpen,
                onClick = onToggleTerminal,
            )
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
    }
}

@Composable
private fun StatusAction(
    glyph: String,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.bodyMedium,
        color = if (emphasised) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
