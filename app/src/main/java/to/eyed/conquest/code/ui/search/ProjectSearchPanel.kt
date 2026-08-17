package to.eyed.conquest.code.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.ProjectSearchFile
import to.eyed.conquest.code.core.ProjectSearchMatch
import to.eyed.conquest.code.core.ProjectSearchSession
import to.eyed.conquest.code.core.ProjectSearchState
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.SearchQuery
import to.eyed.conquest.code.ui.theme.BufferFontFamily
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.workspace.EntryIconMark

/**
 * Zed's `git_panel.default_width` (assets/settings/default.json:997) — its one
 * dock that shows code rather than names, and the width at which a result line
 * is still worth reading.
 */
private val DockWidth = 360.dp
private val DockMinWidth = 260.dp

/** The bar is the buffer search bar's twin, so the numbers are its numbers. */
private val BarHeight = 36.dp
private val FieldHeight = 26.dp
private val FieldRadius = 6.dp

/**
 * The project panel's row height, for the project panel's reason: Zed's row is
 * its label's line box, which is 3.6mm on a phone and too small to hit.
 */
private val RowMinHeight = 40.dp

/** Room for four digits of the buffer font, right-aligned like the gutter. */
private val LineNumberWidth = 40.dp

/** The dock's grip, the same 6dp the terminal dock's is. */
private val HandleWidth = 6.dp

/**
 * How long the query rests before a search starts.
 *
 * Buffer search runs on every keystroke because it is one pass over a rope.
 * This one reads every file in the project, so a keystroke that started one
 * would spin a thread over thousands of files it is about to throw away, and
 * typing `foobar` would start six of them.
 */
private const val QUERY_DEBOUNCE_MS = 250L

/** The engine publishes every 100 ms; polling faster only costs JNI calls. */
private const val POLL_MS = 100L

/** How far PageUp and PageDown move the selection. */
private const val PAGE_ROWS = 10

/**
 * What the panel had in its fields when it was last closed.
 *
 * A compact screen gives the whole work area to the panel, so opening a result
 * closes it — and without this, walking three hits in turn would mean typing
 * the query three times. Session-lived on purpose: it is Zed's search history
 * in the small, not a setting, and it never reaches disk.
 */
private object LastProjectSearch {
    var query: String = ""
    var regex: Boolean = false
    var caseSensitive: Boolean = false
    var wholeWord: Boolean = false
    var includeIgnored: Boolean = false
    var include: String = ""
    var exclude: String = ""

    /** Whether the filter row was carrying anything worth reopening it for. */
    val hasFilters: Boolean
        get() = include.isNotEmpty() || exclude.isNotEmpty() || includeIgnored

    fun store(search: SearchQuery, includeText: String, excludeText: String) {
        query = search.query
        regex = search.regex
        caseSensitive = search.caseSensitive
        wholeWord = search.wholeWord
        includeIgnored = search.includeIgnored
        include = includeText
        exclude = excludeText
    }
}

/** Which field an error belongs to, and therefore which one is outlined. */
private enum class ErrorField { Query, Filters, Neither }

/** The counters a running search publishes, as the status line reads them. */
private data class SearchProgress(
    /** Null before anything has started — an empty query, or the debounce. */
    val state: ProjectSearchState? = null,
    val filesSearched: Int = 0,
    val totalFiles: Int = 0,
    val fileCount: Int = 0,
    val matchCount: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
    val errorField: ErrorField = ErrorField.Neither,
) {
    val isLive: Boolean get() = state?.isLive == true
}

/**
 * Search every file in the project — Zed's project search
 * (crates/search/src/project_search.rs), which there is an item inside a pane.
 *
 * We have no pane, so this is a panel: a dock beside the editor on a wide
 * screen, and the whole work area on a compact one, which is the split the
 * terminal already makes. What that costs is Zed's "results as one editable
 * multi-buffer"; what it buys is a results list that survives on a phone,
 * where a second pane would leave neither half usable.
 *
 * The engine reads the files on a thread of its own and publishes every
 * 100 ms, so results arrive while the search runs and the list grows under the
 * reader. Only the cheap version counter is read on the main thread; the parse
 * — which can be megabytes of JSON — is not.
 *
 * Keyboard, mouse and touch all reach every result: the arrows walk the rows
 * while the query field keeps the caret, Enter opens the selected one and
 * Escape closes; a mouse hovers, clicks and scrolls; a finger taps.
 */
