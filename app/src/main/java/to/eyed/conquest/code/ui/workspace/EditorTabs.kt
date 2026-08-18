package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ZedRadius
import to.eyed.conquest.code.ui.theme.glyphHeight
import to.eyed.conquest.code.ui.theme.rem
import to.eyed.conquest.code.ui.theme.remsAt

/**
 * The tab strip's metrics as rem multiples — `ui_font_size` is the rem
 * (theme_settings/src/settings.rs:619), so raising the UI font grows the strip
 * with its labels rather than leaving 14px tabs full of 20px text.
 *
 * Bare numbers rather than `Dp` so the table is checkable on the host at any
 * font size (`ChromeMetricsTest`); the composable getters underneath are what
 * the strip reads.
 */
internal object TabMetrics {

    /**
     * `DynamicSpacing::Base32` — 32px at the default 16px rem, and a rem
     * despite the name (tab.rs:84, ui_macros/src/dynamic_spacing.rs:147-162).
     * It was 40 for a while, for the ✕ and the dot; the 2026-08-17 density
     * decision in DECISIONS.md reversed that — exact Zed metrics win, and every
     * small target keeps a second route (the tab's long-press menu closes it,
     * Ctrl+S saves it).
     */
    const val BAR_HEIGHT = 2f

    /** `px(Base04)` inside the tab, and the gap between its slots (tab.rs:173-174). */
    const val CONTENT_PADDING = 0.25f
    const val CONTENT_GAP = 0.25f

    /** `Indicator::dot()` — `w_1p5`/`h_1p5` = `rems(0.375)` (indicator.rs:73-78). */
    const val DIRTY_DOT = 0.375f

    /**
     * How wide a label is allowed to get before it ellipsises. Ours, not Zed's
     * — Zed truncates the *string* at 24 characters (items.rs:66) — but it is a
     * measure of text, so it grows with the text.
     */
    const val MAX_LABEL_WIDTH = 11.25f

    /** How far one notch of the wheel moves the strip. About one narrow tab. */
    const val WHEEL_STEP = 6f

    /**
     * The fixed groups at the strip's ends: Zed frames each in `px(Base06)`
     * with `gap(Base04)` between the buttons, bordered below and on the side
     * facing the tabs (tab_bar.rs:103-112 for the start group, 141-150
     * mirrored for the end one).
     */
    const val TOOL_GROUP_PADDING = 0.375f
    const val TOOL_GROUP_GAP = 0.25f

    /**
     * Zed's IconButton at `ButtonSize::Default`: `rems_from_px(22)`
     * (button_like.rs:465-473).
     */
    const val TOOL_BUTTON_BOX = 1.375f

    /**
     * Base32 − 1px: the border, or the selected tab's `pb_px`, eats it
     * (tab.rs:79). Only the 32 scales; the pixel it gives up is a real pixel.
     */
    fun contentHeight(uiFontSize: Float): Dp = remsAt(uiFontSize, BAR_HEIGHT) - TabPixels.Border
}

/**
 * The tab dimensions Zed writes in **pixels**, which do not move with
 * `ui_font_size`.
 *
 * The slots are the interesting pair: `START_TAB_SLOT_SIZE` and
 * `END_TAB_SLOT_SIZE` are `px(12.)` and `px(14.)` (tab.rs:8-9), not rems, so
 * spelling them `rem(0.75f)`/`rem(0.875f)` would have made our tabs diverge
 * from Zed's at exactly the setting this change exists to honour. The dot
 * doubles as the save button and the ✕ closes — both keep a bigger route
 * (Ctrl+S, the long-press menu), which is what the density decision in
 * DECISIONS.md asks for instead of widening the slots.
 */
internal object TabPixels {

    /** Zed's `border_1`, which is every border in the chrome (styles.rs:1337). */
    val Border = 1.dp

    val StartSlotWidth = 12.dp
    val EndSlotWidth = 14.dp
}

/**
 * The bar, with the accessibility floor on top of Zed's metric.
 *
 * `max(rem(2), the label's ink)`: at every ordinary font scale this is exactly
 * [TabMetrics.BAR_HEIGHT] — 32dp at the default — and it only grows once the
 * *system's* font scale has made a tab label taller than the bar Zed specifies,
 * which is the point at which a fixed 32 would start slicing the ascenders off.
 * See [glyphHeight].
 */
