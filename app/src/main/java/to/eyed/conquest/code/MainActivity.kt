package to.eyed.conquest.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.ui.theme.ConquestCodeByEyedTheme
import to.eyed.conquest.code.ui.workspace.WorkspaceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else reaches the engine: it needs to know where the
        // app's private storage is (Android gives a process no $HOME, and the
        // Zed crates require one).
        CoreBridge.initialize(filesDir.absolutePath)
        enableEdgeToEdge()
        setContent {
            ConquestCodeByEyedTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WorkspaceScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
