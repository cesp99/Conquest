package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.R
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** One panel's button: which panel, whether its dock is showing it, and the tap. */
data class PanelButton(
    val panel: WorkspacePanel,
    val isOpen: Boolean,
    val onClick: () -> Unit,
)

/**
 * Every item is Zed's IconButton at `ButtonSize::Default`: a 22px box
 * (button_like.rs:469) with `rounded_sm` corners (button_like.rs:527) around
 * an `IconSize::Small` 14px glyph (status_bar.rs:187 spec; dock.rs:1398-1400).
 * Two of them plus the bar's 4px `p(Base04)` is the whole 30px height.
 */
private val ItemBox = 22.dp
private val ItemIconSize = 14.dp

/**
 * Zed-style status bar: **state, not actions**.
 *
 * Zed splits these deliberately — the title bar holds commands, the status bar
 * reports where you are and which panels are up. Everything that *does*
 * something to a project or a file lives in the title-bar menu, which also
 * keeps it reachable when the soft keyboard covers the bottom of the screen.
 *
 * The panel buttons follow their docks, exactly as Zed's do: the left dock's
 * buttons at the left end of the bar, the right dock's at the right end, and
 * the bottom dock's — the terminal — at the right after them
 * (`workspace.rs:1757-1759`). Move a panel across in settings and its button
 * moves with it, which is the only arrangement in which the button says where
 * the panel will appear.
 */
@Composable
fun StatusBar(
    cursorRow: Int,
    cursorCol: Int,
    modifier: Modifier = Modifier,
    language: String? = null,
    hasFile: Boolean = false,
    /** Panels docked left, in the order they appear in the enum. */
    leftPanels: List<PanelButton> = emptyList(),
    rightPanels: List<PanelButton> = emptyList(),
    isTerminalOpen: Boolean = false,
    onToggleTerminal: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 30 = a 22px default button plus 4px of padding on each side,
            // which is how Zed's status bar gets its height rather than by
            // declaring one (crates/workspace/src/status_bar.rs:153).
            .height(30.dp)
            .background(theme.color("status_bar.background"))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        // `gap_1` = 4px within a group (status_bar.rs:196, 215).
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (button in leftPanels) {
            PanelStatusButton(button)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (hasFile) {
            // Zed writes the caret as line:column — both it and the language
            // are `Label`s at the default colour, `text`, not muted
            // (cursor_position.rs:210-247).
            Text(
                text = "${cursorRow + 1}:${cursorCol + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (language != null) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // A dock group is fenced with a 1px × 16px divider on the side facing
        // the middle (dock.rs:1433-1446, divider.rs:29, 147-149).
        if (rightPanels.isNotEmpty() || onToggleTerminal != null) {
            GroupDivider(theme.color("border"))
        }
        for (button in rightPanels) {
            PanelStatusButton(button)
        }
        if (onToggleTerminal != null) {
            // The touch twin of Ctrl+`, and the only way to reach a terminal
            // on a device with no keyboard attached. At the right end, where
            // Zed puts its bottom dock's buttons.
            StatusIconAction(
                icon = R.drawable.ic_ui_terminal,
                label = "Toggle the terminal",
                emphasised = isTerminalOpen,
                onClick = onToggleTerminal,
            )
        }
    }
}

@Composable
private fun PanelStatusButton(button: PanelButton) {
    StatusIconAction(
        icon = button.panel.icon,
        label = if (button.isOpen) "Close the ${button.panel.title}" else button.panel.title,
        emphasised = button.isOpen,
        onClick = button.onClick,
    )
}

@Composable
private fun StatusIconAction(
    icon: Int,
    label: String,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ItemBox)
            .clip(RoundedCornerShape(4.dp))
            // `Subtle`, a ghost button: transparent at rest,
            // `ghost_element.hover` under the pointer, `ghost_element.active`
            // while pressed, swapped instantly — no ripple
            // (button_like.rs:298-303, 324-329).
            .background(
                when {
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            // An open panel's button is `toggle_state(true)` (dock.rs:1400):
            // the box stays ghost and the glyph swaps to `Color::Selected` =
            // `text.accent` (icon_button.rs:246-248, color.rs:108). At rest
            // the glyph is `Color::Default` = `text` (color.rs:92).
            colorFilter = ColorFilter.tint(
                if (emphasised) {
                    theme.color("text.accent", MaterialTheme.colorScheme.onSurface)
                } else {
                    theme.color("text", MaterialTheme.colorScheme.onSurface)
                }
            ),
            modifier = Modifier.size(ItemIconSize),
        )
    }
}

/**
 * Zed's `Divider::vertical()` between the middle and a dock's button group:
 * 1px wide, `h_4` (16px) tall, in `border` (divider.rs:29, 147-149;
 * dock.rs:1433-1446).
 */
@Composable
private fun GroupDivider(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(color)
    )
}
