package to.eyed.conquest.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.ui.theme.ConquestCodeByEyedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConquestCodeByEyedTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EngineStatus(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun EngineStatus(modifier: Modifier = Modifier) {
    Text(
        text = "Conquest Code — engine v${CoreBridge.engineVersion()}",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun EngineStatusPreview() {
    ConquestCodeByEyedTheme {
        EngineStatus()
    }
}