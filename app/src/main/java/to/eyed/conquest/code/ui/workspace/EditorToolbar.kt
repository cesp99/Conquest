package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.R
import to.eyed.conquest.code.ui.preview.PreviewKind
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/**
 * Zed's toolbar frame: `py(Base06)` = 6px and `px(Base08)` = 8px around a
 * 32px item row (workspace/src/toolbar.rs:123-124, 140, 150), on
 * `toolbar.background` with a 1px `border.variant` underline
 * (toolbar.rs:128-130). Per the 2026-08-17 density decision the 40dp floor is
 * gone; every button's action stays a chord away.
 */
private val ToolbarItemRowHeight = 32.dp
private val ToolbarVerticalPad = 6.dp
private val ToolbarHorizontalPad = 8.dp

/**
 * Zed's IconButton at `ButtonSize::Default`: a 22px box (button_like.rs:469)
 * around an `IconSize::Small` 14px glyph — the exact button the quick action
 * bar's eye is (quick_action_bar/preview.rs:66-68).
 */
private val ButtonBox = 22.dp
private val IconSize = 14.dp

/**
 * The row under the tab strip: Zed's toolbar — breadcrumbs on the left
 * (crates/breadcrumbs/src/breadcrumbs.rs), the quick action bar on the right.
 *
 * The breadcrumb text is the file name, then the engine's symbol path at the
 * caret ("impl Foo" › "fn bar"), separated by Zed's own `›` glyph in
 * `text.placeholder` with the segments muted (editor/src/element.rs:6793,
 * 6809). Zed's crumbs are a button that opens the outline; ours are plain
 * text until an outline picker exists.
 *
 * Quick actions: the magnifier toggles the search bar — the touch twin of
 * Ctrl+F, which Zed's quick action bar carries in the same spot — and for a
 * previewable file, Zed's eye (quick_action_bar/preview.rs).
 */
@Composable
fun EditorToolbar(
    fileName: String,
    symbolPath: List<String>,
    onToggleSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    kind: PreviewKind? = null,
    isPreviewOpen: Boolean = false,
    onTogglePreview: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val underline = theme.color("border.variant")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("toolbar.background", theme.color("editor.background")))
            // The underline is drawn inside the frame, as gpui draws borders
            // (toolbar.rs:128-129).
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(
                    color = underline,
                    topLeft = Offset(0f, size.height - line),
                    size = Size(size.width, line),
                )
            }
            .padding(horizontal = ToolbarHorizontalPad, vertical = ToolbarVerticalPad)
            .height(ToolbarItemRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        // Base08 between the crumbs and the button group (toolbar.rs:136).
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Breadcrumbs scroll off to the left rather than squeezing the
        // buttons, as Zed's `overflow_x_scroll` container does
        // (breadcrumbs.rs:53-55).
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            // `gap_1` between segments (editor/src/element.rs:6813).
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
            )
            for (segment in symbolPath) {
                Text(
                    // Zed's separator is the literal glyph, a Label in
                    // `text.placeholder` (element.rs:6809).
                    text = "›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder"),
                    maxLines = 1,
                )
                Text(
                    text = segment,
                    style = MaterialTheme.typography.bodyMedium,
                    // Segments are `Color::Muted` (element.rs:6793).
                    color = theme.color("text.muted"),
                    maxLines = 1,
                )
            }
        }
        if (onToggleSearch != null) {
            QuickActionButton(
                icon = R.drawable.ic_ui_magnifying_glass,
                label = "Find in file",
                isOn = false,
                onClick = onToggleSearch,
            )
        }
        if (kind != null && onTogglePreview != null) {
            val label = when (kind) {
                PreviewKind.Markdown -> "Preview Markdown"
                PreviewKind.Svg -> "Preview SVG"
            }
            QuickActionButton(
                icon = R.drawable.ic_ui_eye,
                label = label,
                isOn = isPreviewOpen,
                onClick = onTogglePreview,
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: Int,
    label: String,
    isOn: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ButtonBox)
            .clip(RoundedCornerShape(4.dp))
            // `Subtle`, a ghost button: transparent at rest,
            // `ghost_element.hover` under the pointer and
            // `ghost_element.active` while pressed, swapped instantly
            // (button_like.rs:298-303, 324-329).
            .background(
                when {
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            // A toggled IconButton keeps its ghost background and swaps the
            // glyph to `Color::Selected` = `text.accent`
            // (icon_button.rs:246-248, color.rs:108).
            colorFilter = ColorFilter.tint(
                if (isOn) {
                    theme.color("text.accent", theme.color("icon"))
                } else {
                    theme.color("text", theme.color("icon"))
                }
            ),
            modifier = Modifier.size(IconSize),
        )
    }
}
