package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/**
 * Zed-style status bar: the open file's path, a save action while it has
 * unsaved edits, and the cursor position. On compact layouts it also hosts the
 * project panel toggle (Zed keeps panel toggles here too).
 */
@Composable
fun StatusBar(
    cursorRow: Int,
    cursorCol: Int,
    modifier: Modifier = Modifier,
    filePath: String? = null,
    isDirty: Boolean = false,
    projectName: String? = null,
    onSave: (() -> Unit)? = null,
    onToggleProjectPanel: (() -> Unit)? = null,
    onOpenProjects: (() -> Unit)? = null,
    onFindFile: (() -> Unit)? = null,
) {
    val engineVersion = remember { CoreBridge.engineVersion() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(LocalZedTheme.current.color("status_bar.background"))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggleProjectPanel != null) {
            Text(
                text = "☰ files",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onToggleProjectPanel)
                    .padding(end = 12.dp),
            )
        }
        if (projectName != null && onOpenProjects != null) {
            // The touch twin of Ctrl+O.
            Text(
                text = projectName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onOpenProjects)
                    .padding(end = 12.dp),
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
        Spacer(modifier = Modifier.weight(1f))
        // The touch twin of Ctrl+P.
        if (onFindFile != null) {
            Text(
                text = "⌕ find",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onFindFile)
                    .padding(end = 12.dp),
            )
        }
        // Ctrl+S covers hardware keyboards; touch needs something to press.
        if (isDirty && onSave != null) {
            Text(
                text = "Save",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onSave)
                    .padding(end = 12.dp),
            )
        }
        Text(
            text = "Ln ${cursorRow + 1}, Col ${cursorCol + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = "engine v$engineVersion",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
