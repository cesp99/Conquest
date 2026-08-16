package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.ui.theme.LocalZedTheme

private val TabBarHeight = 40.dp
private val MaxTabLabelWidth = 180.dp
private val DotTouchTarget = 28.dp

/**
 * Zed-style tab strip: one tab per open file, a dot for unsaved edits, a
 * close affordance on each.
 *
 * The dot and the close button are separate targets rather than the desktop
 * dot-turns-into-× trick, which depends on hover — a gesture a touch device
 * doesn't have.
 */
@Composable
fun EditorTabs(
    files: OpenFilesState,
    onSave: (OpenFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .background(theme.color("tab_bar.background"))
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        files.tabs.forEachIndexed { index, file ->
            EditorTab(
                file = file,
                isActive = index == files.activeIndex,
                onSelect = { files.select(index) },
                onSave = { onSave(file) },
                onClose = { files.close(index) },
            )
        }
    }
}

@Composable
private fun EditorTab(
    file: OpenFile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val background = if (isActive) {
        theme.color("tab.active_background")
    } else {
        theme.color("tab.inactive_background")
    }
    val foreground = when {
        // A file that vanished underneath us is worth shouting about; an
        // unsaved one is only worth marking.
        file.isDeleted -> theme.color("error", MaterialTheme.colorScheme.error)
        isActive -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(TabBarHeight)
            .background(background)
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 8.dp),
    ) {
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = MaxTabLabelWidth),
        )
        if (file.isDirty) {
            // The dot doubles as the save button. The status bar has one too,
            // but the soft keyboard covers the status bar — and typing is
            // exactly when you want to save, so the affordance has to live up
            // here where it stays visible.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(DotTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onSave),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(theme.color("conflict", foreground)),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(DotTouchTarget))
        }
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/**
 * The strip shown above the editor when the file underneath a buffer moved.
 *
 * It only ever appears for a *dirty* buffer or a deleted file: a clean buffer
 * whose file changed is reloaded silently, because there is nothing to lose
 * and so nothing to ask about.
 */
@Composable
fun FileConflictBar(
    file: OpenFile,
    onReload: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (file.isDeleted) {
                "${file.name} was deleted on disk"
            } else {
                "${file.name} changed on disk"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (file.isDeleted) {
            ConflictAction("Save", onSave)
        } else {
            ConflictAction("Reload", onReload)
        }
        ConflictAction("Dismiss", onDismiss)
    }
}

@Composable
private fun ConflictAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
