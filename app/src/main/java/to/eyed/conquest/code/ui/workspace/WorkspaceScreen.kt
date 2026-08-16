package to.eyed.conquest.code.ui.workspace

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.ui.editor.EditorPane
import to.eyed.conquest.code.ui.editor.EditorState

private val WideLayoutMinWidth = 840.dp
private val ProjectPanelWidth = 260.dp

private val WELCOME_TEXT = """
    // Welcome to Conquest Code.
    //
    // This buffer lives inside the Rust engine (core/crates/engine):
    // Zed's rope/CRDT text stack, reached over JNI. The editor draws
    // only the visible line window, and the colors come from Zed's
    // tree-sitter highlight queries running inside the engine.
    //
    // The lines that follow are generated so you can put the
    // virtualized renderer through its paces. Fling away.

    const GREETING: &str = "Hello from the Rust core!";

""".trimIndent()

/** Welcome text plus generated lines — a scroll workout for the renderer. */
private fun sampleText(): String = buildString {
    append(WELCOME_TEXT)
    for (i in 1..5_000) {
        append("fn generated_$i() -> i32 { $i * ${i % 7} }  // scroll test, line ${i + 12}\n")
    }
}

/**
 * Root of the IDE UI, in the spirit of Zed's workspace: a project panel,
 * an editor area and a status bar. Wide screens (tablets, unfolded
 * foldables) get a fixed sidebar; compact screens (phones, folded) get a
 * slimmer layout with the panel in a drawer.
 */
@Composable
fun WorkspaceScreen(modifier: Modifier = Modifier) {
    val editorState = remember {
        EditorState(BufferSession(sampleText()).also { it.setLanguage("rust") })
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= WideLayoutMinWidth) {
            WideWorkspace(editorState)
        } else {
            CompactWorkspace(editorState)
        }
    }
}

@Composable
private fun WideWorkspace(editorState: EditorState) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(ProjectPanelWidth)
                .fillMaxHeight()
        ) {
            ProjectPanel()
        }
        VerticalDivider()
        EditorArea(editorState, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompactWorkspace(editorState: EditorState) {
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
            editorState,
            onOpenProjectPanel = { scope.launch { drawerState.open() } },
        )
    }
}

@Composable
private fun EditorArea(
    editorState: EditorState,
    modifier: Modifier = Modifier,
    onOpenProjectPanel: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        EditorPane(
            state = editorState,
            modifier = Modifier.weight(1f)
        )
        HorizontalDivider()
        StatusBar(
            cursorRow = editorState.cursorRow,
            cursorCol = editorState.cursorCol,
            onOpenProjectPanel = onOpenProjectPanel,
        )
    }
}
