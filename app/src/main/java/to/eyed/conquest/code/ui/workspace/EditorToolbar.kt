package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.R
import to.eyed.conquest.code.ui.preview.PreviewKind
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/**
 * The row under the tab strip: Zed's toolbar, and specifically the quick
 * action bar at the right of it.
 *
 * Only one action so far — the eye that shows a Markdown or SVG preview,
 * exactly the button Zed's `quick_action_bar/preview.rs` renders and for
 * exactly the same two file kinds. Breadcrumbs are the other half of Zed's
 * toolbar and are not written yet, which is why this row appears *only* when
 * there is something in it: an empty 32dp band above every file would be a
 * strip of chrome that does nothing, on a device where vertical space is the
 * scarcest thing there is.
 */
private val ToolbarHeight = 34.dp

/** Zed draws a 26px button; a finger needs more than that around it. */
private val TouchTarget = 32.dp
private val IconSize = 16.dp

@Composable
fun EditorToolbar(
    kind: PreviewKind,
    isPreviewOpen: Boolean,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ToolbarHeight)
            .background(theme.color("toolbar.background", theme.color("editor.background")))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        val label = when (kind) {
            PreviewKind.Markdown -> "Preview Markdown"
            PreviewKind.Svg -> "Preview SVG"
        }
        Box(
            modifier = Modifier
                .size(TouchTarget)
                .clip(RoundedCornerShape(4.dp))
                // The pressed look is the background Zed gives a toggled
                // button, not a different icon: the eye stays the eye.
                .background(
                    if (isPreviewOpen) {
                        theme.color("element.selected", theme.color("border"))
                    } else {
                        theme.color("editor.background")
                    }
                )
                .clickable(onClickLabel = label, onClick = onTogglePreview)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_ui_eye),
                contentDescription = label,
                colorFilter = ColorFilter.tint(
                    if (isPreviewOpen) theme.color("icon.accent", theme.color("icon")) else theme.color("icon")
                ),
                modifier = Modifier.size(IconSize),
            )
        }
    }
}
