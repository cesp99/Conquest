package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * gone; the whole eye button's actions stay a chord away (Ctrl+Shift+M).
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
 * The row under the tab strip: Zed's toolbar, and specifically the quick
 * action bar at the right of it.
 *
 * Only one action so far — the eye that shows a Markdown or SVG preview,
 * exactly the button Zed's `quick_action_bar/preview.rs` renders and for
 * exactly the same two file kinds. Breadcrumbs are the other half of Zed's
 * toolbar and are not written yet, which is why this row appears *only* when
 * there is something in it: an empty band above every file would be a strip of
 * chrome that does nothing, on a device where vertical space is the scarcest
 * thing there is.
 */
@Composable
fun EditorToolbar(
    kind: PreviewKind,
    isPreviewOpen: Boolean,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
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
    ) {
        Spacer(modifier = Modifier.weight(1f))
        val label = when (kind) {
            PreviewKind.Markdown -> "Preview Markdown"
            PreviewKind.Svg -> "Preview SVG"
        }
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
                    onClick = onTogglePreview,
                )
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_ui_eye),
                contentDescription = label,
                // A toggled IconButton keeps its ghost background and swaps
                // the glyph to `Color::Selected` = `text.accent`
                // (icon_button.rs:246-248, color.rs:108): the eye stays the
                // eye, it just lights up.
                colorFilter = ColorFilter.tint(
                    if (isPreviewOpen) {
                        theme.color("text.accent", theme.color("icon"))
                    } else {
                        theme.color("text", theme.color("icon"))
                    }
                ),
                modifier = Modifier.size(IconSize),
            )
        }
    }
}
