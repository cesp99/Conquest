package to.eyed.conquest.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import to.eyed.conquest.code.core.AppSettings
import to.eyed.conquest.code.core.CoreBridge
import java.io.File
import to.eyed.conquest.code.ui.theme.ConquestCodeByEyedTheme
import to.eyed.conquest.code.ui.workspace.WorkspaceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else reaches the engine: it needs to know where the
        // app's private storage is (Android gives a process no $HOME, and the
        // Zed crates require one).
        CoreBridge.initialize(filesDir.absolutePath)
        // Read once, synchronously: the theme is chosen from it, and loading
        // it asynchronously would mean painting the wrong theme first and
        // flashing to the right one. It is a single ~700-byte read of
        // app-private storage, not the kind of I/O the main thread must be
        // kept away from.
        val initialSettings = AppSettings.load()
        enableEdgeToEdge()
        setContent {
            var settings by remember { mutableStateOf(initialSettings) }
            ConquestCodeByEyedTheme(settings) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WorkspaceScreen(
                        settings = settings,
                        settingsPath = File(filesDir, "settings.json").absolutePath,
                        onSettingsChanged = { settings = it },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
