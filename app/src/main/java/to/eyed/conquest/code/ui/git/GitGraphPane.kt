package to.eyed.conquest.code.ui.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.Commit
import to.eyed.conquest.code.core.CommitDetails
import to.eyed.conquest.code.core.GitSession
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ZedTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One page of history; more are fetched as the list is scrolled. */
private const val PAGE = 100

/** One formatter, not one per row per frame. */
private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())

/** However tangled the history, the diagram may not eat the whole row. */
private const val MAX_DRAWN_LANES = 8

/**
 * Row metrics — Zed's own graph constants. A single-line row is the UI line
 * box plus `ROW_VERTICAL_PADDING` = 4px (git_graph.rs:78, 1354-1361): 14 × φ
 * ≈ 22.7, so ~27. The stacked row a narrow screen gets is ours, sized the
 * same way for its two line boxes. Lanes are `LANE_WIDTH` = 16px with a
 * 3.5px-radius dot and 1.5px lines (git_graph.rs:68-72), and the diagram is
 * inset `LEFT_PADDING` = 12px (git_graph.rs:71).
 */
private val RowHeight = 27.dp
private val StackedRowHeight = 46.dp
private val LaneWidth = 16.dp
private val DotRadius = 3.5.dp
private val LineWidth = 1.5.dp
private val GraphLeftPadding = 12.dp

/** Where the columns appear, rather than the second line of a stacked row. */
private val ColumnsFrom = 640.dp

/**
 * The commit graph — Zed's `git_graph`, drawn for a screen you hold.
 *
 * The diagram down the left is the point of it: lanes that fork and rejoin are
 * what tell you the shape of the history, and no list of subjects can. Beside
 * it go the description, the date, the author and the short hash — Zed's own
 * columns — collapsing to two lines per row when the window is too narrow for
 * five columns, which on a phone it is.
 *
 * Tapping a row opens what that commit contains, in place.
 */
