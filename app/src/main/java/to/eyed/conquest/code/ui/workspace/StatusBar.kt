package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.core.CoreBridge

/**
 * Zed-style status bar. On compact layouts it also hosts the project panel
 * toggle (Zed keeps panel toggles here too).
 */
@Composable
fun StatusBar(
    modifier: Modifier = Modifier,
    onOpenProjectPanel: (() -> Unit)? = null,
) {
    val engineVersion = remember { CoreBridge.engineVersion() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onOpenProjectPanel != null) {
            Text(
                text = "☰ files",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onOpenProjectPanel)
                    .padding(end = 12.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "engine v$engineVersion",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
