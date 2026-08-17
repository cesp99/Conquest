package to.eyed.conquest.code.ui.git

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.GitBranch
import to.eyed.conquest.code.core.GitSession
import to.eyed.conquest.code.core.ProjectSession

/** How often the shared status counter is re-read. Cheap; see [GitSession]. */
private const val POLL_MS = 500L

/**
 * The branch the project is on, for the title bar.
 *
 * It reads the *same* status run the project panel's colours and the git panel
 * both use — one `git status` per project, one counter to poll — so putting
 * the branch in the title bar costs a long read every half second and no git
 * at all.
 */
@Composable
fun rememberGitBranch(project: ProjectSession?): GitBranch? {
    var branch by remember(project) { mutableStateOf<GitBranch?>(null) }
    LaunchedEffect(project) {
        if (project == null) {
            branch = null
            return@LaunchedEffect
        }
        val session = GitSession(project)
        var seen = -1L
        while (true) {
            val version = withContext(Dispatchers.Default) { session.version }
            if (version != seen) {
                seen = version
                branch = withContext(Dispatchers.Default) { session.state().branch }
            }
            delay(POLL_MS)
        }
    }
    return branch
}