@Composable
fun GitGraphPane(
    project: ProjectSession,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val session = remember(project) { GitSession(project) }
    var commits by remember(session) { mutableStateOf<List<Commit>>(emptyList()) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var loading by remember(session) { mutableStateOf(true) }
    var exhausted by remember(session) { mutableStateOf(false) }
    var open by remember(session) { mutableStateOf<CommitDetails?>(null) }
    /** The commit whose detail is being read; null when none is wanted. */
    var wanted by remember(session) { mutableStateOf<String?>(null) }

    // Reading a commit's detail is a git call of its own, so it happens off
    // the click rather than in the draw.
    LaunchedEffect(wanted) {
        val sha = wanted
        open = if (sha == null) null else withContext(Dispatchers.IO) { session.commitDetails(sha) }
    }

    val listState = rememberLazyListState()
    // Laid out off the main thread: it is O(commits) per page and the page
    // grows, so doing it in composition made every page cost more than the
    // last on the frame's own thread.
    var rows by remember(session) { mutableStateOf<List<GraphRow>>(emptyList()) }
    LaunchedEffect(commits) {
        rows = withContext(Dispatchers.Default) { layoutGraph(commits) }
    }

    suspend fun loadMore() {
        if (exhausted) return
        loading = true
        val page = withContext(Dispatchers.IO) { session.log(PAGE, commits.size) }
        loading = false
        if (page.error != null) {
            error = page.error
            exhausted = true
            return
        }
        if (page.commits.isEmpty()) {
            exhausted = true
            return
        }
        // A page that is entirely commits already seen — history rewritten
        // under us — would leave the list unchanged and the paging waiting for
        // a change that never comes.
        val before = commits.size
        // Deduplicate: a commit made while this is open shifts the window, and
        // the same sha arriving twice would draw two rows and two lanes.
        val seen = commits.mapTo(mutableSetOf()) { it.sha }
        commits = commits + page.commits.filter { seen.add(it.sha) }
        if (commits.size == before) exhausted = true
    }

    LaunchedEffect(session) { loadMore() }

    // A commit made while this tab is open belongs at the top of it. Watched
    // through the same counter everything else uses; a move resets the list
    // rather than appending, since history can be rewritten as well as added
    // to.
    LaunchedEffect(session) {
        var seen = withContext(Dispatchers.Default) { session.version }
        while (true) {
            kotlinx.coroutines.delay(500)
            val now = withContext(Dispatchers.Default) { session.version }
            if (now != seen) {
                seen = now
                commits = emptyList()
                exhausted = false
                error = null
                loadMore()
            }
        }
    }

    // Paging: when the last few rows come into view, ask for the next page.
    //
    // `rows` is snapshot state and the derived block is keyed on it, because a
    // `derivedStateOf` created once over a plain local captures the *first*
    // value — which was the empty list, so the condition read `last >= -5`,
    // was always true, and the graph loaded the entire history at once.
    val nearTheEnd by remember(rows) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            rows.isNotEmpty() && last >= rows.size - 5
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { nearTheEnd }.collect { near ->
            if (near && !loading && !exhausted) loadMore()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background"))
    ) {
        val columns = maxWidth >= ColumnsFrom
        when {
            error != null && rows.isEmpty() -> Message(error!!, isError = true)
            rows.isEmpty() && loading -> Message("Reading history…")
            rows.isEmpty() -> Message("Nothing has been committed yet")
            else -> Column(modifier = Modifier.fillMaxSize()) {
                if (columns) {
                    GraphHeader()
                    HorizontalDivider(color = theme.color("border.variant"))
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.commit.sha }) { row ->
                        GraphRowView(
                            row = row,
                            columns = columns,
                            isOpen = wanted == row.commit.sha,
                            onClick = {
                                wanted = if (wanted == row.commit.sha) null else row.commit.sha
                            },
                        )
                        if (open?.commit?.sha == row.commit.sha) {
                            CommitFiles(open!!, onOpenFile)
                        }
                    }
                    if (error != null) {
                        item(key = "error") {
                            Message(error!!, isError = true, inline = true)
                        }
                    } else if (!exhausted) {
                        item(key = "loading") {
                            Message("Reading more…", inline = true)
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun GraphHeader() {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("panel.background"))
            .padding(start = GraphLeftPadding, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        HeaderCell("Graph", 90.dp)
        HeaderCell("Description", null)
        HeaderCell("Date", 130.dp)
        HeaderCell("Author", 150.dp)
        HeaderCell("Commit", 80.dp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp?,
) {
    val theme = LocalZedTheme.current
    Text(
        // Column titles are Small muted labels, as every Zed table header.
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.muted"),
        maxLines = 1,
        modifier = if (width != null) Modifier.width(width) else Modifier.weight(1f),
    )
}

@Composable
private fun GraphRowView(
    row: GraphRow,
    columns: Boolean,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val date = remember(row.commit.authorTime) {
        DATE_FORMAT.format(Date(row.commit.authorTime * 1000L))
    }
    // No divider under a row: Zed's graph rows meet edge to edge, told apart
    // by the lane drawing and the hover fill alone.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (columns) RowHeight else StackedRowHeight)
            .background(
                when {
                    isOpen -> theme.color("element.selected", theme.color("border.variant"))
                    hovered -> theme.color("element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                // Instant swap, no ripple — Zed's rows never animate.
                indication = null,
                onClickLabel = "Show what this commit changed",
                onClick = onClick,
            )
            .padding(start = GraphLeftPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lanes(row, theme)
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Ref chips in Zed's Chip clothes: `px_1`, 1px `border`,
                // `rounded_sm`, `element.background` (chip.rs:106-115).
                for (name in row.commit.refs.take(2)) {
                    Text(
                        text = name.removePrefix("HEAD -> "),
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text"),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .background(
                                theme.color("element.background", theme.color("border.variant")),
                                androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp),
                    )
                }
                Text(
                    text = row.commit.subject.ifBlank { "(no message)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!columns) {
                Text(
                    text = "${row.commit.author} · $date · ${row.commit.shortSha}",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (columns) {
            Cell(date, 130.dp)
            Cell(row.commit.author, 150.dp)
            Cell(row.commit.shortSha, 80.dp)
        }
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.muted"),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
    )
}

/**
 * The diagram: a dot for this commit and a line for every lane that passes or
 * leaves it.
 *
 * Lanes are coloured by index rather than by branch, which is what every graph
 * does and the only thing that can be done without walking the whole history:
 * the colour says "this is a different line", not "this is that branch".
 */
@Composable
private fun Lanes(row: GraphRow, theme: ZedTheme) {
    val palette = remember(theme) {
        listOf(
            theme.color("text.accent", Color(0xFF61AFEF)),
            theme.color("version_control.added", Color(0xFF98C379)),
            theme.color("warning", Color(0xFFE5C07B)),
            theme.color("version_control.deleted", Color(0xFFE06C75)),
            theme.color("text.muted", Color(0xFFC678DD)),
        )
    }
    val laneCount = row.laneCount.coerceIn(1, MAX_DRAWN_LANES)
    Canvas(
        modifier = Modifier
            .width(LaneWidth * laneCount)
            .fillMaxHeight()
    ) {
        val laneWidth = LaneWidth.toPx()
        // A history tangled past the cap is drawn in the last column rather
        // than off the side of the canvas.
        fun x(lane: Int) = laneWidth * lane.coerceAtMost(laneCount - 1) + laneWidth / 2f
        val top = 0f
        val middle = size.height / 2f
        val bottom = size.height
        // `LINE_WIDTH` (git_graph.rs:72).
        val stroke = LineWidth.toPx()

        // Lines belonging to branches this commit is not on: straight through.
        for (lane in row.through) {
            drawLine(
                color = palette[lane % palette.size],
                start = Offset(x(lane), top),
                end = Offset(x(lane), bottom),
                strokeWidth = stroke,
            )
        }
        // Into this commit from above, and out to each parent below.
        drawLine(
            color = palette[row.lane % palette.size],
            start = Offset(x(row.lane), top),
            end = Offset(x(row.lane), middle),
            strokeWidth = stroke,
        )
        for (parent in row.parentLanes) {
            drawLine(
                color = palette[parent % palette.size],
                start = Offset(x(row.lane), middle),
                end = Offset(x(parent), bottom),
                strokeWidth = stroke,
            )
        }
        drawCircle(
            color = palette[row.lane % palette.size],
            radius = DotRadius.toPx(),
            center = Offset(x(row.lane), middle),
        )
    }
}

/** What one commit touched, under its row. */
@Composable
private fun CommitFiles(details: CommitDetails, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("panel.background"))
            .padding(start = GraphLeftPadding, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        val body = details.message.substringAfter('\n', "").trim()
        if (body.isNotEmpty()) {
            Text(
                text = body,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        for (file in details.files) {
            // A file line is its label's line box, and the whole width is the
            // tap target — Zed's dense-list rule (list_item.rs:365-368).
            Text(
                text = "${file.status}  ${file.original?.let { "$it → " } ?: ""}${file.path}",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = "Open ${file.path}") { onOpenFile(file.path) },
            )
        }
    }
    HorizontalDivider(color = theme.color("border.variant"))
}

@Composable
private fun Message(text: String, isError: Boolean = false, inline: Boolean = false) {
    val theme = LocalZedTheme.current
    Box(
        modifier = if (inline) {
            Modifier.fillMaxWidth().padding(16.dp)
        } else {
            Modifier.fillMaxSize().padding(24.dp)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) theme.color("error") else theme.color("text.muted"),
        )
    }
}
