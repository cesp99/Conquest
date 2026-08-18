package to.eyed.conquest.code.core

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.eyed.conquest.code.terminal.Userland

/**
 * The agent session the panel is showing, and which agent it is with.
 *
 * Outside the composition on purpose, the same way
 * [to.eyed.conquest.code.terminal.UserlandInstaller] and `GitClone` are:
 * closing the panel — or the dock reshuffling on a fold — must not end a
 * conversation or kill the agent process behind it. Reopening the panel finds
 * the conversation where it was left.
 *
 * One session at a time, which follows from the engine holding one agent
 * process at a time; opening a project's panel while another project's session
 * is live closes that one first, because the agent's working directory is the
 * project it was started in.
 */
object AgentSessions {

    private const val TAG = "conquest-agent"

    /** The agent the user picked, by [AgentDefinition.id]; null until they do. */
    var agentId by mutableStateOf<String?>(null)
        private set

    /** The live session, or -1. */
    var sessionId by mutableStateOf(-1L)
        private set

    /** Which project [sessionId] belongs to. */
    var projectId by mutableStateOf(-1L)
        private set

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

    val agent: AgentDefinition? get() = Agents.byId(agentId)

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
    fun choose(agent: AgentDefinition) {
        if (agentId == agent.id) return
        close()
        agentId = agent.id
    }

    /**
     * Open a session for [project] with the chosen agent, unless one is
     * already open for it. Returns at once; watch [sessionId] and then
     * [rememberAgentSession].
     */
    fun open(project: Long) {
        val agent = agent ?: return
        if (job?.isActive == true) return
        if (sessionId >= 0 && projectId == project) return
        if (sessionId >= 0) close()

        isStarting = true
        startError = null
        val spec = agent.toSpecJson()
        val mine = ++generation
        job = scope.launch {
            // Blocking: it spawns proot and the agent behind it. Anything that
            // throws out of the bridge — a JNI failure — must leave the panel
            // saying so rather than stuck on "starting" for ever.
            val id = runCatching { CoreBridge.acpStartSession(project, spec) }
                .getOrElse { error ->
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
            projectId = project
            sessionId = id
            isStarting = false
        }
    }

    /**
     * End the session and, with the last one, the agent process — through the
     * engine, which takes proot down the careful way.
     */
    fun close() {
        val doomed = sessionId
        sessionId = -1L
        projectId = -1L
        startError = null
        isStarting = false
        generation++
        job?.cancel()
        job = null
        if (doomed >= 0) {
            scope.launch { runCatching { CoreBridge.acpCloseSession(doomed) } }
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

    fun clearRefusal() {
        lastRefusal = null
    }

    /** Send a prompt; [onRefused] runs when the engine would not take it. */
    fun prompt(text: String, onRefused: () -> Unit) {
        val session = sessionId
        if (session < 0) {
            onRefused()
            lastRefusal = "There is no agent session to send that to."
            return
        }
        lastRefusal = null
        scope.launch {
            val sent = runCatching { CoreBridge.acpPrompt(session, text) }.getOrDefault(false)
            if (!sent) {
                onRefused()
                lastRefusal = "The agent did not take that message; the session may have ended."
            }
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
        agentId = null
    }
}
