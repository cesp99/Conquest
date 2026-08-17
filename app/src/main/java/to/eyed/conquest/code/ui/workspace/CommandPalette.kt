package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/**
 * The command palette: every command in the workspace, searchable by name.
 *
 * This is the thing that makes the keyboard optional. A binding you have to
 * know is a binding a phone or a tablet cannot offer at all, so the palette is
 * the one surface where *everything* is reachable — type part of a name, tap
 * the row. It deliberately looks and behaves like the file finder ([FileFinder]),
 * because to a user they are one gesture with two contents.
 *
 * Names are Zed's own action names, humanised: "terminal panel: toggle", not
 * "Toggle terminal". Searching "term" therefore finds everything the terminal
 * can do, which is the whole point of the namespace being in the name.
 *
 * A command that cannot run right now is greyed rather than hidden — Zed's
 * behaviour, and the one that teaches. The chord beside each row is rendered
 * from the keymap in Keybindings.kt, so it cannot drift from what the keyboard
 * actually does.
 */
@Composable
fun CommandPalette(
    workspace: CommandContext,
    /** Runs the command; false when the workspace refused it after all. */
    onRun: (WorkspaceCommand) -> Boolean,
    onDismiss: () -> Unit,
    /** Where the keyboard was when the palette opened; decides which chords it prints. */
    keyboardFocus: Focus = Focus.Workspace,
    /** Pre-filled query, for a caller handing the palette a search. */
    initialQuery: String = "",
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    var query by remember { mutableStateOf(TextFieldValue(initialQuery)) }
    var selected by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf(CommandRecency.known()) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        // Painted from the in-memory copy first; the disk read only matters
        // once per process, and never on the frame that opens the palette.
        recent = withContext(Dispatchers.IO) { CommandRecency.load(context) }
    }

    // A leading ">" is how VS Code's finder switches to commands, and enough
    // people type it out of habit that swallowing it is kinder than matching
    // nothing. It is also the hook for handing off from the file finder.
    val text = query.text.removePrefix(">")
    val entries = remember(workspace, keyboardFocus) { paletteEntries(workspace, keyboardFocus) }
    val results = remember(entries, text, recent) { matchCommands(entries, text, recent) }

    LaunchedEffect(text) { selected = 0 }
    LaunchedEffect(selected, results) {
        if (selected in results.indices) listState.animateScrollToItem(selected)
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = ((selected + delta) % size + size) % size
    }

    fun run(match: CommandMatch) {
        if (!match.entry.isEnabled) return
        val command = match.entry.command
        onDismiss()
        // Only what actually ran is worth remembering: a command the workspace
        // refused should not climb to the top of the list for it.
        if (onRun(command)) recent = CommandRecency.record(context, command)
    }

    Dialog(
        onDismissRequest = onDismiss,
        // The palette has to stay usable with the soft keyboard up — on a
        // phone it is the *only* way to type — and a dialog only learns where
        // the IME is once it stops fitting the system windows itself.
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
                // The content fills the window, so nothing is ever "outside"
                // it as far as the dialog is concerned; tapping the dimmed
                // area has to dismiss us by hand.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 640.dp)
                    .fillMaxWidth()
                    // Swallow taps, or they would reach the dismiss handler
                    // above and close the palette from inside it. `clickable`
                    // would do it too, and would ripple the whole panel.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // Arrows and Enter must reach us even though the text
                    // field has focus, so they are intercepted before it.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (isCommandPalette(event)) {
                            // The chord that opened it closes it, as in Zed.
                            onDismiss()
                            return@onPreviewKeyEvent true
                        }
                        when {
                            event.key == Key.DirectionDown -> { move(1); true }
                            event.key == Key.DirectionUp -> { move(-1); true }
                            // Zed's menu bindings: Ctrl+N/Ctrl+P and Tab move
                            // the selection too, for hands that never leave
                            // the home row.
                            event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                            event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                            event.key == Key.Tab -> {
                                move(if (event.isShiftPressed) -1 else 1)
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                results.getOrNull(selected)?.let(::run)
                                true
                            }
                            event.key == Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
            ) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
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
                                text = "Execute a command",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

                    if (results.isEmpty()) {
                        Text(
                            text = "No matching commands",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                        ) {
                            itemsIndexed(
                                results,
                                key = { _, match -> match.entry.command.id },
                            ) { index, match ->
                                CommandRow(
                                    match = match,
                                    isSelected = index == selected,
                                    onClick = { run(match) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    match: CommandMatch,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val entry = match.entry
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    val selectedColor = theme.color("element.selected", Color.Transparent)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    isSelected -> selectedColor
                    isHovered -> theme.color("element.hover", selectedColor)
                    else -> Color.Transparent
                }
            )
            .then(
                if (entry.isEnabled) {
                    Modifier
                        .hoverable(hover)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = highlighted(
                entry.name,
                match.positions,
                theme.color("conflict", MaterialTheme.colorScheme.primary),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isEnabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.shortcut != null) {
            Text(
                text = entry.shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The name with matched characters emphasised, as the file finder does. */
private fun highlighted(name: String, positions: List<Int>, color: Color): AnnotatedString {
    if (positions.isEmpty()) return AnnotatedString(name)
    val marked = positions.toHashSet()
    return buildAnnotatedString {
        name.forEachIndexed { index, character ->
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
