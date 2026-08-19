package to.eyed.conquest.code.core

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.eyed.conquest.code.terminal.Userland

/**
 * One conversation with the agent — Zed's thread. The engine session behind
 * it holds the transcript; this is the identity the thread list renders.
 */
class AgentThread internal constructor(
    val sessionId: Long,
    val projectId: Long,
    val projectName: String,
    /** Creation order, newest last — the list shows newest first. */
    val ordinal: Int,
    /**
     * Whether this thread was reopened from the agent's own history rather
     * than started fresh. Worth saying in the list, because an agent that
     * could only `session/resume` gave the conversation back *without* its
     * transcript: the thread is genuinely a continuation of something the
     * panel cannot show.
     */
    val isReopened: Boolean = false,
) {
    /**
     * The agent's own name for the conversation, once it sends one
     * (`SessionInfoUpdate`); stamped by the panel while the thread is
     * showing, so the history list can name it after it stops being active.
     */
    var title by mutableStateOf<String?>(null)
        internal set

    /** What the history list prints. */
    val listTitle: String get() = title ?: "Thread $ordinal"

    /**
     * The unsent message, and the paths @-mentioned in it.
     *
     * Held on the thread rather than inside the composer because the
     * composer is a *branch* of the panel's `when`: opening the threads
     * view, or a `+ New` that flips the panel to "starting", disposes it and
     * would take an unsent prompt with it. Per thread, so each conversation
     * keeps its own draft — which is what Zed does too.
     */
    var draft by mutableStateOf("")

    val draftMentions = mutableStateListOf<String>()
}

/**
 * The agent threads the panel is showing, and which agent they are with.
 *
 * Outside the composition on purpose, the same way
 * [to.eyed.conquest.code.terminal.UserlandInstaller] and `GitClone` are:
 * closing the panel — or the dock reshuffling on a fold — must not end a
 * conversation or kill the agent process behind it. Reopening the panel finds
 * the conversation where it was left.
 *
 * **Threads, plural, within one project** — Zed's shape: the panel shows one
 * thread, `+` starts another, and the history view lists them per project.
 * They share the one agent process the engine holds (a session is a protocol
 * object, not a process). Threads in *different* projects cannot share it —
 * the guest binds the project directory at spawn — so opening a thread in a
 * new project closes every thread of the old one, exactly as the engine
 * replaces the agent underneath.
 */
object AgentSessions {

    private const val TAG = "conquest-agent"

    /**
     * The agent the user picked; null until they do.
     *
     * The definition itself rather than its id, because a configured agent
     * exists only in settings.json — there is no table to look it up in, and
     * a stale id would silently resolve to nothing after an edit.
     */
    var agent by mutableStateOf<AgentDefinition?>(null)
        private set

    /** Every live thread, in creation order. */
    val threads = androidx.compose.runtime.mutableStateListOf<AgentThread>()

    /** The thread the conversation view is showing. */
    var active by mutableStateOf<AgentThread?>(null)
        private set

    private var nextOrdinal = 1

    /** The active thread's session, or -1 — what the panel polls. */
    val sessionId: Long get() = active?.sessionId ?: -1L

    /** Which project [active] belongs to, or -1. */
    val projectId: Long get() = active?.projectId ?: -1L

    /** A session is being asked for; the engine call blocks on a spawn. */
    var isStarting by mutableStateOf(false)
        private set

    /**
     * Why no session could be asked for at all — a caller-side refusal, which
     * is the only thing [CoreBridge.acpStartSession] answers with -1 for.
     * Everything the *agent* got wrong arrives as session state instead.
     */
    var startError by mutableStateOf<String?>(null)
        private set

    /** False in builds with no userland: there is no agent panel there at all. */
    val isSupported: Boolean get() = Userland.backend.isSupported

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Bumped by every start and every close, so a start that finished after it
     * was abandoned cannot publish its session over whatever is current.
     *
     * The same guard `AptInstaller` carries, and needed for the same reason
     * with more teeth: `acpStartSession` **blocks** — it spawns proot and a
     * Node process — and has no suspension point after it, so cancelling the
     * job cannot stop the lines that follow. Without this, pressing *New* or
     * changing agent while a start was in flight left two live engine
     * sessions, one of them unreachable and never closed, and pointed the
     * panel at whichever returned last.
     */
    @Volatile
    private var generation = 0

