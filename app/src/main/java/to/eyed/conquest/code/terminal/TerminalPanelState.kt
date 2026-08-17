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
 * the workspace no longer has open is a trap.
 *
 * Held by [TerminalSessions] rather than by a composable, because a running
 * `apt install` must survive anything the UI does to itself, and because the
 * foreground service that keeps those processes alive is driven from the
 * session count here.
 */
class TerminalPanelState(context: Context) {

    /** Application context: these outlive any activity by design. */
    private val context = context.applicationContext

    private val entries = mutableStateListOf<TerminalSessionHost>()

    val sessions: List<TerminalSessionHost> get() = entries

    var activeIndex by mutableStateOf(-1)
        private set

    var isOpen by mutableStateOf(false)
        private set

    val active: TerminalSessionHost? get() = entries.getOrNull(activeIndex)

    private var created = 0

    /** Show the dock, starting a first shell in [cwd] if there is none. */
    fun open(cwd: String) {
        if (entries.isEmpty()) newSession(cwd) else isOpen = true
    }

    fun hide() {
        isOpen = false
    }

    fun toggle(cwd: String) {
        if (isOpen) hide() else open(cwd)
    }

    /** Start another shell and show it. Must be called on the main thread. */
    fun newSession(cwd: String) {
        created += 1
        entries.add(TerminalSessionHost(context, cwd, "shell $created"))
        activeIndex = entries.lastIndex
        isOpen = true
        syncService()
    }

    fun select(index: Int) {
        if (index !in entries.indices) return
        activeIndex = index
        // Looking at a session is hearing its bell, the same rule Zed uses.
        entries[index].clearBell()
    }

    /** Give a session a name of its own; empty hands the chip back to the shell. */
    fun rename(index: Int, title: String) {
        entries.getOrNull(index)?.rename(title)
    }

    fun selectRelative(delta: Int) {
        if (entries.isEmpty()) return
        val size = entries.size
        select(((activeIndex + delta) % size + size) % size)
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
        syncService()
    }

    fun closeAll() {
        for (host in entries) host.finish()
        entries.clear()
        activeIndex = -1
        isOpen = false
        created = 0
        syncService()
    }

    /**
     * Keep the foreground service in step with reality: it exists exactly as
     * long as there is a session for it to protect.
     */
    private fun syncService() {
        TerminalService.sync(context, entries.size)
    }
}

/**
 * The one place terminal sessions live.
 *
 * Not a composable's `remember {}`: a session is a running process tree, and
 * it has to outlive the composition, the activity, and the user closing the
 * dock. Everything above it is still ordinary Compose state, so the UI
 * observes it exactly as before.
 */
object TerminalSessions {
    private var instance: TerminalPanelState? = null

    fun of(context: Context): TerminalPanelState =
        instance ?: TerminalPanelState(context).also { instance = it }
}
