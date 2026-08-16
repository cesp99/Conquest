package to.eyed.conquest.code.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Placeholder editor pane: a monospace text field with a line-number
 * gutter. This is NOT the final editor — the real one is a custom
 * canvas-rendered surface driven by the Rust engine (rope + tree-sitter
 * highlighting), planned in agent-docs/ROADMAP.md phase 2. This exists to
 * exercise the JNI buffer round-trip and give the shell something to show.
 * [lineCount] comes from the engine (`bufferLineCount`), not from counting
 * newlines in [text].
 */
@Composable
fun EditorPane(
    text: String,
    lineCount: Int,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editorTextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
    ) {
        Column(modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp)) {
            for (line in 1..lineCount) {
                Text(
                    text = line.toString(),
                    style = editorTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = editorTextStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .padding(top = 8.dp, end = 8.dp)
                .fillMaxSize(),
        )
    }
}
