package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Placeholder project panel. The real one renders the worktree from the
 * Rust engine (lazy directory scanning, gitignore awareness) — see
 * agent-docs/ROADMAP.md phase 2.
 */
@Composable
fun ProjectPanel(modifier: Modifier = Modifier) {
    val placeholderEntries = listOf(
        "▾ conquest-project",
        "  ▾ src",
        "    main.rs",
        "    lib.rs",
        "  Cargo.toml",
        "  README.md",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Text(
            text = "PROJECT",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(placeholderEntries) { entry ->
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
    }
}