    /** Remember the choice without starting anything. */
    fun choose(chosen: AgentDefinition) {
        if (agent == chosen) return
        close()
        agent = chosen
    }

    /**
     * Make sure [project] has a thread showing: select its most recent, or
     * start its first. Returns at once; watch [active] and then
     * [rememberAgentSession].
     */
    fun open(project: Long, projectName: String) {
        if (active?.projectId == project) return
        val existing = threads.lastOrNull { it.projectId == project }
        if (existing != null) {
            active = existing
            return
        }
        newThread(project, projectName)
    }

    /**
     * Start another thread for [project] — Zed's `+`. The new thread becomes
     * the one showing; the others stay live in [threads].
     */
    fun newThread(project: Long, projectName: String) {
        startThread(project, projectName, null)
    }

    /**
     * Reopen one of the agent's *own* past conversations as a thread —
     * `session/load` where the agent can replay the history, `session/resume`
     * where it can only continue.
     *
     * Only offered when `agent.capabilities.hasHistory` says both halves are
     * there; the panel gates the view on that.
     */
    fun resumeThread(project: Long, projectName: String, pastSessionId: String) {
        startThread(project, projectName, pastSessionId)
    }

    private fun startThread(project: Long, projectName: String, resume: String?) {
        val agent = agent ?: return
        // A start already in flight is **superseded, never ignored**. It used
        // to `return` here without bumping the generation, so a project
        // switch during the (blocking) spawn published the old project's
        // thread as active: the panel showed project B while every prompt and
        // @-mention resolved against project A's tree, silently. Bumping the
        // generation below is what makes the in-flight start close its own
        // session instead of publishing it.
        job?.cancel()
        // A thread in another project cannot share the agent process — the
        // guest binds the project directory at spawn — so the engine replaces
        // the agent underneath and those sessions die. Close them here too,
        // or the list would show threads whose transcripts are gone.
        if (threads.any { it.projectId != project }) {
            closeThreadsExcept(project)
        }

        isStarting = true
        startError = null
        val spec = agent.toSpecJson()
        val mine = ++generation
        job = scope.launch {
            // Blocking: it spawns proot and the agent behind it. Anything that
            // throws out of the bridge — a JNI failure — must leave the panel
            // saying so rather than stuck on "starting" for ever.
            val id = runCatching {
                if (resume == null) {
                    CoreBridge.acpStartSession(project, spec)
                } else {
                    CoreBridge.acpResumeSession(project, spec, resume)
                }
            }.getOrElse { error ->
                Log.e(TAG, "could not start an agent session", error)
                -1L
            }
            if (generation != mine) {
                // Abandoned while we were spawning: this session belongs to
                // nobody, so close it rather than leak the process behind it.
                if (id >= 0) runCatching { CoreBridge.acpCloseSession(id) }
                return@launch
            }
            if (id < 0) {
                startError = "The agent could not be launched — its command may be misconfigured."
                isStarting = false
                return@launch
            }
            val thread = AgentThread(id, project, projectName, nextOrdinal++, resume != null)
            threads.add(thread)
            active = thread
            isStarting = false
        }
    }

    /** Whether [project] has any thread at all — the panel's empty state. */
    fun hasThreadFor(project: Long): Boolean = threads.any { it.projectId == project }

    /** Show [thread] — the history view's tap. */
    fun select(thread: AgentThread) {
        if (thread in threads) active = thread
    }

    /**
     * End one thread. The engine closes its session and, with the last one,
     * the agent process — through the engine, which takes proot down the
     * careful way.
     */
    fun closeThread(thread: AgentThread) {
        threads.remove(thread)
        if (active == thread) {
            active = threads.lastOrNull { it.projectId == thread.projectId }
        }
        scope.launch { runCatching { CoreBridge.acpCloseSession(thread.sessionId) } }
    }

    private fun closeThreadsExcept(project: Long) {
        val doomed = threads.filter { it.projectId != project }
        threads.removeAll(doomed)
        if (active?.projectId != project) active = null
        for (thread in doomed) {
            scope.launch { runCatching { CoreBridge.acpCloseSession(thread.sessionId) } }
        }
    }

