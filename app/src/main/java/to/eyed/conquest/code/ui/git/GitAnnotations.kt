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
import to.eyed.conquest.code.core.BlameLine
import to.eyed.conquest.code.core.FileBlame
import to.eyed.conquest.code.core.GitDiff
import to.eyed.conquest.code.core.GitHunk
import to.eyed.conquest.code.ui.editor.EditorState

/** How often the engine's hunk counter is re-read. */
private const val HUNK_POLL_MS = 250L

/**
 * What git has to say about the open file, as the editor draws it: the
 * gutter's hunks, and who last touched the line the caret is on.
 *
 * Two very different costs behind one holder, which is why it is one holder.
 * Hunks are a cache in the engine — reading the counter is what schedules the
 * diff, and reading the hunks never runs git — so they are polled. Blame
 * **runs git**, inside proot, over the whole file: that is hundreds of
 * milliseconds, and it happens when the file is opened and when it is saved,
 * never on a keystroke and never on a caret move.
 */
class GitAnnotations(
    val hunks: List<GitHunk>,
    /** Null when blame is off, still loading, or git had nothing to say. */
    val blame: FileBlame?,
) {
    fun blameAt(row: Int): BlameLine? = blame?.at(row)

    companion object {
        val NONE = GitAnnotations(emptyList(), null)
    }
}

/**
 * @param showBlame whether to run blame at all — the editor's own setting.
 *   Blame is only shown while the buffer is **clean**: it describes the file
 *   on disk, and once there are unsaved edits its row numbers describe a file
 *   that no longer exists. Zed can blame the buffer itself; this engine
 *   blames the file, so the honest thing is to say nothing rather than to
 *   attribute somebody else's line to a commit.
 */
@Composable
fun rememberGitAnnotations(editor: EditorState, showBlame: Boolean): GitAnnotations {
    val session = editor.sessionOrNull
    var hunks by remember(session) { mutableStateOf(emptyList<GitHunk>()) }
    var blame by remember(session) { mutableStateOf<FileBlame?>(null) }
    // Bumped every time the buffer becomes clean — which is every save, and is
    // the only moment blame can have changed.
    var savedToken by remember(session) { mutableStateOf(0) }
    var isDirty by remember(session) { mutableStateOf(false) }

    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        var seenHunks = -1L
        var wasDirty = false
        while (true) {
            // Both of these are JNI calls that take the engine's locks; neither
            // belongs on the frame's thread, cheap as they are.
            val update = withContext(Dispatchers.Default) {
                val version = GitDiff.hunksVersion(session.id)
                val dirty = session.isDirty
                val rows = if (version != seenHunks) GitDiff.hunks(session.id) else null
                Triple(version, dirty, rows)
            }
            seenHunks = update.first
            update.third?.let { hunks = it }
            if (update.second != wasDirty) {
                wasDirty = update.second
                isDirty = update.second
                if (!update.second) savedToken++
            }
            delay(HUNK_POLL_MS)
        }
    }

    LaunchedEffect(session, showBlame, savedToken) {
        if (session == null || !showBlame) {
            blame = null
            return@LaunchedEffect
        }
        // git, through proot: IO, and never on the poll loop above.
        blame = withContext(Dispatchers.IO) { GitDiff.blame(session.id) }
    }

    return remember(hunks, blame, isDirty) {
        GitAnnotations(hunks, blame.takeIf { !isDirty })
    }
}