private val TabBarHeight: Dp
    @Composable @ReadOnlyComposable get() = maxOf(
        rem(TabMetrics.BAR_HEIGHT),
        glyphHeight(MaterialTheme.typography.bodyMedium) + TabPixels.Border,
    )

/** The bar less the pixel the border takes, whichever of the two won above. */
private val TabContentHeight: Dp
    @Composable @ReadOnlyComposable get() = TabBarHeight - TabPixels.Border

private val TabContentPadding: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.CONTENT_PADDING)

private val TabContentGap: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.CONTENT_GAP)

private val DirtyDotSize: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.DIRTY_DOT)

private val MaxTabLabelWidth: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.MAX_LABEL_WIDTH)

private val ToolGroupPadding: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.TOOL_GROUP_PADDING)

private val ToolGroupGap: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.TOOL_GROUP_GAP)

private val ToolButtonBox: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.TOOL_BUTTON_BOX)

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
 *
 * The strip sits between two fixed groups, as Zed's does: the navigation
 * arrows at the left (tab_bar.rs:103-112) and the `+` at the right
 * (tab_bar.rs:141-150) stay put while the tabs scroll between them.
 */
@Composable
fun EditorTabs(
    files: OpenFilesState,
    onSave: (OpenFile) -> Unit,
    modifier: Modifier = Modifier,
    /** Wired by the workspace, which knows how to open a file again. */
    onReopen: (() -> Unit)? = null,
    /** Zed's `pane::GoBack` — the workspace owns it, since going back can reopen a file. */
    onNavigateBack: (() -> Unit)? = null,
    /** Zed's `pane::GoForward`. */
    onNavigateForward: (() -> Unit)? = null,
    /** The workspace's new-file flow — what Zed's `+` leads with (pane.rs:4272). */
    onNewFile: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val border = theme.color("border")
    val strip = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val wheelStep = with(LocalDensity.current) { rem(TabMetrics.WHEEL_STEP).toPx() }
    val barHeight = TabBarHeight
    val borderWidth = TabPixels.Border

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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(theme.color("tab_bar.background")),
    ) {
        // Zed's start group: back and forward, greyed when their stack is
        // empty rather than hidden (pane.rs:3407-3452). The glyphs are text —
        // sized like the tab's ✕ — since chrome here draws no new icons.
        if (onNavigateBack != null || onNavigateForward != null) {
            TabBarToolGroup(trailing = false) {
                TabBarIconButton(
                    glyph = "←",
                    label = "Go back",
                    enabled = files.canGoBack && onNavigateBack != null,
                    onClick = { onNavigateBack?.invoke() },
                )
                TabBarIconButton(
                    glyph = "→",
                    label = "Go forward",
                    enabled = files.canGoForward && onNavigateForward != null,
                    onClick = { onNavigateForward?.invoke() },
                )
            }
        }
        LazyRow(
            state = strip,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // The strip's own bottom border, drawn *behind* the tabs so the
                // selected one's background covers its share of it — which is
                // what makes the active tab read as open into the editor below
                // rather than as a label sitting on a line (tab_bar.rs:122-128).
                // The end groups draw their own stretch, so the line runs
                // unbroken across all three.
                .drawBehind {
                    val thickness = borderWidth.toPx()
                    drawRect(
                        color = border,
                        topLeft = Offset(0f, size.height - thickness),
                        size = Size(size.width, thickness),
                    )
                }
                .pointerInput(wheelStep) {
                    // A horizontally scrolling row ignores a vertical wheel,
                    // which is the only wheel most mice have. Taken in the
                    // initial pass and consumed, so it can't also scroll
                    // something else.
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
        // Zed's end group holds `+`, split and zoom, and shows them only
        // while the pane has focus (pane.rs:4244-4250). There are no splits
        // here and a finger has no focus to speak of, so: just the `+`, shown
        // while the pane has tabs.
        if (onNewFile != null && files.tabs.isNotEmpty()) {
            TabBarToolGroup(trailing = true) {
                TabBarIconButton(
                    glyph = "+",
                    label = "New file",
                    enabled = true,
                    onClick = onNewFile,
                )
            }
        }
    }

    UnsavedChangesDialog(files)
}

/**
 * The frame of one fixed group: bordered below like the strip, and on the
 * side facing the tabs — `border_b_1` + `border_r_1` leading,
 * `border_b_1` + `border_l_1` trailing (tab_bar.rs:107-110, 145-148).
 */
@Composable
private fun TabBarToolGroup(
    trailing: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val border = LocalZedTheme.current.color("border")
    val borderWidth = TabPixels.Border
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolGroupGap),
        modifier = Modifier
            .fillMaxHeight()
            .drawBehind {
                val thickness = borderWidth.toPx()
                drawRect(
                    color = border,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
                )
                val edge = if (trailing) 0f else size.width - thickness
                drawRect(
                    color = border,
                    topLeft = Offset(edge, 0f),
                    size = Size(thickness, size.height),
                )
            }
            .padding(horizontal = ToolGroupPadding),
        content = content,
    )
}