@Composable
fun ProjectSearchPanel(
    project: ProjectSession,
    /** Wide screens dock it beside the editor and let a mouse drag it wider. */
    isDock: Boolean,
    /**
     * Bumped by the workspace whenever Ctrl+Shift+F is pressed, so pressing it
     * with the panel already open puts the caret back in the query rather than
     * doing nothing visible.
     */
    focusToken: Int,
    onOpenMatch: (path: String, match: ProjectSearchMatch) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    var query by remember {
        mutableStateOf(
            TextFieldValue(
                LastProjectSearch.query,
                TextRange(LastProjectSearch.query.length),
            )
        )
    }
    var caseSensitive by remember { mutableStateOf(LastProjectSearch.caseSensitive) }
    var wholeWord by remember { mutableStateOf(LastProjectSearch.wholeWord) }
    var regex by remember { mutableStateOf(LastProjectSearch.regex) }
    var includeIgnored by remember { mutableStateOf(LastProjectSearch.includeIgnored) }
    var include by remember { mutableStateOf(TextFieldValue(LastProjectSearch.include)) }
    var exclude by remember { mutableStateOf(TextFieldValue(LastProjectSearch.exclude)) }
    var filtersOpen by remember { mutableStateOf(LastProjectSearch.hasFilters) }

    // Appended to, never rebuilt: a poll hands back only what is new, and a
    // finished search over a big repository is thousands of files.
    val files = remember { mutableStateListOf<ProjectSearchFile>() }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var progress by remember { mutableStateOf(SearchProgress()) }
    var selected by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(focusToken) { focus.requestFocus() }

    val search = remember(
        query.text,
        regex,
        caseSensitive,
        wholeWord,
        includeIgnored,
        include.text,
        exclude.text,
    ) {
        SearchQuery(
            query = query.text,
            regex = regex,
            caseSensitive = caseSensitive,
            wholeWord = wholeWord,
            includeIgnored = includeIgnored,
            includeGlobs = globsOf(include.text),
            excludeGlobs = globsOf(exclude.text),
        )
    }
    val rows = remember(files.size, collapsed) { projectSearchRows(files, collapsed) }

    // One search at a time, restarted whenever anything about the query moves.
    // Nothing here cancels the previous one by hand: starting a search already
    // cancels whatever was running for the project, and the cancel below is
    // matched on the id it was given — so a restart that overtakes this
    // coroutine's cleanup cannot kill its successor.
    LaunchedEffect(project, search) {
        LastProjectSearch.store(search, include.text, exclude.text)
        files.clear()
        collapsed = emptySet()
        selected = -1
        progress = SearchProgress()
        if (search.query.isEmpty()) return@LaunchedEffect
        delay(QUERY_DEBOUNCE_MS)

        // Compiling a pathological regex costs tens of milliseconds, so it is
        // asked once, here, rather than on the keystroke path.
        val invalid = withContext(Dispatchers.Default) { search.error() }
        if (invalid != null) {
            progress = SearchProgress(error = invalid, errorField = ErrorField.Query)
            return@LaunchedEffect
        }
        var session: ProjectSearchSession? = null
        try {
            // Assigned from inside the block rather than taken as its result:
            // cancelling this coroutine while the engine is starting the
            // search would skip the assignment, and the search would then run
            // to the end holding results nobody will ever read.
            withContext(NonCancellable + Dispatchers.Default) {
                session = ProjectSearchSession(project, search)
            }
            val started = session ?: return@LaunchedEffect
            if (started.id < 0) {
                // The query compiled a moment ago and the project is one we
                // hold open, so what the engine refused is a glob. The
                // contract cannot say which of the two patterns it was.
                progress = SearchProgress(
                    error = "Check the include and exclude patterns",
                    errorField = ErrorField.Filters,
                )
                return@LaunchedEffect
            }
            progress = SearchProgress(state = ProjectSearchState.Scanning)
            var seen = 0L
            while (true) {
                val version = started.version
                // 0 means forgotten, never "not yet": nothing more is coming.
                if (version == 0L) break
                if (version != seen) {
                    seen = version
                    val results = withContext(Dispatchers.Default) { started.poll() }
                    files.addAll(results.newFiles)
                    progress = SearchProgress(
                        state = results.state,
                        filesSearched = results.filesSearched,
                        totalFiles = results.totalFiles,
                        fileCount = results.fileCount,
                        matchCount = results.matchCount,
                        truncated = results.truncated,
                        error = results.error,
                    )
                    if (!results.state.isLive) break
                }
                delay(POLL_MS)
            }
        } finally {
            // Every result is in `files` by now, and the engine is holding its
            // own copy — megabytes, over a big repository — until someone says
            // otherwise. This runs when the search finishes, when the query
            // moves on, and when the panel closes.
            session?.let { started ->
                withContext(NonCancellable + Dispatchers.Default) { started.cancel() }
            }
        }
    }

    // Bumped only by the arrows: a row the mouse just clicked is already on
    // screen, and scrolling it to the top under the pointer would move the
    // next row out from under it.
    var scrollToSelected by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollToSelected) {
        if (scrollToSelected > 0 && selected in rows.indices) {
            listState.animateScrollToItem(selected)
        }
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        selected = when {
            selected < 0 -> if (delta > 0) 0 else rows.lastIndex
            else -> (selected + delta).coerceIn(0, rows.lastIndex)
        }
        scrollToSelected++
    }

    fun toggle(path: String) {
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
    }

    /** What Enter and a click both mean: a file folds, a match opens. */
    fun activate(row: ProjectSearchRow) {
        when (row) {
            is ProjectSearchRow.FileRow -> toggle(row.path)
            is ProjectSearchRow.MatchRow -> onOpenMatch(row.path, row.match)
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            // Arrows, Enter and Escape have to reach us while the query field
            // holds the caret, so they are taken before it sees them. Anything
            // with Ctrl is left alone: those belong to the workspace's table,
            // which matched them before this pass ever ran.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.PageDown -> { move(PAGE_ROWS); true }
                    Key.PageUp -> { move(-PAGE_ROWS); true }
                    Key.Enter, Key.NumPadEnter -> {
                        // With nothing selected, Enter is how the keyboard
                        // steps out of the query and into the results.
                        val row = rows.getOrNull(selected)
                        if (row == null) move(1) else activate(row)
                        true
                    }
                    Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.color("panel.background"))
        ) {
            QueryBar(
                query = query,
                onQuery = { query = it },
                caseSensitive = caseSensitive,
                onCaseSensitive = { caseSensitive = !caseSensitive },
                wholeWord = wholeWord,
                onWholeWord = { wholeWord = !wholeWord },
                regex = regex,
                onRegex = { regex = !regex },
                filtersOpen = filtersOpen,
                onFilters = { filtersOpen = !filtersOpen },
                hasError = progress.errorField == ErrorField.Query,
                focus = focus,
                onClose = onDismiss,
            )
            if (filtersOpen) {
                FilterBar(
                    include = include,
                    onInclude = { include = it },
                    exclude = exclude,
                    onExclude = { exclude = it },
                    includeIgnored = includeIgnored,
                    onIncludeIgnored = { includeIgnored = !includeIgnored },
                    hasError = progress.errorField == ErrorField.Filters,
                )
            }
            StatusLine(progress = progress, queryIsEmpty = query.text.isEmpty())
            HorizontalDivider(color = theme.color("border.variant"))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    when (row) {
                        is ProjectSearchRow.FileRow -> FileHeaderRow(
                            row = row,
                            isSelected = index == selected,
                            onClick = {
                                selected = index
                                toggle(row.path)
                            },
                        )
                        is ProjectSearchRow.MatchRow -> MatchResultRow(
                            row = row,
                            isSelected = index == selected,
                            onClick = {
                                selected = index
                                onOpenMatch(row.path, row.match)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The dock's left edge: the border between it and the editor, and the grip
 * that drags it wider. The terminal dock's handle, turned ninety degrees.
 */
@Composable
private fun ResizeHandle(onDrag: (Dp) -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .width(HandleWidth)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, delta -> onDrag(delta.toDp()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(color = theme.color("border"))
    }
}

@Composable
private fun QueryBar(
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    caseSensitive: Boolean,
    onCaseSensitive: () -> Unit,
    wholeWord: Boolean,
    onWholeWord: () -> Unit,
    regex: Boolean,
    onRegex: () -> Unit,
    filtersOpen: Boolean,
    onFilters: () -> Unit,
    hasError: Boolean,
    focus: FocusRequester,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .background(theme.color("toolbar.background"))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Field(
            value = query,
            onValue = onQuery,
            // Zed's own placeholder (project_search.rs:1073).
            placeholder = "Search all files…",
            hasError = hasError,
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        Toggle("Aa", caseSensitive, "Match case", onCaseSensitive)
        Toggle("ab", wholeWord, "Whole word", onWholeWord)
        Toggle(".*", regex, "Regular expression", onRegex)
        Toggle("…", filtersOpen, "Include and exclude paths", onFilters)
        Glyph("✕", "Close", onClose)
    }
}

@Composable
private fun FilterBar(
    include: TextFieldValue,
    onInclude: (TextFieldValue) -> Unit,
    exclude: TextFieldValue,
    onExclude: (TextFieldValue) -> Unit,
    includeIgnored: Boolean,
    onIncludeIgnored: () -> Unit,
    hasError: Boolean,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .background(theme.color("toolbar.background"))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Zed's placeholders, shortened to what a phone's width can show
        // (crates/search/src/search.rs:85-86).
        Field(
            value = include,
            onValue = onInclude,
            placeholder = "Include: src/**/*.rs",
            hasError = hasError,
            modifier = Modifier.weight(1f),
        )
        Field(
            value = exclude,
            onValue = onExclude,
            placeholder = "Exclude: *.lock",
            hasError = hasError,
            modifier = Modifier.weight(1f),
        )
        // Spelt out rather than given a glyph: this row has the width for it,
        // and no icon says "the files git is hiding from you".
        Toggle("ignored", includeIgnored, "Search ignored files", onIncludeIgnored, wide = true)
    }
}

@Composable
private fun Field(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    placeholder: String,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = modifier
            .height(FieldHeight)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("editor.background"))
            .border(
                1.dp,
                theme.color(if (hasError) "error" else "border"),
                RoundedCornerShape(FieldRadius),
            )
            .pointerHoverIcon(PointerIcon.Text)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.cursor),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.placeholder"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How far the search has got and what it found: the buffer bar's counter, grown up. */
@Composable
private fun StatusLine(progress: SearchProgress, queryIsEmpty: Boolean) {
    val theme = LocalZedTheme.current
    val error = progress.error
    val text = when {
        error != null -> error
        queryIsEmpty -> "Search every file in the project"
        progress.state == null -> ""
        progress.state == ProjectSearchState.Scanning -> "Scanning the project…"
        else -> statusOf(progress)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (error != null) theme.color("error") else theme.color("text.muted"),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
        // A bar rather than a spinner, because the contract hands over a real
        // fraction. It leaves when the search does, as the numbers do.
        if (progress.isLive && progress.totalFiles > 0) {
            val done = (progress.filesSearched.toFloat() / progress.totalFiles).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(theme.color("border.variant"))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(done)
                        .height(2.dp)
                        .background(theme.color("border.focused"))
                )
            }
        }
    }
}

