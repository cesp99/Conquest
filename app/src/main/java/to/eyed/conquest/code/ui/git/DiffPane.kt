package to.eyed.conquest.code.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.FileDiff
import to.eyed.conquest.code.core.GitSession
import to.eyed.conquest.code.core.PatchLine
import to.eyed.conquest.code.core.PatchResult
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.ui.theme.BufferFontFamily
import to.eyed.conquest.code.ui.theme.LocalAppSettings
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** What a diff tab is looking at. */
data class DiffTarget(
    /** A project-relative path, or null for every changed file. */
    val path: String?,
    /** The index against HEAD, rather than the working tree against HEAD. */
    val staged: Boolean = false,
) {
    /** What the tab strip calls it. */
    val title: String =
        if (path == null) "All changes" else "Diff: ${path.substringAfterLast('/')}"
}

/**
 * A unified diff, drawn the way Zed's own diffs are: the old and the new text
 * in one column, added lines on green, removed on red, with both line numbers
 * down the left.
 *
 * Unified rather than side-by-side, deliberately. Side by side is the better
 * view when there is room for two 80-column panes; on a phone it is two 20-
 * column panes, and Zed's own `project_diff` is unified for the same reason.
 *
 * It is a *view*: nothing here stages, discards or edits. The git panel does
 * those, and it is one tap away.
 */
@Composable
fun DiffPane(
    project: ProjectSession,
    target: DiffTarget,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val session = remember(project) { GitSession(project) }
    // One read per *change*, not one per poll: the version started at zero and
    // was corrected a frame later, so opening a diff ran git twice within
    // fifteen milliseconds — which is wasteful at best, and the second of the
    // pair is what the pane ended up showing.
    var patch by remember(session, target) { mutableStateOf<PatchResult?>(null) }
    LaunchedEffect(session, target) {
        var seen = -1L
        while (true) {
            val version = withContext(Dispatchers.Default) { session.version }
            if (version != seen) {
                seen = version
                patch = withContext(Dispatchers.IO) { session.patch(target.path, target.staged) }
            }
            kotlinx.coroutines.delay(400)
        }
    }

    val result = patch
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background"))
    ) {
        when {
            result == null -> Notice("Reading the diff…")
            result.error != null -> Notice(result.error!!, isError = true)
            result.files.isEmpty() -> Notice(
                if (target.path == null) {
                    "Nothing has changed since the last commit"
                } else {
                    "${target.path} matches the last commit"
                }
            )
            else -> DiffBody(result.files, onOpenFile)
        }
    }
}

