package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

// Zed: `DynamicSpacing::Base32` — 32px at the default 16px rem
// (crates/ui/src/components/tab.rs:84). We give the bar 40 instead, and not
// reluctantly: the strip carries the two smallest targets in the app, the ✕
// that closes a tab and the dot that saves it, and at 32 neither is reachable
// with a finger. The tab's *contents* keep Zed's proportions inside it.
private val TabBarHeight = 40.dp

/** Base32 − 1px: the border, or the selected tab's `pb_px`, eats it (tab.rs:79). */
private val TabContentHeight = 39.dp

/** `px(Base04)` inside the tab, and the gap between its slots (tab.rs:173-174). */
private val TabContentPadding = 4.dp
private val TabContentGap = 4.dp

/** Zed's `border_1`, which is every border in the chrome (styles.rs:1337). */
private val TabBorder = 1.dp

/** `Indicator::dot()` — `w_1p5`/`h_1p5` (crates/ui/src/components/indicator.rs:74). */
private val DirtyDotSize = 6.dp

// Zed's slots are fixed 12px and 14px squares (tab.rs:8-9) — mouse targets on
// a desktop. Ours are doubled, because the dot is also the save button and the
// ✕ is the only way to close a tab by hand. They stop here rather than at the
// 40dp a finger really wants: any wider and either the tab outgrows its label
// or the slots start overlapping the neighbouring tab's own targets.
private val StartSlotWidth = 24.dp
private val EndSlotWidth = 28.dp

private val MaxTabLabelWidth = 180.dp

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
    val border = theme.color("border")
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
            // The strip's own bottom border, drawn *behind* the tabs so the
            // selected one's background covers its share of it — which is what
            // makes the active tab read as open into the editor below rather
            // than as a label sitting on a line (tab_bar.rs:122-128).
            .drawBehind {
                val thickness = TabBorder.toPx()
                drawRect(
                    color = border,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
                )
            }
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
            borders = tabBorders(index, files.activeIndex, files.tabs.size),
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

/**
 * Which edges of one tab are drawn, and which are 1px of padding instead.
 *
 * Every case leaves exactly 1px on each side and 1px at the bottom, so the
 * label sits in the same place whichever tab is selected — that is why Zed
 * pads where it does not draw (tab.rs:150-165).
 */
private data class TabBorders(val left: Boolean, val right: Boolean, val bottom: Boolean)

/**
 * Zed's five cases, by where a tab sits relative to the selected one.
 *
 * The selected tab is the one with side borders and *no* bottom border; its
 * neighbours carry a bottom border and lend it the side they face. That is the
 * whole trick: the active tab is the only break in the line under the strip.
 */
private fun tabBorders(index: Int, activeIndex: Int, count: Int): TabBorders {
    val selected = index == activeIndex
    return when {
        // The first tab has nothing to its left to separate it from.
        index == 0 -> TabBorders(left = false, right = selected, bottom = !selected)
        selected -> TabBorders(left = true, right = true, bottom = false)
        index == count - 1 -> TabBorders(left = false, right = true, bottom = true)
        index < activeIndex -> TabBorders(left = true, right = false, bottom = true)
        else -> TabBorders(left = false, right = true, bottom = true)
    }
}

@Composable
private fun EditorTab(
    file: OpenFile,
    isActive: Boolean,
    borders: TabBorders,
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
    val border = theme.color("border")
    Box(
        modifier = Modifier
            .height(TabBarHeight)
            .background(background)
            .drawBehind {
                val thickness = TabBorder.toPx()
                if (borders.left) {
                    drawRect(border, Offset.Zero, Size(thickness, size.height))
                }
                if (borders.right) {
                    drawRect(
                        border,
                        Offset(size.width - thickness, 0f),
                        Size(thickness, size.height),
                    )
                }
                if (borders.bottom) {
                    drawRect(
                        border,
                        Offset(0f, size.height - thickness),
                        Size(size.width, thickness),
                    )
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .onSecondaryClick { position -> menuAt = position }
            .onMiddleClick { if (!file.isPinned) onClose() }
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { menuAt = DpOffset.Zero },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TabContentGap),
            modifier = Modifier
                // The pixel the border takes, whether or not this tab draws
                // one: the content box is 31px tall and inset by 1 on each
                // side in all five cases.
                .padding(start = TabBorder, end = TabBorder, bottom = TabBorder)
                .height(TabContentHeight)
                .padding(horizontal = TabContentPadding),
        ) {
            // The dot leads the label, as Zed's start slot does, and doubles as
            // the save button. The status bar has one too, but the soft
            // keyboard covers the status bar — and typing is exactly when you
            // want to save, so the affordance has to live up here.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(StartSlotWidth)
                    .fillMaxHeight()
                    .then(
                        if (file.isDirty) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = onSave)
                        } else {
                            Modifier
                        }
                    ),
            ) {
                if (file.isDirty || file.hasDiskChange) {
                    Box(
                        modifier = Modifier
                            .size(DirtyDotSize)
                            .clip(CircleShape)
                            .background(
                                // `warning` is a file that moved under the
                                // buffer; plain unsaved work is `text.accent`
                                // (pane.rs:4973-4979).
                                if (file.hasDiskChange) {
                                    theme.color("warning", foreground)
                                } else {
                                    theme.color("text.accent", foreground)
                                }
                            ),
                    )
                }
            }
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MaxTabLabelWidth),
            )
            // A pinned tab shows the pin where the ✕ would be, as Zed does:
            // the way out of a pinned tab is to unpin it, and the mark is the
            // button that does that.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(EndSlotWidth)
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = if (file.isPinned) onTogglePin else onClose),
            ) {
                Text(
                    text = if (file.isPinned) "⚑" else "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        when {
            // A picture has no buffer to write back, so "Save" would be a
            // button that does nothing; the tab is all there is to close.
            file.isDeleted && file.session == null -> Unit
            file.isDeleted -> ConflictAction("Save", onSave)
            else -> ConflictAction("Reload", onReload)
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
