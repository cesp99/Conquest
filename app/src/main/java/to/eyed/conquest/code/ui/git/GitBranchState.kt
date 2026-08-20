package to.eyed.conquest.code.ui.git

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.GitBranch
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.ResumedEffect
import to.eyed.conquest.code.core.pollVersion

/** How often the shared status counter is re-read. Cheap; see [GitBranch]. */
private const val POLL_MS = 500L

/**
 * The branch the project is on, for the title bar.
 *
 * It reads the *same* status run the project panel's colours and the git panel
 * both use — one `git status` per project, one counter to poll — through
 * [CoreBridge.gitBranch], which hands back the cached name alone. The full
 * [to.eyed.conquest.code.core.GitSession.state] read serializes and parses
 * every changed file, which a title bar has no use for.
 *
 * Null when no branch can be named — no repository, no status run yet, or a
 * detached HEAD — and the title bar shows nothing rather than guessing.
 */
@Composable
fun rememberGitBranch(project: ProjectSession?): GitBranch? {
    var branch by remember(project) { mutableStateOf<GitBranch?>(null) }
    ResumedEffect(project) {
        if (project == null) return@ResumedEffect
        pollVersion(
            intervalMs = POLL_MS,
            version = { project.gitStatusVersion },
            read = { _ -> CoreBridge.gitBranch(project.id)?.let { GitBranch(name = it) } },
            apply = { branch = it },
        )
    }
    return branch
}
