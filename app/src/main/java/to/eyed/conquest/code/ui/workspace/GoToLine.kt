package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.ui.editor.Caret
import to.eyed.conquest.code.ui.editor.EditorState
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** A line, and optionally a column, both as the user counts them: from 1. */
internal data class GoToLineTarget(val line: Int, val column: Int?)

/**
 * Read what was typed into a target, or null if it is not one yet.
 *
 * `42` and `42:8` are Zed's forms. A comma is accepted for the same thing
 * because a soft keyboard puts the colon behind a modifier key and the digit
 * row already carries the comma — the cost of allowing it is nothing, and on a
 * phone it is the difference between one keypress and three.
 *
 * A trailing separator (`42:`) is a line with no column rather than an error:
 * it is the state the field is in halfway through typing `42:8`, and blanking
 * the preview at that moment would make the caret jump back and forth.
 */
internal fun parseGoToLine(input: String): GoToLineTarget? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val parts = text.split(':', ',')
    if (parts.size > 2) return null
    val line = parts[0].trim().toIntOrNull()?.takeIf { it >= 1 } ?: return null
    val columnText = parts.getOrNull(1)?.trim()
    if (columnText.isNullOrEmpty()) return GoToLineTarget(line, null)
    val column = columnText.toIntOrNull()?.takeIf { it >= 1 } ?: return null
    return GoToLineTarget(line, column)
}

/**
 * Where [target] actually lands: a row and a UTF-16 column, both 0-based and
 * both clamped to the buffer.
 *
 * Clamped rather than refused, which is what every editor with this command
 * does: `9999` in a 300-line file means the end of the file, and telling
 * somebody their number is too big helps nobody.
 */
internal fun goToLinePosition(
    target: GoToLineTarget,
    lineCount: Int,
    lengthOfRow: (Int) -> Int,
): Pair<Int, Int> {
    val row = (target.line - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    val column = ((target.column ?: 1) - 1).coerceIn(0, lengthOfRow(row))
    return row to column
}

/** Zed's own control size, drawn inside a target a finger can hit. */
private val FieldHeight = 26.dp
private val TouchTarget = 40.dp
private val FieldRadius = 6.dp

/** `rounded_lg`, the radius every elevated surface in this app wears. */
private val SurfaceRadius = 8.dp

/**
 * Go to line — Zed's `go_to_line::Toggle`, on `Ctrl` `G`.
 *
 * A small panel over the editor rather than a dialog, and deliberately: the
 * caret moves *while* you type, and a dialog's scrim would dim the file you
 * are watching go past. Escape puts the caret, the selection and the viewport
 * back exactly where they were; Enter keeps the move.
 *
 * [modifier] is where the caller places it — `Modifier.align(Alignment.TopCenter)`
 * inside the work area, which is where Zed's own goes.
 */
@Composable
internal fun GoToLine(
    editor: EditorState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    var query by remember(editor) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }

    // What Escape restores. Captured once per editor, before anything moves:
    // the whole caret set rather than the cursor's row and column, so a
    // cancelled jump gives back the selection and the extra carets too.
    val original = remember(editor) {
        Triple(editor.caretsInOrder(), editor.primaryCaret(), editor.scrollY)
    }

    LaunchedEffect(editor) { focus.requestFocus() }

    fun moveTo(target: GoToLineTarget) {
        val (row, column) = goToLinePosition(target, editor.lineCount) { editor.line(it).length }
        val caret = Caret(row, column)
        // `setCarets` is the one door: it drops the extra carets, clears the
        // selection and scrolls the caret into view, which is the whole of
        // what this command means.
        editor.setCarets(listOf(caret), caret)
    }

    fun restore() {
        val (carets, primary, scrollY) = original
        editor.setCarets(carets, primary)
        // …and the viewport with it. `setCarets` only scrolls far enough to
        // show the caret, which after a jump to line 4000 is not where the
        // reader was.
        editor.scrollToY(scrollY)
    }

    fun cancel() {
        restore()
        onDismiss()
    }

    Box(
        modifier = modifier
            .padding(8.dp)
            .width(260.dp)
            .clip(RoundedCornerShape(SurfaceRadius))
            .background(theme.color("elevated_surface.background"))
            .border(1.dp, theme.color("border.variant"), RoundedCornerShape(SurfaceRadius))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        cancel()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        // The caret is already where the preview put it; Enter
                        // only agrees with it. An unparseable field leaves the
                        // caret alone, so this closes without moving anything.
                        onDismiss()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(FieldHeight)
                    .clip(RoundedCornerShape(FieldRadius))
                    .background(theme.color("editor.background"))
                    .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
                    .pointerHoverIcon(PointerIcon.Text)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { value ->
                        query = value
                        val target = parseGoToLine(value.text)
                        // A half-typed or emptied field puts the caret back
                        // rather than leaving it wherever the last valid
                        // number happened to land.
                        if (target == null) restore() else moveTo(target)
                    },
                    singleLine = true,
                    // Deliberately *not* `KeyboardType.Number`: the numeric pad
                    // is a nicer target for the digits and on most IMEs it has
                    // no colon at all, which would leave the column half of
                    // this command unreachable by touch. The ordinary keyboard
                    // has every character, and [parseGoToLine] refuses the rest.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    // The soft keyboard's own Go key never reaches the panel's
                    // key handler, so it is answered here as well.
                    keyboardActions = KeyboardActions(onGo = { onDismiss() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = theme.color("text"),
                    ),
                    cursorBrush = SolidColor(theme.cursor),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (query.text.isEmpty()) {
                    Text(
                        text = "Go to line: column",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text.placeholder"),
                        maxLines = 1,
                    )
                }
            }
            Action("↵", "Go to the line", onClick = onDismiss)
            Action("✕", "Cancel", onClick = ::cancel)
        }
    }
}

/**
 * One of the two buttons. Zed's controls are 26px, drawn for a mouse; these
 * keep that size inside a 40dp box, because the soft keyboard is up while this
 * panel is open and a thumb is the only pointer there is.
 */
@Composable
private fun Action(glyph: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .height(TouchTarget)
            .width(TouchTarget)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("icon"),
        )
    }
}
