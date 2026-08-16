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
import to.eyed.conquest.code.core.GitignoredFiles
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.GitFileStatus as EngineStatus
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.ui.theme.LocalZedTheme

private val RowHeightPadding = 5.dp
private val IndentPerLevel = 14.dp

/** How often to check the engine for a newer worktree snapshot. */
private const val SCANNING_POLL_MS = 120L
private const val IDLE_POLL_MS = 1_000L

/**
 * Where the panel gets per-path git status: the engine, which runs Debian's
 * git through proot behind a version counter of the same shape as the worktree
 * snapshot's. Both are cheap reads of a cache; neither ever waits on git.
 *
 * In a build with no Linux userland the counter stays 0 and the table is
 * empty, so the tree looks exactly as it always has.
 */
fun gitStatusSourceFor(project: ProjectSession): GitStatusSource = EngineGitStatusSource(project)

private class EngineGitStatusSource(private val project: ProjectSession) : GitStatusSource {

    override val version: Long get() = project.gitStatusVersion

    override fun snapshot(): GitStatusSnapshot {
        // Read the version first: a bump between here and the table means the
        // next poll picks the change up, rather than this one recording a new
        // version against older rows.
        val version = project.gitStatusVersion
        val engine = project.gitStatus()
        if (engine.isEmpty()) return GitStatusSnapshot.of(version, emptyMap())
        val byPath = HashMap<String, GitFileStatus>(engine.size)
        for ((path, status) in engine) byPath[path] = status.forPanel()
        return GitStatusSnapshot.of(version, byPath)
    }
}

/** The engine's vocabulary, in the panel's. */
private fun EngineStatus.forPanel(): GitFileStatus = when (this) {
    EngineStatus.Modified -> GitFileStatus.Modified
    EngineStatus.Added -> GitFileStatus.Added
    EngineStatus.Deleted -> GitFileStatus.Deleted
    EngineStatus.Renamed -> GitFileStatus.Renamed
    EngineStatus.Conflicted -> GitFileStatus.Conflicted
    EngineStatus.Untracked -> GitFileStatus.Untracked
    EngineStatus.Ignored -> GitFileStatus.Ignored
}

/**
 * The project tree, rendered from the engine's worktree.
 *
 * Directories are read one level at a time and only while expanded, so the
 * panel never walks the whole project. The engine scans asynchronously, so
 * this polls its snapshot version — fast while the initial scan is running,
 * lazily afterwards, where it doubles as external-change detection. Git status
 * rides the same poll: it is a second cheap version counter, and the rows are
 * re-coloured in place when it moves.
 */
@Composable
fun ProjectPanel(
    project: ProjectSession?,
    onOpenFile: (ProjectEntry) -> Unit,
    modifier: Modifier = Modifier,
    openedPath: String? = null,
    gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
    gitStatus: GitStatusSource? = null,
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

        val statusSource = remember(project, gitStatus) {
            gitStatus ?: gitStatusSourceFor(project)
        }
        val tree = remember(project, gitignoredFiles, statusSource) {
            ProjectTreeState(project, gitignoredFiles, statusSource)
        }
        // Resolved once per theme, never per row: `ZedTheme.color` is a map
        // read, and this panel draws one row per visible line per frame.
        val onSurface = MaterialTheme.colorScheme.onSurface
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val colours = remember(theme, onSurface, onSurfaceVariant) {
            GitStatusColours.from(theme, onSurface, onSurfaceVariant)
        }
        val dimIgnored = gitignoredFiles == GitignoredFiles.Dimmed
        val scope = rememberCoroutineScope()

        // Keyed on `tree`, not `project`: changing a setting that affects the
        // tree (showing gitignored files) builds a fresh, empty
        // ProjectTreeState, and an effect still holding the old one would
        // leave the panel permanently blank.
        //
        // Re-flatten whenever the engine reports a newer snapshot. The rebuild
        // reads through JNI and parses JSON, so it stays off the main thread.
        LaunchedEffect(tree) {
            while (true) {
                val version = project.version
                val shape = tree.shape
                if (version != tree.version) {
                    tree.publish(
                        version,
                        withContext(Dispatchers.Default) { tree.rebuild() },
                        shape,
                    )
                } else if (statusSource.version != tree.statusVersion) {
                    // Statuses normally land after the tree has been drawn.
                    // Re-colouring keeps the same rows and the same keys, so
                    // the list doesn't blink, scroll, or re-measure.
                    val current = tree.rows
                    tree.publish(
                        version,
                        withContext(Dispatchers.Default) { tree.restatus(current) },
                        shape,
                    )
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
                        status = row.status,
                        colours = colours,
                        isExpanded = tree.isExpanded(row.entry.path),
                        isOpen = row.entry.path == openedPath,
                        dimIgnored = dimIgnored,
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
                                val rebuilt = withContext(Dispatchers.Default) { tree.rebuild() }
                                tree.publish(tree.version, rebuilt)
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
    status: GitFileStatus,
    colours: GitStatusColours,
    isExpanded: Boolean,
    isOpen: Boolean,
    dimIgnored: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val background = if (isOpen) {
        theme.color("element.selected")
    } else {
        Color.Transparent
    }
    // Zed tints the *name* by git status and greys gitignored entries rather
    // than hiding them; "show" opts out of even that, for people who don't
    // want their tree to editorialise. Ignored still wins over status, as in
    // Zed: an ignored file that also has changes reads as ignored.
    //
    // Colours were resolved once for the whole panel, so this is a `when` over
    // an enum — no theme lookup and no allocation per row, per frame.
    val color = colours.colorFor(status, entry.isIgnored, dimIgnored)
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
