package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.FileMatch
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.ui.theme.LocalZedTheme

private const val MAX_RESULTS = 50

// Zed's picker is one widget with several contents: `DEFAULT_MODAL_WIDTH` is
// `rems(34)` and `DEFAULT_MODAL_MAX_HEIGHT` `rems(24)`, which are 544 and 384
// at the default 16px rem (crates/picker/src/picker.rs:45-46). Elevated
// surfaces are `rounded_lg` = 8px with a 1px `border.variant`
// (crates/ui/src/traits/styled_ext.rs:6-12).
private val ModalWidth = 544.dp
private val ModalMaxHeight = 384.dp
private val ModalRadius = 8.dp

/**
 * The shell every picker in this app wears: the file finder and the command
 * palette are the same widget in Zed, so they are the same widget here.
 *
 * [modifier] lands on the surface itself, which is where a caller hangs the
 * key handling it needs to see before the text field does. Tapping outside
 * dismisses: the content fills the window so that nothing is ever "outside" it
 * as far as the dialog is concerned, and the dimmed area has to close us by
 * hand.
 */
@Composable
internal fun PickerModal(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalZedTheme.current
    Dialog(
        onDismissRequest = onDismiss,
        // A picker has to stay usable with the soft keyboard up — on a phone
        // it is the *only* way to type — and a dialog only learns where the IME
        // is once it stops fitting the system windows itself.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                shape = RoundedCornerShape(ModalRadius),
                color = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(
                    1.dp,
                    theme.color("border.variant", MaterialTheme.colorScheme.outlineVariant),
                ),
                modifier = modifier
                    .widthIn(max = ModalWidth)
                    .heightIn(max = ModalMaxHeight)
                    .fillMaxWidth()
                    // Swallow taps, or they would reach the dismiss handler
                    // above and close the picker from inside it. `clickable`
                    // would do it too, and would ripple the whole panel.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.padding(vertical = 16.dp), content = content)
            }
        }
    }
}

/**
 * Fuzzy file finder, in the shape every editor uses: type to filter, arrows
 * to move, Enter to open.
 *
 * Matching happens in the engine against the worktree snapshot already in
 * memory, so a keystroke costs one coarse JNI call and no directory walk.
 * The query runs off the main thread — it is blocking on the engine side —
 * and results are only published if the query hasn't moved on, so a slow
 * result can't overwrite a newer one.
 *
 * Keyboard-first by design (see docs/SHORTCUTS.md), but every row is also a
 * touch target and shows a hand cursor under a mouse.
 */
@Composable
fun FileFinder(
    project: ProjectSession,
    onOpen: (FileMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf(emptyList<FileMatch>()) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(query.text, project) {
        val text = query.text
        val found = withContext(Dispatchers.Default) { project.findFiles(text, MAX_RESULTS) }
        // The user may have typed on while this ran; a stale answer must not
        // replace a fresher one.
        if (text == query.text) {
            results = found
            selected = 0
        }
    }

    LaunchedEffect(selected) {
        if (selected in results.indices) listState.animateScrollToItem(selected)
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = ((selected + delta) % size + size) % size
    }

    fun openSelected() {
        results.getOrNull(selected)?.let(onOpen)
    }

    PickerModal(
        onDismiss = onDismiss,
        // Arrows and Enter must reach us even though the text field has focus,
        // so they are intercepted before it sees them.
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> { move(1); true }
                Key.DirectionUp -> { move(-1); true }
                Key.Enter, Key.NumPadEnter -> { openSelected(); true }
                Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(theme.color("editor.background"), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(theme.color("editor.foreground")),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            if (query.text.isEmpty()) {
                Text(
                    text = "Search files by name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        if (results.isEmpty()) {
            Text(
                text = if (query.text.isEmpty()) "No files" else "No matches",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                // Whatever is left of the modal's 384dp, and no more: the
                // picker's height is Zed's number, not the result count's.
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(results, key = { _, match -> match.path }) { index, match ->
                    ResultRow(
                        match = match,
                        isSelected = index == selected,
                        onClick = { onOpen(match) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    match: FileMatch,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) theme.color("element.selected") else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        // The path is what was matched, so it carries the highlights; the
        // name is shown above it because that is what people scan for.
        Text(
            text = match.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = highlighted(match, theme.color("conflict", MaterialTheme.colorScheme.primary)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The path with matched characters emphasised. Positions are UTF-16 offsets
 * from the engine, which is exactly what an [AnnotatedString] range wants.
 */
private fun highlighted(match: FileMatch, color: Color): AnnotatedString {
    if (match.positions.isEmpty()) return AnnotatedString(match.path)
    val marked = match.positions.toHashSet()
    return buildAnnotatedString {
        match.path.forEachIndexed { index, character ->
            if (index in marked) {
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                    append(character)
                }
            } else {
                append(character)
            }
        }
    }
}
