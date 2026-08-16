package to.eyed.conquest.code.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The terminal dock: a list of shell sessions and which one is showing.
 *
 * Sessions outlive the panel being hidden — a build keeps running while you
 * read code — but not a project switch, since a shell sitting in a directory
 * the workspace no longer has open is a trap rather than a convenience.
 */
class TerminalPanelState {

    private val entries = mutableStateListOf<TerminalSessionHost>()

    val sessions: List<TerminalSessionHost> get() = entries

    var activeIndex by mutableStateOf(-1)
        private set

    var isOpen by mutableStateOf(false)
        private set

    val active: TerminalSessionHost? get() = entries.getOrNull(activeIndex)

    private var created = 0

    /** Show the dock, starting a first shell in [cwd] if there is none. */
    fun open(context: Context, cwd: String) {
        if (entries.isEmpty()) newSession(context, cwd) else isOpen = true
    }

    fun hide() {
        isOpen = false
    }

    fun toggle(context: Context, cwd: String) {
        if (isOpen) hide() else open(context, cwd)
    }

    /** Start another shell and show it. Must be called on the main thread. */
    fun newSession(context: Context, cwd: String) {
        created += 1
        entries.add(TerminalSessionHost(context, cwd, "shell $created"))
        activeIndex = entries.lastIndex
        isOpen = true
    }

    fun select(index: Int) {
        if (index in entries.indices) activeIndex = index
    }

    fun selectRelative(delta: Int) {
        if (entries.isEmpty()) return
        val size = entries.size
        activeIndex = ((activeIndex + delta) % size + size) % size
    }

    /** Kill a session and drop it. Hides the dock when the last one goes. */
    fun closeSession(index: Int) {
        val host = entries.getOrNull(index) ?: return
        host.finish()
        entries.removeAt(index)
        if (entries.isEmpty()) {
            activeIndex = -1
            isOpen = false
        } else {
            activeIndex = activeIndex.coerceAtMost(entries.lastIndex)
        }
    }

    fun closeAll() {
        for (host in entries) host.finish()
        entries.clear()
        activeIndex = -1
        isOpen = false
        created = 0
    }
}
