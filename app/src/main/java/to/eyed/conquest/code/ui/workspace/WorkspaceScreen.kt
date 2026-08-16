package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.ui.editor.EditorPane

private val WideLayoutMinWidth = 840.dp
private val ProjectPanelWidth = 260.dp

private const val SAMPLE_TEXT = """// Welcome to Conquest Code.
//
// Everything you see is rendered by Jetpack Compose, but this buffer
// lives inside the Rust engine (core/crates/engine), reached over JNI.
// Edit it: every change round-trips through native code.

fun main() {
    println("Hello from the Rust core!")
}
"""

/**
 * Root of the IDE UI, in the spirit of Zed's workspace: a project panel,
 * an editor area and a status bar. Wide screens (tablets, unfolded
 * foldables) get a fixed sidebar; compact screens (phones, folded) get a
 * slimmer layout with the panel in a drawer.
 */
@Composable
fun WorkspaceScreen(modifier: Modifier = Modifier) {
    val bufferId = remember { CoreBridge.createBuffer(SAMPLE_TEXT.trimStart()) }
    var bufferText by remember { mutableStateOf(CoreBridge.bufferText(bufferId).orEmpty()) }

    // Placeholder edit strategy: replace the whole buffer. Fine while the
    // engine buffer is a String; replaced by incremental edits with the rope.
    val onTextChange: (String) -> Unit = { newText ->
        val currentLen = bufferText.encodeToByteArray().size.toLong()
        if (CoreBridge.editBuffer(bufferId, 0, currentLen, newText)) {
            bufferText = newText
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= WideLayoutMinWidth) {
            WideWorkspace(bufferText, onTextChange)
        } else {
            CompactWorkspace(bufferText, onTextChange)
        }
    }
}

@Composable
private fun WideWorkspace(bufferText: String, onTextChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(ProjectPanelWidth)
                .fillMaxHeight()
        ) {
            ProjectPanel()
        }
        VerticalDivider()
        EditorArea(bufferText, onTextChange, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompactWorkspace(bufferText: String, onTextChange: (String) -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerState) {
                ProjectPanel()
            }
        }
    ) {
        EditorArea(
            bufferText,
            onTextChange,
            onOpenProjectPanel = { scope.launch { drawerState.open() } },
        )
    }
}

@Composable
private fun EditorArea(
    bufferText: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenProjectPanel: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        EditorPane(
            text = bufferText,
            onTextChange = onTextChange,
            modifier = Modifier.weight(1f)
        )
        HorizontalDivider()
        StatusBar(onOpenProjectPanel = onOpenProjectPanel)
    }
}