@Composable
private fun DiffBody(files: List<FileDiff>, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val code = remember(settings.bufferFontSize) {
        TextStyle(
            fontFamily = BufferFontFamily,
            fontSize = settings.bufferFontSize.sp,
            // `buffer_line_height: "comfortable"` = φ, as in the editor
            // itself (theme_settings/src/settings.rs:390).
            lineHeight = (settings.bufferFontSize * 1.618034f).sp,
        )
    }
    // One scroll for the whole patch, horizontal as well: a diff of a long
    // line must not be wrapped, or the two sides stop lining up.
    //
    // Every row is given the *same* content width — that of the longest line —
    // because `horizontalScroll` writes its maximum from each node's own
    // measure and the setter clamps the offset down to it. Sharing one state
    // across rows of different widths meant the shortest visible row decided
    // how far the patch could scroll, which for a short last line was: not at
    // all.
    val across = rememberScrollState()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val contentWidth = remember(files, code) {
        val longest = files.asSequence()
            .flatMap { file -> file.hunks.asSequence() }
            .flatMap { hunk -> hunk.lines.asSequence() }
            .maxOfOrNull { it.text.length + 1 } ?: 0
        // Measured from the font rather than guessed: the buffer font is
        // monospaced, so one character's width times the longest line is
        // exactly right.
        val character = measurer.measure("M", code).size.width
        (longest * character).coerceAtLeast(1)
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for ((fileIndex, file) in files.withIndex()) {
            item(key = "file:$fileIndex") {
                FileHeader(file, onOpenFile)
            }
            if (file.isBinary) {
                item(key = "binary:$fileIndex") {
                    Notice("Binary file — nothing to show line by line.")
                }
                continue
            }
            if (file.hunks.isEmpty()) {
                item(key = "empty:$fileIndex") {
                    Notice("Only the file's mode changed.")
                }
                continue
            }
            for ((index, hunk) in file.hunks.withIndex()) {
                item(key = "hunk:$fileIndex:$index") {
                    Text(
                        // git's own header, minus the line counts, which are
                        // in the numbers down the side anyway.
                        text = "@@ -${hunk.oldStart},${hunk.oldCount} " +
                            "+${hunk.newStart},${hunk.newCount} @@ ${hunk.heading}".trimEnd(),
                        style = code.copy(fontSize = settings.bufferFontSize.sp * 0.85f),
                        color = theme.color("text.muted"),
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.color("element.background", theme.color("border.variant")))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                itemsIndexed(
                    items = hunk.lines,
                    // Keyed by *position*, not by content: two files whose
                    // names git could not give us would otherwise collide, and
                    // a duplicate key throws inside LazyLayout.
                    key = { at, _ -> "line:$fileIndex:$index:$at" },
                ) { _, line ->
                    DiffLineRow(line, code, across, contentWidth)
                }
            }
        }
    }
}

/**
 * A file's header, in the clothes of Zed's multibuffer excerpt header: the
 * whole strip is `FILE_HEADER_HEIGHT` = 2 buffer lines with 4px of padding
 * around a card (`BUFFER_HEADER_PADDING` = rems(0.25); editor.rs:290-291),
 * and the card is `rounded_sm`, 1px `border`, `editor.subheader.background`,
 * `pl_1`/`pr_2` with a `gap_1p5`, the filename set in the buffer font
 * (element/header.rs:707-733, 843-851). The +/− counts are the header's diff
 * stat; "open" stands in for its open-file button, one label instead of an
 * icon we do not ship.
 */
@Composable
private fun FileHeader(file: FileDiff, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val colours = remember(theme) {
        to.eyed.conquest.code.ui.workspace.GitStatusColours.from(
            theme,
            theme.color("text"),
            theme.color("text.muted"),
        )
    }
    val bufferLine = settings.bufferFontSize * 1.618034f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((bufferLine * 2).dp)
            .padding(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(theme.color("editor.subheader.background"))
                .border(1.dp, theme.color("border"), RoundedCornerShape(4.dp))
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = file.original?.let { "$it → ${file.path}" } ?: file.path,
                style = TextStyle(
                    fontFamily = BufferFontFamily,
                    fontSize = settings.bufferFontSize.sp,
                ),
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "+${file.added}",
                style = MaterialTheme.typography.labelMedium,
                color = colours.added,
            )
            Text(
                text = "−${file.removed}",
                style = MaterialTheme.typography.labelMedium,
                color = colours.deleted,
            )
            Spacer(modifier = Modifier.weight(1f))
            val openInteraction = remember { MutableInteractionSource() }
            val openHovered by openInteraction.collectIsHoveredAsState()
            Text(
                text = "open",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (openHovered) {
                            theme.color("ghost_element.hover", Color.Transparent)
                        } else {
                            Color.Transparent
                        }
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = openInteraction,
                        // Instant swap, no ripple, as everywhere in Zed.
                        indication = null,
                        onClickLabel = "Open the file",
                    ) { onOpenFile(file.path) }
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun DiffLineRow(
    line: PatchLine,
    code: TextStyle,
    across: androidx.compose.foundation.ScrollState,
    contentWidth: Int,
) {
    val theme = LocalZedTheme.current
    // The tokens Zed highlights expanded hunk rows with: the status pair
    // `created.background` / `deleted.background` (crates/theme/src/styles/
    // status.rs:19, 96), whose alpha is baked into the theme hex.
    val background = when (line.kind) {
        '+' -> theme.color("created.background", theme.color("created").copy(alpha = 0.16f))
        '-' -> theme.color("deleted.background", theme.color("deleted").copy(alpha = 0.16f))
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background),
        verticalAlignment = Alignment.Top,
    ) {
        LineNumber(if (line.oldLine == 0) "" else line.oldLine.toString(), code)
        LineNumber(if (line.newLine == 0) "" else line.newLine.toString(), code)
        Box(modifier = Modifier.horizontalScroll(across)) {
            Text(
                text = "${line.kind}${line.text}",
                style = code,
                color = theme.color("editor.foreground"),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(with(LocalDensity.current) { contentWidth.toDp() }),
            )
        }
    }
}

@Composable
private fun LineNumber(text: String, code: TextStyle) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = code.copy(fontSize = code.fontSize * 0.85f),
        color = theme.color("editor.line_number"),
        maxLines = 1,
        modifier = Modifier
            .width(44.dp)
            .padding(end = 6.dp),
    )
}

@Composable
private fun Notice(text: String, isError: Boolean = false) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            // Zed centres a muted default-size label in an empty surface.
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) theme.color("error") else theme.color("text.muted"),
        )
    }
}
