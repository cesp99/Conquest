package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.ThemeMode
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ThemeStore
import to.eyed.conquest.code.ui.theme.ZedTheme
import to.eyed.conquest.code.ui.theme.ZedThemes
import to.eyed.conquest.code.ui.theme.isDark

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
 *
 * The chrome is [PickerModal] — in Zed this is the same `Picker` as the file
 * finder and the command palette, so it is the same widget here too.
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

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
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
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (theme_selector.rs:385-387).
            placeholder = "Select Theme...",
            focusRequester = focus,
        )

        if (results.isEmpty()) {
            PickerEmptyState(if (installed.isEmpty()) "Loading themes" else "No matches")
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
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

        // Zed's picker footer: a row behind a 1px `border.variant` top border
        // with 8px of padding and an 8px gap (theme_selector.rs:536-546).
        // Zed leaves confirm and cancel to Enter and Escape; a phone has
        // neither, and "tap once to preview, tap the same row again to keep
        // it" is not a rule anyone guesses, so the two verbs are also buttons.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(theme.color("border.variant")),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f))
            GhostButton(text = "Cancel", isPrimary = false, onClick = onDismiss)
            GhostButton(text = "Use theme", isPrimary = true, onClick = { confirm() })
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
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()

    LaunchedEffect(isHovered) { if (isHovered) onHover() }

    // The row itself is Zed's: an inset `ListItem`, `Sparse`, its label's
    // line box tall (theme_selector.rs:517-528). No minimum height — per the
    // 2026-08-17 density decision the ~31dp row stands, and a finger that
    // misses can walk the list by hovering or arrow keys instead.
    PickerListItem(
        isSelected = isSelected,
        onClick = onClick,
        interactionSource = interaction,
    ) {
        Text(
            text = highlightedName(
                match.meta.name,
                match.positions,
                theme.color("text.accent"),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Which of the two slots this theme would fill. Zed's own selector
        // omits it (its end slot is a check icon on the original theme,
        // theme_selector.rs:526-528); here it is the only thing telling you
        // that confirming a light theme will not change what you see after
        // dark. `LabelSize::Small`, muted — accent when it is the live slot.
        Text(
            text = if (isInUse) {
                if (match.meta.isDark) "dark · in use" else "light · in use"
            } else {
                if (match.meta.isDark) "dark" else "light"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isInUse) {
                theme.color("text.accent")
            } else {
                theme.color("text.muted")
            },
        )
    }
}

/**
 * Zed's `Button` in its default dress: `ButtonSize::Default` is a 22px-high
 * `rounded_sm` row with 4px of side padding (`px(Base04)`,
 * button_like.rs:464-473, 798-803), and the `Subtle` style rests transparent,
 * swapping instantly to `ghost_element.hover` / `ghost_element.active`
 * (button_like.rs:242-247, 298-303, 324-329). The label is the default 14px
 * `text` — accent here marks the confirming verb, which is ours, not Zed's.
 */
@Composable
private fun GhostButton(text: String, isPrimary: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    pressed -> theme.color("ghost_element.active")
                    hovered -> theme.color("ghost_element.hover")
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPrimary) theme.color("text.accent") else theme.color("text"),
            maxLines = 1,
        )
    }
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

/**
 * The name with matched characters recoloured to `text.accent` — a colour
 * change only, as Zed's `HighlightedLabel` draws it
 * (crates/ui/src/components/label/highlighted_label.rs:208-218).
 */
private fun highlightedName(name: String, positions: List<Int>, color: Color): AnnotatedString {
    if (positions.isEmpty()) return AnnotatedString(name)
    val marked = positions.toHashSet()
    return buildAnnotatedString {
        name.forEachIndexed { index, character ->
            if (index in marked) {
                withStyle(SpanStyle(color = color)) { append(character) }
            } else {
                append(character)
            }
        }
    }
}
