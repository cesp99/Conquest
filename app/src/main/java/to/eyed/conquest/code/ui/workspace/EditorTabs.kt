package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.conquest.code.ui.theme.LocalZedTheme

private val TabBarHeight = 40.dp
private val MaxTabLabelWidth = 180.dp
private val DotTouchTarget = 28.dp

/** How far one notch of the wheel moves the strip. About one narrow tab. */
private val WheelStep = 96.dp

/**
 * Zed-style tab strip: one tab per open file, a dot for unsaved edits, a
 * close affordance on each, and pinned tabs held at the left.
 *
 * The dot and the close button are separate targets rather than the desktop
 * dot-turns-into-× trick, which depends on hover — a gesture a touch device
 * doesn't have.
 *
 * Closing goes through [OpenFilesState.requestClose] rather than
 * `close`, so a buffer with unsaved edits asks before it is dropped; the
 * question itself is [UnsavedChangesDialog], hosted here so that every route
 * into the strip is covered by it.
 *
 * Mouse and touch reach the same menu: right-click or long-press a tab.
 * Middle-click closes one, and the wheel scrolls the strip — a vertical wheel
 * on a horizontal strip, because that is the wheel most mice have.
 */
@Composable
fun EditorTabs(
    files: OpenFilesState,
    onSave: (OpenFile) -> Unit,
    modifier: Modifier = Modifier,
    /** Wired by the workspace, which knows how to open a file again. */
    onReopen: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val strip = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val wheelStep = with(LocalDensity.current) { WheelStep.toPx() }

    // Ctrl+Tab and Ctrl+9 can select a tab that is scrolled out of the strip
    // entirely; bring it back rather than leaving the user looking at tabs
    // that are not the one they are editing.
    LaunchedEffect(files.activeIndex, files.tabs.size) {
        val index = files.activeIndex
        if (index < 0) return@LaunchedEffect
        val item = strip.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val viewportEnd = strip.layoutInfo.viewportEndOffset
        when {
            item == null -> strip.animateScrollToItem(index)
            item.offset < 0 -> strip.animateScrollBy(item.offset.toFloat())
            item.offset + item.size > viewportEnd ->
                strip.animateScrollBy((item.offset + item.size - viewportEnd).toFloat())
        }
    }

    LazyRow(
        state = strip,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .background(theme.color("tab_bar.background"))
            .pointerInput(wheelStep) {
                // A horizontally scrolling row ignores a vertical wheel, which
                // is the only wheel most mice have. Taken in the initial pass
                // and consumed, so it can't also scroll something else.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll) continue
                        val delta = event.changes.fold(0f) { sum, change ->
                            sum + change.scrollDelta.y + change.scrollDelta.x
                        }
                        if (delta == 0f) continue
                        event.changes.forEach { it.consume() }
                        scope.launch { strip.scrollBy(delta * wheelStep) }
                    }
                }
            },
    ) {
        tabItems(files, onSave, onReopen)
    }

    UnsavedChangesDialog(files)
}

/** Kept out of [EditorTabs] only so the lambda nesting stays readable. */
private fun LazyListScope.tabItems(
    files: OpenFilesState,
    onSave: (OpenFile) -> Unit,
    onReopen: (() -> Unit)?,
) {
    items(count = files.tabs.size, key = { index -> files.tabs[index].path }) { index ->
        val file = files.tabs[index]
        EditorTab(
            file = file,
            isActive = index == files.activeIndex,
            menu = { tabMenu(files, index, onReopen) },
            onSelect = { files.select(index) },
            onSave = { onSave(file) },
            onClose = { files.requestClose(index) },
            onTogglePin = { files.togglePin(index) },
        )
    }
}

/**
 * The tab's own menu, in Zed's order.
 *
 * The bulk closes leave pinned tabs alone — `close_pinned: false` is Zed's
 * default for all three — so pinning a tab is a way of saying "not this one"
 * once, rather than every time.
 */
private fun tabMenu(
    files: OpenFilesState,
    index: Int,
    onReopen: (() -> Unit)?,
): List<ContextMenuItem> {
    val file = files.tabs[index]
    val closable = files.tabs.count { !it.isPinned }
    return buildList {
        add(ContextMenuItem("Close", "Ctrl W") { files.requestClose(index) })
        add(
            ContextMenuItem("Close others", enabled = closable > if (file.isPinned) 0 else 1) {
                files.requestCloseOthers(index)
            }
        )
        add(
            ContextMenuItem(
                "Close to the right",
                enabled = files.tabs.drop(index + 1).any { !it.isPinned },
            ) { files.requestCloseToTheRight(index) }
        )
        add(ContextMenuItem("Close all", enabled = closable > 0) { files.requestCloseAll() })
        add(
            ContextMenuItem(if (file.isPinned) "Unpin tab" else "Pin tab") {
                files.togglePin(index)
            }
        )
        if (onReopen != null) {
            add(
                ContextMenuItem("Reopen closed tab", "Ctrl Shift T", files.hasClosedTabs) {
                    onReopen()
                }
            )
        }
    }
}

@Composable
private fun EditorTab(
    file: OpenFile,
    isActive: Boolean,
    menu: () -> List<ContextMenuItem>,
    onSelect: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }
    val background = if (isActive) {
        theme.color("tab.active_background")
    } else {
        theme.color("tab.inactive_background")
    }
    val foreground = when {
        // A file that vanished underneath us is worth shouting about; an
        // unsaved one is only worth marking.
        file.isDeleted -> theme.color("error", MaterialTheme.colorScheme.error)
        isActive -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .height(TabBarHeight)
                .background(background)
                .pointerHoverIcon(PointerIcon.Hand)
                .onSecondaryClick { position -> menuAt = position }
                .onMiddleClick { if (!file.isPinned) onClose() }
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = { menuAt = DpOffset.Zero },
                )
                .padding(start = 14.dp, end = 8.dp),
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MaxTabLabelWidth),
            )
            if (file.isDirty) {
                // The dot doubles as the save button. The status bar has one too,
                // but the soft keyboard covers the status bar — and typing is
                // exactly when you want to save, so the affordance has to live up
                // here where it stays visible.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(DotTouchTarget)
                        .clip(CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onSave),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(theme.color("conflict", foreground)),
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(DotTouchTarget))
            }
            // A pinned tab shows the pin where the ✕ would be, as Zed does:
            // the way out of a pinned tab is to unpin it, and the mark is the
            // button that does that.
            Text(
                text = if (file.isPinned) "⚑" else "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = if (file.isPinned) onTogglePin else onClose)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        val position = menuAt
        if (position != null) {
            ContextMenu(
                expanded = true,
                onDismiss = { menuAt = null },
                items = menu(),
                offset = position,
            )
        }
    }
}

/**
 * The strip shown above the editor when the file underneath a buffer moved.
 *
 * It only ever appears for a *dirty* buffer or a deleted file: a clean buffer
 * whose file changed is reloaded silently, because there is nothing to lose
 * and so nothing to ask about.
 */
@Composable
fun FileConflictBar(
    file: OpenFile,
    onReload: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (file.isDeleted) {
                "${file.name} was deleted on disk"
            } else {
                "${file.name} changed on disk"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (file.isDeleted) {
            ConflictAction("Save", onSave)
        } else {
            ConflictAction("Reload", onReload)
        }
        ConflictAction("Dismiss", onDismiss)
    }
}

@Composable
private fun ConflictAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
