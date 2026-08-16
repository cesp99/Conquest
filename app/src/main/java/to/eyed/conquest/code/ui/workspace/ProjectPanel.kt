package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.ui.theme.LocalZedTheme

private val RowHeightPadding = 5.dp
private val IndentPerLevel = 14.dp

/** How often to check the engine for a newer worktree snapshot. */
private const val SCANNING_POLL_MS = 120L
private const val IDLE_POLL_MS = 1_000L

/**
 * The project tree, rendered from the engine's worktree.
 *
 * Directories are read one level at a time and only while expanded, so the
 * panel never walks the whole project. The engine scans asynchronously, so
 * this polls its snapshot version — fast while the initial scan is running,
 * lazily afterwards, where it doubles as external-change detection.
 */
@Composable
fun ProjectPanel(
    project: ProjectSession?,
    onOpenFile: (ProjectEntry) -> Unit,
    modifier: Modifier = Modifier,
    openedPath: String? = null,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background"))
    ) {
        Text(
            text = project?.rootName?.uppercase().orEmpty().ifEmpty { "PROJECT" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        )

        if (project == null) {
            PanelMessage("No project open")
            return@Column
        }

        val tree = remember(project) { ProjectTreeState(project) }
        val scope = rememberCoroutineScope()

        // One coroutine per project: re-flatten whenever the engine reports a
        // newer snapshot. The rebuild reads through JNI and parses JSON, so it
        // stays off the main thread.
        LaunchedEffect(project) {
            while (true) {
                val version = project.version
                if (version != tree.version) {
                    tree.publish(version, withContext(Dispatchers.Default) { tree.rebuild() })
                }
                delay(if (project.scanComplete) IDLE_POLL_MS else SCANNING_POLL_MS)
            }
        }

        val error = project.error
        when {
            error != null -> PanelMessage(error)
            tree.rows.isEmpty() && !project.scanComplete -> PanelMessage("Scanning…")
            tree.rows.isEmpty() -> PanelMessage("Empty project")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(tree.rows, key = { it.entry.path }) { row ->
                    ProjectRow(
                        entry = row.entry,
                        depth = row.depth,
                        isExpanded = tree.isExpanded(row.entry.path),
                        isOpen = row.entry.path == openedPath,
                        onClick = {
                            if (!row.entry.isDir) {
                                onOpenFile(row.entry)
                                return@ProjectRow
                            }
                            tree.toggle(row.entry)
                            // Re-flatten now rather than waiting for the poll:
                            // collapsing and expanding an already-scanned
                            // directory needs no engine round-trip.
                            scope.launch {
                                val rows = withContext(Dispatchers.Default) { tree.rebuild() }
                                tree.publish(tree.version, rows)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    entry: ProjectEntry,
    depth: Int,
    isExpanded: Boolean,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val background = if (isOpen) {
        theme.color("element.selected")
    } else {
        Color.Transparent
    }
    // Zed dims gitignored entries rather than hiding them.
    val color = if (entry.isIgnored) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(
                start = 12.dp + IndentPerLevel * depth,
                end = 12.dp,
                top = RowHeightPadding,
                bottom = RowHeightPadding,
            ),
    ) {
        Text(
            text = when {
                entry.isDir && isExpanded -> "▾"
                entry.isDir -> "▸"
                else -> " "
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PanelMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