/**
 * "12 results in 3 files · searched 480 of 1 200".
 *
 * The counts are the engine's own, which include matches it dropped at its
 * per-file cap — so a capped search says how much it found, not how much it
 * kept, and says separately that a limit was reached.
 */
private fun statusOf(progress: SearchProgress): String {
    val parts = ArrayList<String>(3)
    if (progress.matchCount > 0) {
        parts += "${count(progress.matchCount, "result")} in ${count(progress.fileCount, "file")}"
    } else if (!progress.isLive) {
        parts += "No results"
    }
    if (progress.isLive && progress.totalFiles > 0) {
        parts += "searched ${progress.filesSearched} of ${progress.totalFiles}"
    }
    if (progress.truncated && !progress.isLive) parts += "limit reached"
    return if (parts.isEmpty()) "Searching…" else parts.joinToString(" · ")
}

private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

@Composable
private fun FileHeaderRow(
    row: ProjectSearchRow.FileRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    ResultRow(isSelected = isSelected, onClick = onClick) {
        Text(
            text = if (row.isCollapsed) "▸" else "▾",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("icon.muted", theme.color("text.muted")),
            modifier = Modifier.width(10.dp),
        )
        // The same icon the panel and the finder draw: a result here and the
        // file itself have to read as the same thing.
        EntryIconMark(
            name = row.name,
            isDir = false,
            isExpanded = false,
            color = theme.color("icon.muted", theme.color("text.muted")),
        )
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.directory,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.matchCount.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
        )
    }
}

