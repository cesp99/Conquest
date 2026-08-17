package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.R
import to.eyed.conquest.code.core.GitBranch
import to.eyed.conquest.code.ui.theme.LocalZedTheme

// Zed: max(1.75rem, 34px) — 34 at the default 16px rem
// (crates/ui/src/utils/constants.rs:17-19).
private val TitleBarHeight = 34.dp

/**
 * Everything that sits in the bar is a ButtonLike at `ButtonSize::Default`:
 * a 22px-tall box (button_like.rs:469) with `px(Base04)` = 4px inside
 * (button_like.rs:800-801), `rounded_sm` corners (button_like.rs:527) and
 * `gap(Base04)` = 4px between its children (button_like.rs:797).
 */
private val BarButtonHeight = 22.dp
private val BarButtonPad = 4.dp
private val BarButtonGap = 4.dp

/** One entry in the menu: what it does, and the chord that also does it. */
data class MenuAction(
    val label: String,
    val shortcut: String?,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The workspace title bar, in the shape Zed uses: a menu button on the left,
 * then what you have open.
 *
 * Project- and file-level commands live here rather than in the status bar.
 * The status bar is for *state* — where the cursor is, what language, what
 * the panel is doing — and the top bar is for *actions*, which is both Zed's
 * split and the one that survives the soft keyboard covering the bottom of
 * the screen.
 */
@Composable
fun TitleBar(
    projectName: String?,
    filePath: String?,
    isDirty: Boolean,
    menuGroups: List<List<MenuAction>>,
    modifier: Modifier = Modifier,
    /** The branch the project is on, as Zed shows it beside the name. */
    branch: GitBranch? = null,
    /** Opens the git panel, which is what Zed's branch control leads to. */
    onBranch: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The left group's rhythm is `gap_0p5` = 2px between the buttons,
        // whose own 4px `px` makes the visible gap (title_bar.rs:295).
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(theme.color("title_bar.background", theme.color("tab_bar.background")))
            // Left only, as Zed's `pl_2`: the right end is a button group
            // that brings its own padding (title_bar/src/title_bar.rs:417).
            .padding(start = 8.dp),
    ) {
        Box {
            val menuInteraction = remember { MutableInteractionSource() }
            val menuHovered by menuInteraction.collectIsHoveredAsState()
            val menuPressed by menuInteraction.collectIsPressedAsState()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(BarButtonHeight)
                    .clip(RoundedCornerShape(4.dp))
                    // A ghost button's states, swapped instantly: `Subtle` is
                    // `ghost_element.hover` under the pointer and
                    // `ghost_element.active` while pressed
                    // (button_like.rs:298-303, 324-329).
                    .background(
                        when {
                            menuPressed -> theme.color("ghost_element.active", Color.Transparent)
                            menuHovered -> theme.color("ghost_element.hover", Color.Transparent)
                            else -> Color.Transparent
                        }
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = menuInteraction, indication = null) {
                        menuOpen = true
                    }
                    .padding(horizontal = BarButtonPad),
            ) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // Zed's `elevation_2`: an elevated surface, `rounded_lg` 8px,
                // and a 1px border in `border.variant` (styled_ext.rs:6-12) —
                // the same container every context menu wears
                // (context_menu.rs:2274).
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, theme.color("border.variant")),
                containerColor = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
            ) {
                // Scrollable, and it has to be: the menu has outgrown the
                // screen. Material's DropdownMenu clips to the window and does
                // not scroll on its own, so the last entries — settings, and
                // removing the userland — were simply unreachable on a
                // 674dp-tall window, and worse on a phone.
                // Bounded *and* scrollable, in that order: DropdownMenu
                // measures its content with an infinite maximum height, and a
                // scroller inside that throws. Capping it against the window
                // is what gives the scroller something finite to work with —
                // and without the cap the menu simply ran off the bottom of
                // the screen, taking settings and "remove userland" with it.
                val maxMenuHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
                // Entries are inset ListItems: 4px of surface around each row,
                // the row itself `rounded_sm` with 6px inside
                // (list_item.rs:309, 364, 405).
                Column(
                    modifier = Modifier
                        .widthIn(min = 260.dp)
                        .heightIn(max = maxMenuHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp)
                ) {
                    menuGroups.forEachIndexed { index, group ->
                        if (index > 0) {
                            // Zed's ListSeparator: 1px of `border.variant`
                            // with 6px above and below (list_separator.rs:9-12).
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .height(1.dp)
                                    .background(theme.color("border.variant")),
                            )
                        }
                        for (action in group) {
                            MenuRow(action) { menuOpen = false }
                        }
                    }
                }
            }
        }

        if (projectName != null) {
            // In Zed this is a Button opening the recent-projects picker —
            // `LabelSize::Small` in `text`, ghost hover, `rounded_sm`
            // (title_bar.rs:841-853). We have no recent-projects picker yet,
            // so the ButtonLike dress stays and the popover does not: hover
            // per the grammar, but no hand cursor over a control that has
            // nothing to do when clicked.
            val nameInteraction = remember { MutableInteractionSource() }
            val nameHovered by nameInteraction.collectIsHoveredAsState()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(BarButtonHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (nameHovered) {
                            theme.color("ghost_element.hover", Color.Transparent)
                        } else {
                            Color.Transparent
                        }
                    )
                    .hoverable(nameInteraction)
                    .padding(horizontal = BarButtonPad),
            ) {
                Text(
                    text = projectName,
                    // LabelSize::Small = 12px (title_bar.rs:842) — labelMedium
                    // on our scale — at the plain 400 weight Zed's labels keep.
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Zed's own order: the app menu, the project, then the branch
        // (title_bar/src/title_bar.rs). A repository with no commits yet has a
        // branch name and nothing on it, which is worth seeing too.
        if (branch != null) {
            val branchInteraction = remember { MutableInteractionSource() }
            val branchHovered by branchInteraction.collectIsHoveredAsState()
            val branchPressed by branchInteraction.collectIsPressedAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BarButtonGap),
                modifier = Modifier
                    .height(BarButtonHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            branchPressed && onBranch != null ->
                                theme.color("ghost_element.active", Color.Transparent)
                            branchHovered ->
                                theme.color("ghost_element.hover", Color.Transparent)
                            else -> Color.Transparent
                        }
                    )
                    .then(
                        if (onBranch != null) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = branchInteraction,
                                    indication = null,
                                    onClickLabel = "Open the git panel",
                                    onClick = onBranch,
                                )
                        } else {
                            Modifier.hoverable(branchInteraction)
                        }
                    )
                    .padding(horizontal = BarButtonPad),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ui_git_branch),
                    contentDescription = null,
                    // `IconSize::XSmall` = 12px in `Color::Muted`, exactly the
                    // branch button's start icon (title_bar.rs:1043-1047).
                    colorFilter = ColorFilter.tint(
                        theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                    ),
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    // The branch label is `LabelSize::Small` in `Color::Muted`
                    // (title_bar.rs:1038-1042).
                    text = branch.name ?: "no branch",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                )
                // Ahead and behind, in git's own arrows — the same pair the
                // git panel's header shows.
                val drift = buildString {
                    if (branch.ahead > 0) append("↑${branch.ahead}")
                    if (branch.behind > 0) {
                        if (isNotEmpty()) append(' ')
                        append("↓${branch.behind}")
                    }
                }
                if (drift.isNotEmpty()) {
                    Text(
                        text = drift,
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color(
                            "text.muted",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        if (filePath != null) {
            Text(
                text = if (isDirty) "$filePath •" else filePath,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = BarButtonPad)
                    .weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun MenuRow(action: MenuAction, onChosen: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // `ml_4` between a label and its keybinding (context_menu.rs:2089).
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            // A ghost row: `ghost_element.hover` under the pointer,
            // `ghost_element.active` while pressed (list_item.rs:380-385).
            .background(
                when {
                    !action.enabled -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (action.enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        // Instant colour swap, no ripple, as everywhere in Zed.
                        .clickable(interactionSource = interaction, indication = null) {
                            onChosen()
                            action.onClick()
                        }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (action.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                theme.color("text.disabled", MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.weight(1f),
        )
        if (action.shortcut != null) {
            Text(
                text = action.shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
