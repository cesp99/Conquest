package to.eyed.conquest.code.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import to.eyed.conquest.code.ui.theme.UiFontFamily

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
    // Re-read whenever the repository's own counter moves, so staging a file
    // or editing it in the editor beside this updates the diff.
    val version by produceState(0L, session) {
        while (true) {
            value = withContext(Dispatchers.Default) { session.version }
            kotlinx.coroutines.delay(400)
        }
    }
    val patch by produceState<PatchResult?>(null, session, target, version) {
        value = withContext(Dispatchers.IO) { session.patch(target.path, target.staged) }
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
        TextStyle(fontFamily = BufferFontFamily, fontSize = settings.bufferFontSize.sp)
    }
    // One scroll for the whole patch, horizontal as well: a diff of a long
    // line must not be wrapped, or the two sides stop lining up.
    val across = rememberScrollState()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (file in files) {
            item(key = "file:${file.path}") {
                FileHeader(file, onOpenFile)
            }
            if (file.isBinary) {
                item(key = "binary:${file.path}") {
                    Notice("Binary file — nothing to show line by line.")
                }
                continue
            }
            for ((index, hunk) in file.hunks.withIndex()) {
                item(key = "hunk:${file.path}:$index") {
                    Text(
                        // git's own header, minus the line counts, which are
                        // in the numbers down the side anyway.
                        text = "@@ -${hunk.oldStart} +${hunk.newStart} @@ ${hunk.heading}".trimEnd(),
                        style = code.copy(fontSize = settings.bufferFontSize.sp * 0.85f),
                        color = theme.color("text.muted"),
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.color("element.background", theme.color("border.variant")))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                items(
                    items = hunk.lines,
                    key = { line -> "line:${file.path}:$index:${line.oldLine}:${line.newLine}:${line.kind}" },
                ) { line ->
                    DiffLineRow(line, code, across)
                }
            }
        }
    }
}

@Composable
private fun FileHeader(file: FileDiff, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val colours = remember(theme) {
        to.eyed.conquest.code.ui.workspace.GitStatusColours.from(
            theme,
            theme.color("text"),
            theme.color("text.muted"),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("panel.background"))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = file.original?.let { "$it → ${file.path}" } ?: file.path,
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 13.sp),
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            Text(
                text = "+${file.added}",
                style = TextStyle(fontFamily = UiFontFamily, fontSize = 11.sp),
                color = colours.added,
            )
            Text(
                text = "  −${file.removed}",
                style = TextStyle(fontFamily = UiFontFamily, fontSize = 11.sp),
                color = colours.deleted,
            )
            Text(
                text = "   open",
                style = TextStyle(fontFamily = UiFontFamily, fontSize = 11.sp),
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clickable(onClickLabel = "Open the file") { onOpenFile(file.path) },
            )
        }
    }
    HorizontalDivider(color = theme.color("border.variant"))
}

@Composable
private fun DiffLineRow(
    line: PatchLine,
    code: TextStyle,
    across: androidx.compose.foundation.ScrollState,
) {
    val theme = LocalZedTheme.current
    // Zed flattens the hunk colour over the editor background rather than
    // drawing a translucent quad, so text on it keeps its contrast.
    val background = when (line.kind) {
        '+' -> theme.color("version_control.added", theme.color("created")).copy(alpha = 0.16f)
        '-' -> theme.color("version_control.deleted", theme.color("deleted")).copy(alpha = 0.16f)
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
        Text(
            text = "${line.kind}${line.text}",
            style = code,
            color = theme.color("editor.foreground"),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(across)
                .padding(end = 12.dp),
        )
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
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 13.sp),
            color = if (isError) theme.color("error") else theme.color("text.muted"),
        )
    }
}