@Composable
private fun MatchResultRow(
    row: ProjectSearchRow.MatchRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val line = remember(row.match, theme) {
        matchLine(row.match, theme.color("search.match_background"))
    }
    ResultRow(isSelected = isSelected, onClick = onClick) {
        Text(
            text = row.match.line.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = BufferFontFamily),
            color = theme.color("editor.line_number", theme.color("text.muted")),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(LineNumberWidth),
        )
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
            color = theme.color("editor.foreground"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The box every result row shares: hover, selection, a hand cursor, a tap target. */
@Composable
private fun ResultRow(
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        isSelected -> theme.color("ghost_element.selected")
        hovered -> theme.color("ghost_element.hover", Color.Transparent)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .background(background)
            .pointerHoverIcon(PointerIcon.Hand)
            // The panel is the one focus target; rows taking it in turn would
            // fight the arrows for the selection.
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = interaction,
                // Zed swaps a row's colour instantly and has no ripple at all.
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun Toggle(
    label: String,
    on: Boolean,
    description: String,
    onClick: () -> Unit,
    /** A word instead of a glyph, so the chip grows to fit it. */
    wide: Boolean = false,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .height(FieldHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color(if (on) "element.selected" else "ghost_element.background"))
            .clickable(onClickLabel = description, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .then(if (wide) Modifier.padding(horizontal = 8.dp) else Modifier.width(FieldHeight)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color(if (on) "text" else "text.muted"),
        )
    }
}

@Composable
private fun Glyph(glyph: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .size(FieldHeight)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("icon"),
        )
    }
}