    /** End every thread and the agent process behind them. */
    fun close() {
        val doomed = threads.toList()
        threads.clear()
        active = null
        startError = null
        isStarting = false
        generation++
        job?.cancel()
        job = null
        for (thread in doomed) {
            scope.launch { runCatching { CoreBridge.acpCloseSession(thread.sessionId) } }
        }
    }

    // --- what the panel does, on a scope that outlives it --------------------
    //
    // The panel is a dock: it comes and goes with a chord, a fold, a rotation.
    // A send whose coroutine died with the composition would clear the box and
    // never reach the agent, so these run here instead — and they report a
    // refusal rather than discarding it, because the bridge answers `false`
    // for a session the engine has forgotten and that is exactly the case a
    // user would otherwise see as "my message vanished".

    /**
     * Why the last thing the user did did not happen, or null. Cleared by the
     * next thing they do.
     */
    var lastRefusal by mutableStateOf<String?>(null)
        private set

    /**
     * Say why something the user asked for did not happen, from a caller
     * outside this object — the panel's terminal sign-in, which needs a
     * Context and so cannot live here.
     */
    fun reportRefusal(message: String) {
        lastRefusal = message
    }

    /**
     * Clear the engine's own notice — why the last mode or config change did
     * not take — and ours.
     */
    fun clearNotice() {
        lastRefusal = null
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch { runCatching { CoreBridge.acpClearNotice(session) } }
    }

    /**
     * Send the last prompt again, for a turn that failed on something worth
     * retrying — a rate limit, a provider hiccup.
     *
     * The text is remembered rather than read back out of the transcript,
     * because a refusal truncates the transcript past it and the whole point
     * is to be able to try again after one.
     */
    fun retryLastPrompt() {
        val text = lastPrompt ?: return
        prompt(text.first, text.second) { }
    }

    /** Start a fresh thread on the project the active one is in. */
    fun newThreadHere() {
        val thread = active ?: return
        newThread(thread.projectId, thread.projectName)
    }

    /** The last prompt sent, for [retryLastPrompt]. */
    private var lastPrompt: Pair<String, List<String>>? = null

    fun clearRefusal() {
        lastRefusal = null
    }

    /**
     * Send a prompt; [onRefused] runs when the engine would not take it.
     * [mentions] are the project-relative paths the user @-mentioned; the
     * engine turns each into a resource block beside the text.
     */
    fun prompt(text: String, mentions: List<String>, onRefused: () -> Unit) {
        val session = sessionId
        if (session < 0) {
            onRefused()
            lastRefusal = "There is no agent session to send that to."
            return
        }
        lastRefusal = null
        // Kept for `retryLastPrompt`: a refusal truncates the transcript past
        // the prompt it refused, so the transcript cannot be the source.
        lastPrompt = text to mentions
        val mentionsJson = org.json.JSONArray(mentions).toString()
        scope.launch {
            val sent = runCatching { CoreBridge.acpPrompt(session, text, mentionsJson) }
                .getOrDefault(false)
            if (!sent) {
                onRefused()
                lastRefusal = "The agent did not take that message; the session may have ended."
            }
        }
    }

    /** Change one of the agent's config options — model, effort, a toggle. */
    fun setConfigOption(configId: String, valueJson: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            runCatching { CoreBridge.acpSetConfigOption(session, configId, valueJson) }
        }
    }

    fun cancelTurn() {
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch { runCatching { CoreBridge.acpCancel(session) } }
    }

    fun respondToPermission(toolCallId: String, optionId: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val answered = runCatching {
                CoreBridge.acpRespondPermission(session, toolCallId, optionId)
            }.getOrDefault(false)
            if (!answered) {
                lastRefusal = "That request is no longer waiting for an answer."
            }
        }
    }

    fun authenticate(methodId: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch { runCatching { CoreBridge.acpAuthenticate(session, methodId) } }
    }

    /** Ask the agent to work in a different mode; it confirms, or does not. */
    fun setMode(modeId: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val accepted = runCatching { CoreBridge.acpSetMode(session, modeId) }
                .getOrDefault(false)
            if (!accepted) {
                lastRefusal = "This agent would not change mode."
            }
        }
    }

    /** Forget the chosen agent as well — back to the picker. */
    fun reset() {
        close()
        agent = null
    }
}
