package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** One row of a context menu: what it does, and the chord that also does it. */
internal data class ContextMenuItem(
    val label: String,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The menu a right-click or a long-press opens, in the shape the title bar's
 * menu already uses: label on the left, its shortcut on the right.
 *
 * [offset] moves it to where the pointer was, so a right-click drops the menu
 * under the cursor rather than at the corner of whatever was clicked.
 */
@Composable
internal fun ContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<ContextMenuItem>,
    offset: DpOffset = DpOffset.Zero,
    minWidth: Dp = 220.dp,
) {
    val theme = LocalZedTheme.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        containerColor = theme.color(
            "elevated_surface.background",
            MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.widthIn(min = minWidth)) {
            for (item in items) {
                ContextMenuRow(item, onChosen = onDismiss)
            }
        }
    }
}

@Composable
private fun ContextMenuRow(item: ContextMenuItem, onChosen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable {
                            onChosen()
                            item.onClick()
                        }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (item.shortcut != null) {
            Text(
                text = item.shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Right-click, reported with the position it happened at.
 *
 * Watched in the *initial* pass and consumed only for the secondary button, so
 * taps, drags and left-clicks reach whatever is underneath unchanged — which
 * matters where the thing underneath is an Android view, as in the terminal.
 */
@Composable
internal fun Modifier.onSecondaryClick(onClick: (DpOffset) -> Unit): Modifier =
    onButtonPress(secondary = true, onClick = onClick)

/** Middle-click. Closing a tab with the wheel button is a habit worth keeping. */
@Composable
internal fun Modifier.onMiddleClick(onClick: () -> Unit): Modifier =
    onButtonPress(secondary = false) { onClick() }

@Composable
private fun Modifier.onButtonPress(secondary: Boolean, onClick: (DpOffset) -> Unit): Modifier {
    // The pointer loop outlives recomposition, so it reads the callback
    // through a holder rather than closing over the one it started with.
    val latest by rememberUpdatedState(onClick)
    return pointerInput(secondary) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val buttons = event.buttons
                val wanted =
                    if (secondary) buttons.isSecondaryPressed else buttons.isTertiaryPressed
                if (!wanted) continue
                val position = event.changes.first().position
                event.changes.forEach { it.consume() }
                latest(DpOffset(position.x.toDp(), position.y.toDp()))
            }
        }
    }
}
