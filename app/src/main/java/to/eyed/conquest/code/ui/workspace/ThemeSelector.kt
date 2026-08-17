package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import to.eyed.conquest.code.core.ThemeMode
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ThemeStore
import to.eyed.conquest.code.ui.theme.ZedTheme
import to.eyed.conquest.code.ui.theme.ZedThemes
import to.eyed.conquest.code.ui.theme.isDark
import to.eyed.conquest.code.ui.theme.rem

/**
 * The theme selector: every installed theme, filtered as you type, **applied
 * as you move through it**.
 *
 * The live preview is the widget, not a flourish on it. Zed applies the theme
 * under the cursor to the real window and reverts if you dismiss
 * (`theme_selector.rs:227-256`), because a theme is judged on the file you
 * were already reading — a swatch grid tells you nothing about what a comment
 * or a diff marker will look like at two in the morning. Ours does the same by
 * pushing a name into [ThemeStore.preview], which the whole app is already
 * painting from, so the preview costs no plumbing and cannot go stale.
 *
 * Dark and light are separate settings, as in Zed: confirming a theme sets the
 * slot matching its own appearance and leaves the other alone, and switches
 * the mode only when the theme you picked would otherwise be invisible.
 */
@Composable
fun ThemeSelector(
    /** The mode from settings.json, which decides which slot is in effect. */
    mode: ThemeMode,
    /** Pins the mode when the chosen theme's appearance disagrees with it. */
    onSetMode: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val systemIsDark = isSystemInDarkTheme()
    val choices by ThemeStore.choices.collectAsState()

    var query by remember { mutableStateOf(TextFieldValue("")) }
    var installed by remember { mutableStateOf(emptyList<ZedTheme.Meta>()) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        // Parse every palette up front: the cursor is about to walk the list
        // and each step repaints the whole app with a different theme.
        installed = withContext(Dispatchers.IO) {
            ZedThemes.warm(context)
            ZedThemes.installed(context)
        }
    }

    val results = remember(installed, query.text) { matchThemes(installed, query.text) }

    // Start on the theme already in use, as Zed does, so opening the selector
    // and pressing Escape is a no-op you can't get wrong.
    LaunchedEffect(installed) {
        val inUse = choices.resolve(mode.isDark(systemIsDark))
        selected = results.indexOfFirst { it.meta.name == inUse }.coerceAtLeast(0)
    }
    LaunchedEffect(query.text) { selected = 0 }

    // Moving the cursor *is* choosing, provisionally.
    LaunchedEffect(selected, results) {
        results.getOrNull(selected)?.let { ThemeStore.preview(it.meta.name) }
        if (selected in results.indices) listState.animateScrollToItem(selected)
    }

    // Dismissal has four routes — Escape, the back gesture, a tap outside, the
    // Cancel button — and every one has to put the theme back. Hanging the
    // revert off the composable leaving the tree catches all of them at once.
    // Confirming needs no guard here: it has already cleared the preview and
    // written the choice, so this runs as a no-op behind it.
    DisposableEffect(Unit) {
        onDispose { ThemeStore.preview(null) }
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = ((selected + delta) % size + size) % size
    }

    fun confirm() {
        val meta = results.getOrNull(selected)?.meta ?: return
        ThemeStore.choose(context, meta)
        // Zed's rule: a theme whose appearance matches what the mode already
        // resolves to leaves "follow the system" alone; one that disagrees
        // pins the mode, because otherwise you would confirm a theme and watch
        // nothing happen (`theme_selector.rs:459-476`).
        if (meta.isDark != mode.isDark(systemIsDark)) {
            onSetMode(if (meta.isDark) ThemeMode.Dark else ThemeMode.Light)
        }
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                // Zed's modal geometry: `rounded_lg` is 8px (`styled_ext.rs:8`)
                // and a picker is `rems(34)` wide (`picker.rs:45`), which is
                // 544dp at the default UI font and grows with it.
                shape = RoundedCornerShape(8.dp),
                color = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .widthIn(max = rem(34f))
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.DirectionDown -> { move(1); true }
                            event.key == Key.DirectionUp -> { move(-1); true }
                            event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                            event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                            event.key == Key.Tab -> {
                                move(if (event.isShiftPressed) -1 else 1)
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                confirm()
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
                                text = "Select theme",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

                    if (results.isEmpty()) {
                        Text(
                            text = if (installed.isEmpty()) "Loading themes" else "No matches",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Zed's `rems(24)` (`picker.rs:46`).
                                .heightIn(max = rem(24f)),
                        ) {
                            itemsIndexed(results, key = { _, it -> it.meta.name }) { index, match ->
                                ThemeRow(
                                    match = match,
                                    isSelected = index == selected,
                                    isInUse = match.meta.name ==
                                        if (match.meta.isDark) choices.dark else choices.light,
                                    // Hovering moves the cursor, so a mouse
                                    // gets the same live preview a keyboard
                                    // does without having to click anything.
                                    onHover = { selected = index },
                                    onClick = {
                                        if (index == selected) confirm() else selected = index
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                    // Zed leaves confirm and cancel to Enter and Escape. A
                    // phone has neither, and "tap once to preview, tap the
                    // same row again to keep it" is not a rule anyone guesses,
                    // so the two verbs are also buttons.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        Box(modifier = Modifier.weight(1f))
                        Action(text = "Cancel", isPrimary = false, onClick = onDismiss)
                        Action(text = "Use theme", isPrimary = true, onClick = { confirm() })
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(
    match: ThemeMatch,
    isSelected: Boolean,
    isInUse: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()

    LaunchedEffect(isHovered) { if (isHovered) onHover() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Zed's picker rows are ~28px. Deviating up to 40dp: this list is
            // meant to be walked with a finger on a phone, and a 28dp row
            // cannot be hit reliably. The padding below is Zed's; only the
            // floor is ours.
            .heightIn(min = 40.dp)
            .background(if (isSelected) theme.color("element.selected") else Color.Transparent)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = highlightedName(
                match.meta.name,
                match.positions,
                theme.color("conflict", MaterialTheme.colorScheme.primary),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Which of the two slots this theme would fill. Zed's own selector
        // omits it because its list is grouped and its settings file is right
        // there; here it is the only thing telling you that confirming a light
        // theme will not change what you see after dark.
        Text(
            text = if (isInUse) {
                if (match.meta.isDark) "dark · in use" else "light · in use"
            } else {
                if (match.meta.isDark) "dark" else "light"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (isInUse) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun Action(text: String, isPrimary: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (isPrimary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** A theme that matched the query, and where in its name it matched. */
data class ThemeMatch(val meta: ZedTheme.Meta, val positions: List<Int>)

/**
 * Filter [themes] by [query], keeping the registry's dark-then-name order.
 *
 * The order is deliberately not re-scored: this list is eleven names long and
 * fully visible, so a filter that also reshuffles costs the user the position
 * they had already found. The command palette scores because its list is long
 * enough that the order is all you have.
 *
 * The matcher is a plain subsequence scan rather than the palette's exhaustive
 * one (`Commands.kt`, whose matcher is private to that file). It highlights the
 * first occurrence of each query character, which differs from the best one
 * only when a letter repeats inside a name — "Gruvbox Dark Hard" against
 * "hard" is the closest thing to a case, and it still lands right.
 */
fun matchThemes(themes: List<ZedTheme.Meta>, query: String): List<ThemeMatch> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return themes.map { ThemeMatch(it, emptyList()) }
    return themes.mapNotNull { meta ->
        subsequence(meta.name, trimmed)?.let { ThemeMatch(meta, it) }
    }
}

/** Case-insensitive subsequence positions, or null if [query] isn't one. */
private fun subsequence(name: String, query: String): List<Int>? {
    val positions = ArrayList<Int>(query.length)
    var at = 0
    for (character in query) {
        val found = name.indexOf(character, at, ignoreCase = true)
        if (found < 0) return null
        positions += found
        at = found + 1
    }
    return positions
}

private fun highlightedName(name: String, positions: List<Int>, color: Color): AnnotatedString {
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
