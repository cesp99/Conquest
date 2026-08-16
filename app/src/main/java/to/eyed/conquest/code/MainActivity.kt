package to.eyed.conquest.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import to.eyed.conquest.code.ui.theme.ConquestCodeByEyedTheme
import to.eyed.conquest.code.ui.workspace.WorkspaceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