/**
 * Zed's IconButton in its `Subtle` ghost style: a 22dp box, transparent at
 * rest, `ghost_element.hover`/`.active` swapped instantly under the pointer
 * (button_like.rs:298-303). Disabled keeps the box and greys the glyph to
 * `text.disabled`, as a disabled `IconButton` does (Color::Disabled,
 * ui/src/styles/color.rs) — present but inert, so the group never reflows.
 */
@Composable
private fun TabBarIconButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // The glyph is text, not a drawable, so the box is sized from the
            // same TextUnit: `rem(1.375f)` — Zed's 22px button — until the
            // system's font scale makes the arrow taller than that, and then
            // the arrow. Width follows height so the box stays square.
            .size(maxOf(ToolButtonBox, glyphHeight(MaterialTheme.typography.labelMedium)))
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            .background(
                when {
                    !enabled -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                theme.color("icon", MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                theme.color("text.disabled", MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    }
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
    val tabInteraction = remember { MutableInteractionSource() }
    val tabHovered by tabInteraction.collectIsHoveredAsState()
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
    val borderWidth = TabPixels.Border
    Box(
        modifier = Modifier
            .height(TabBarHeight)
            .background(background)
            .drawBehind {
                val thickness = borderWidth.toPx()
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
                interactionSource = tabInteraction,
                // Zed's tabs do not change colour on hover (tab.rs:112-125
                // computes the hover fills and drops them); the source is only
                // for knowing when to show the ✕.
                indication = null,
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
                .padding(
                    start = TabPixels.Border,
                    end = TabPixels.Border,
                    bottom = TabPixels.Border,
                )
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
                    // Genuinely `px(12.)` in Zed (tab.rs:8), and what it holds
                    // is a dp-sized dot, so nothing here grows with the font.
                    .width(TabPixels.StartSlotWidth)
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
            // button that does that. The ✕ itself appears on hover, which is
            // Zed's default (pane.rs:3014-3015) — and on the active tab, which
            // is not: a finger has no hover, and the active tab's ✕ is the
            // only one-tap close it gets. Inactive tabs close from their
            // long-press menu or the wheel button.
            val showEnd = file.isPinned || isActive || tabHovered
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    // `px(14.)` in Zed (tab.rs:9) — but unlike the start slot
                    // this one holds *text*, so the pixel width is a minimum:
                    // at an accessibility font scale the ✕ is wider than 14dp
                    // and a fixed width would slice it down the middle.
                    .widthIn(min = TabPixels.EndSlotWidth)
                    .fillMaxHeight()
                    .then(
                        if (showEnd) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = if (file.isPinned) onTogglePin else onClose)
                        } else {
                            Modifier
                        }
                    ),
            ) {
                if (showEnd) {
                    Text(
                        text = if (file.isPinned) "⚑" else "✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
        horizontalArrangement = Arrangement.spacedBy(rem(1f)),
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = rem(0.875f), vertical = rem(0.5f)),
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
            .padding(horizontal = rem(0.25f), vertical = rem(0.125f)),
    )
}
