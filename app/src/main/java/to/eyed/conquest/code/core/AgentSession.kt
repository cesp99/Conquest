package to.eyed.conquest.code.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent panel's side of [CoreBridge]'s ACP contract.
 *
 * Everything here is parsing and merging — no drawing, no engine calls beyond
 * the bridge — so the shapes that actually go wrong can be tested on the host.
 * The engine owns the conversation; this owns the *reading* of it, which on a
 * long transcript is the part that has to stay cheap: the engine hands back
 * only the rows whose revision moved, and [AgentConversation.apply] merges
 * them in place.
 *
 * `org.json` trap, and the reason every optional string here goes through
 * [stringOrNull]: on Android `optString(name, null)` returns the **string**
 * `"null"` for a JSON null (agent-docs/CONVENTIONS.md § Traps). Every nullable
 * field below is a real null in the engine's JSON — an agent that has not
 * named itself, a session with no error — so getting this wrong would put the
 * word "null" on screen.
 */
private fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/** Where a session is in its life; `phase` in the engine's state JSON. */
enum class AgentPhase {
    /** Spawning, initializing, or asking for a session. Cannot be prompted. */
    Starting,

    /** Waiting for a prompt. */
    Ready,

    /** A turn is in flight. */
    Running,

    /** Over — the agent exited, refused, or wants signing in to. */
    Unavailable;

    internal companion object {
        fun parse(text: String?): AgentPhase = when (text) {
            "ready" -> Ready
            "running" -> Running
            "unavailable" -> Unavailable
            else -> Starting
        }
    }
}

/** A tool call's state; `status` on a `tool_call` entry. */
enum class ToolCallStatus {
    Pending,
    WaitingForConfirmation,
    InProgress,
    Completed,
    Failed,
    Rejected,
    Canceled;

    /** Whether it is still going somewhere, for a spinner. */
    val isMoving: Boolean get() = this == Pending || this == InProgress

    internal companion object {
        fun parse(text: String?): ToolCallStatus = when (text) {
            "waiting_for_confirmation" -> WaitingForConfirmation
            "in_progress" -> InProgress
            "completed" -> Completed
            "failed" -> Failed
            "rejected" -> Rejected
            "canceled" -> Canceled
            else -> Pending
        }
    }
}

/**
 * What kind of thing a tool call is doing — an icon, not the row's kind.
 *
 * ACP's own list (`ToolKind`), which is what Zed picks its icons from too.
 * Anything unrecognised is [Other], because the enum is open on the wire.
 */
enum class ToolKind {
    Read, Edit, Delete, Move, Search, Execute, Think, Fetch, SwitchMode, Other;

    internal companion object {
        fun parse(text: String?): ToolKind = when (text) {
            "read" -> Read
            "edit" -> Edit
            "delete" -> Delete
            "move" -> Move
            "search" -> Search
            "execute" -> Execute
            "think" -> Think
            "fetch" -> Fetch
            "switch_mode" -> SwitchMode
            else -> Other
        }
    }
}

/** One choice at a permission prompt. */
data class PermissionOption(
    val id: String,
    val name: String,
    /** `allow_once`, `allow_always`, `reject_once`, `reject_always`. */
    val kind: String,
) {
    val isAllow: Boolean get() = kind.startsWith("allow")

    internal companion object {
        fun parse(json: JSONObject) = PermissionOption(
            // ACP's own wire shape is camelCase, unlike everything else the
            // engine sends: these objects are the protocol's, passed through.
            id = json.optString("optionId"),
            name = json.optString("name"),
            kind = json.optString("kind"),
        )
    }
}

/** A file the agent says it is working in. */
data class AgentLocation(val path: String, val line: Int?)

/** One piece of a tool call's output. */
sealed interface ToolContent {
    data class Markdown(val markdown: String) : ToolContent

    /**
     * A file edit, already in the shape the git diff view draws — so the
     * panel's expandable diff is the same renderer a commit gets, rather than
     * a second one that drifts from it.
     */
    data class Diff(val file: FileDiff) : ToolContent
}

/** One chunk of the agent's reply; thoughts fold away. */
data class AssistantChunk(val thought: Boolean, val markdown: String)

/** One row of the conversation. */
sealed interface AgentEntry {
    data class User(val markdown: String) : AgentEntry

    data class Assistant(val chunks: List<AssistantChunk>) : AgentEntry {
        /** The reply proper, without the reasoning. */
        val spoken: String get() = chunks.filter { !it.thought }.joinToString("") { it.markdown }

        val thoughts: String get() = chunks.filter { it.thought }.joinToString("") { it.markdown }
    }

    data class ToolCall(
        val id: String,
        val title: String,
        val kind: ToolKind,
        val status: ToolCallStatus,
        /** Non-empty only while [status] is [ToolCallStatus.WaitingForConfirmation]. */
        val options: List<PermissionOption>,
        val content: List<ToolContent>,
        val locations: List<AgentLocation>,
    ) : AgentEntry {
        val diffs: List<FileDiff> get() = content.filterIsInstance<ToolContent.Diff>().map { it.file }
    }

    /**
     * A row this build does not know how to draw — a kind added to the engine
     * after this app was built.
     *
     * It is a *row*, not a gap, and that is the point. Skipping it left a hole
     * in the merge, a hole means "we are out of step with the engine", and
     * being out of step asks the poller to re-read from the start — which
     * returns the same unknown row, holes again, and re-reads for ever with an
     * empty transcript and no error anywhere. Keeping the row makes an unknown
     * kind exactly what it should be: one line nobody can render, and the rest
     * of the conversation intact.
     */
    data object Unsupported : AgentEntry

    companion object {
        /** Never null: an unknown row is [Unsupported], never a hole. */
        internal fun parse(json: JSONObject): AgentEntry = when (json.optString("kind")) {
            "user" -> User(json.optString("markdown"))

            "assistant" -> {
                val chunks = json.optJSONArray("chunks") ?: JSONArray()
                Assistant(
                    List(chunks.length()) { index ->
                        val chunk = chunks.getJSONObject(index)
                        AssistantChunk(
                            thought = chunk.optBoolean("thought"),
                            markdown = chunk.optString("markdown"),
                        )
                    }
                )
            }

            "tool_call" -> {
                val options = json.optJSONArray("options") ?: JSONArray()
                val content = json.optJSONArray("content") ?: JSONArray()
                val locations = json.optJSONArray("locations") ?: JSONArray()
                ToolCall(
                    id = json.optString("id"),
                    title = json.optString("title"),
                    // `tool_kind`, not `kind` — `kind` is the row's own tag,
                    // and the two collided once already.
                    kind = ToolKind.parse(json.optString("tool_kind")),
                    status = ToolCallStatus.parse(json.optString("status")),
                    options = List(options.length()) {
                        PermissionOption.parse(options.getJSONObject(it))
                    },
                    content = (0 until content.length()).mapNotNull { index ->
                        val item = content.getJSONObject(index)
                        when (item.optString("type")) {
                            "markdown" -> ToolContent.Markdown(item.optString("markdown"))
                            "diff" -> item.optJSONObject("diff")
                                ?.let { ToolContent.Diff(FileDiff.parse(it)) }

                            else -> null
                        }
                    },
                    locations = List(locations.length()) { index ->
                        val location = locations.getJSONObject(index)
                        AgentLocation(
                            path = location.optString("path"),
                            line = if (location.isNull("line")) null else location.optInt("line"),
                        )
                    },
                )
            }

            else -> Unsupported
        }
    }
}

/** Context-window usage, when the agent reports it. */
data class AgentUsage(val used: Long, val size: Long) {
    /** 0..1, or null when the agent gave a nonsensical window. */
    val fraction: Float? get() = if (size > 0) (used.toFloat() / size).coerceIn(0f, 1f) else null
}

/** One entry of the agent's plan. */
data class AgentPlanEntry(
    val content: String,
    /** `high`, `medium`, `low`. */
    val priority: String,
    /** `pending`, `in_progress`, `completed`. */
    val status: String,
)

/** A mode the agent can work in — Claude Code's "Always Ask", "Accept Edits". */
data class AgentMode(val id: String, val name: String, val description: String?)

/** The modes, and which one is current. */
data class AgentModes(val currentId: String, val available: List<AgentMode>) {
    val current: AgentMode? get() = available.firstOrNull { it.id == currentId }
}

/** A way to sign in, as the agent advertised it. */
data class AgentAuthMethod(val id: String, val name: String, val description: String?)

/** What the agent said about itself when it initialized. */
data class AgentInfo(
    /** The name we launched it under. */
    val name: String?,
    /** What it calls itself, when it says. */
    val agentName: String?,
    val agentVersion: String?,
    val authMethods: List<AgentAuthMethod>,
    /** It has not answered `initialize` yet. */
    val starting: Boolean,
    /** It will not start, and this is why. */
    val error: String?,
)

/** Everything about a session except its rows. */
data class AgentSessionState(
    val version: Long,
    val phase: AgentPhase,
    /** The sentence to show when something went wrong. */
    val error: String?,
    /** [CoreBridge.acpAuthenticate] with one of [AgentInfo.authMethods] is the way on. */
    val needsAuth: Boolean,
    val title: String?,
    /** How the last turn ended: `end_turn`, `cancelled`, `refusal`, … */
    val stopReason: String?,
    val entryCount: Int,
    val plan: List<AgentPlanEntry>,
    val usage: AgentUsage?,
    val modes: AgentModes?,
    val agent: AgentInfo?,
) {
    val isBusy: Boolean get() = phase == AgentPhase.Running

    /** Whether a prompt would be accepted at all. */
    val canPrompt: Boolean get() = phase != AgentPhase.Unavailable

    companion object {
        /** Before the first read, and for a session the engine has forgotten. */
        val NONE = AgentSessionState(
            version = 0,
            phase = AgentPhase.Starting,
            error = null,
            needsAuth = false,
            title = null,
            stopReason = null,
            entryCount = 0,
            plan = emptyList(),
            usage = null,
            modes = null,
            agent = null,
        )

        /** Parses [CoreBridge.acpSessionState]; [NONE] for `"null"` or rubbish. */
        fun parse(text: String): AgentSessionState = runCatching {
            val root = JSONObject(text)
            val plan = root.optJSONArray("plan") ?: JSONArray()
            val usage = root.optJSONObject("usage")
            val modes = root.optJSONObject("modes")
            val agent = root.optJSONObject("agent")
            AgentSessionState(
                version = root.optLong("version"),
                phase = AgentPhase.parse(root.optString("phase")),
                error = root.stringOrNull("error"),
                needsAuth = root.optBoolean("needs_auth"),
                title = root.stringOrNull("title"),
                stopReason = root.stringOrNull("stop_reason"),
                entryCount = root.optInt("entry_count"),
                plan = List(plan.length()) { index ->
                    val entry = plan.getJSONObject(index)
                    AgentPlanEntry(
                        content = entry.optString("content"),
                        priority = entry.optString("priority"),
                        status = entry.optString("status"),
                    )
                },
                usage = usage?.let { AgentUsage(it.optLong("used"), it.optLong("size")) },
                modes = modes?.let {
                    val available = it.optJSONArray("availableModes") ?: JSONArray()
                    AgentModes(
                        currentId = it.optString("currentModeId"),
                        available = List(available.length()) { index ->
                            val mode = available.getJSONObject(index)
                            AgentMode(
                                id = mode.optString("id"),
                                name = mode.optString("name"),
                                description = mode.stringOrNull("description"),
                            )
                        },
                    )
                },
                agent = agent?.let {
                    val methods = it.optJSONArray("auth_methods") ?: JSONArray()
                    AgentInfo(
                        name = it.stringOrNull("name"),
                        agentName = it.stringOrNull("agent_name"),
                        agentVersion = it.stringOrNull("agent_version"),
                        authMethods = List(methods.length()) { index ->
                            val method = methods.getJSONObject(index)
                            AgentAuthMethod(
                                // ACP's shape again: camelCase, and the id may
                                // sit under either name depending on variant.
                                id = method.stringOrNull("methodId")
                                    ?: method.optString("id"),
                                name = method.optString("name"),
                                description = method.stringOrNull("description"),
                            )
                        },
                        starting = it.optBoolean("starting"),
                        error = it.stringOrNull("error"),
                    )
                },
            )
        }.getOrDefault(NONE)
    }
}

/**
 * The transcript, merged from deltas.
 *
 * The engine stamps every row with the revision that last touched it and hands
 * back only the ones newer than what the caller quotes, so a conversation that
 * is thousands of rows long costs one row per change rather than the whole
 * thing per poll. Immutable and copied on apply, so Compose sees a new value.
 */
data class AgentConversation(
    val entries: List<AgentEntry> = emptyList(),
    /** The revision to quote on the next read. */
    val revision: Long = 0,
) {
    /**
     * Merge one [CoreBridge.acpEntriesSince] payload.
     *
     * Rows arrive with the index they sit at. A `total` smaller than what we
     * hold means rows were *removed* — a refusal truncates the transcript back
     * past the prompt it refused — and the only honest answer is to drop
     * everything and re-read from zero, which is what returning a conversation
     * at revision 0 asks for.
     */
    fun apply(text: String): AgentConversation = runCatching {
        val root = JSONObject(text)
        val total = root.optInt("total")
        if (total < entries.size) return AgentConversation()

        val incoming = root.optJSONArray("entries") ?: JSONArray()
        // Grown to `total` with holes first: only *changed* rows come back, so
        // the untouched ones in between are already ours and a delta may name
        // an index past the end.
        val merged: MutableList<AgentEntry?> = entries.toMutableList()
        while (merged.size < total) merged.add(null)
        for (index in 0 until incoming.length()) {
            val json = incoming.getJSONObject(index)
            val at = json.optInt("index", -1)
            if (at in merged.indices) merged[at] = AgentEntry.parse(json)
        }
        // A hole left over means we are out of step with the engine — a row
        // exists that no delta has ever described. Start again rather than
        // draw a blank message where a real one belongs.
        if (merged.any { it == null }) return AgentConversation()
        AgentConversation(merged.filterNotNull(), root.optLong("revision", revision))
    }.getOrDefault(this)
}

/** Both halves of what the panel draws, polled together. */
data class AgentSessionSnapshot(
    val state: AgentSessionState = AgentSessionState.NONE,
    val conversation: AgentConversation = AgentConversation(),
)

/** How often the panel looks for news while a session is open. */
private const val POLL_MS = 120L

/**
 * Poll one session, reading only when its counter moves.
 *
 * The house pattern, and deliberately not `produceState`: the "seen" value
 * lives beside the loop rather than in an effect's keys, because a counter
 * that starts at zero and is corrected a frame later makes a keyed effect run
 * twice — which for a guest command means two processes
 * (agent-docs/CONVENTIONS.md § Traps, item 3).
 *
 * 120 ms rather than the 250 the other panels use: this is streaming text, and
 * a reply that arrives in quarter-second steps reads as stuttering rather than
 * as typing.
 */
@Composable
fun rememberAgentSession(sessionId: Long?): AgentSessionSnapshot {
    var snapshot by remember(sessionId) { mutableStateOf(AgentSessionSnapshot()) }
    LaunchedEffect(sessionId) {
        if (sessionId == null || sessionId < 0) return@LaunchedEffect
        var seen = -1L
        var conversation = AgentConversation()
        while (true) {
            val fresh = withContext(Dispatchers.Default) {
                val version = CoreBridge.acpSessionVersion(sessionId)
                if (version == seen) {
                    null
                } else {
                    val state = AgentSessionState.parse(CoreBridge.acpSessionState(sessionId))
                    val merged = conversation.apply(
                        CoreBridge.acpEntriesSince(sessionId, conversation.revision)
                    )
                    version to AgentSessionSnapshot(state, merged)
                }
            }
            if (fresh != null) {
                val (version, next) = fresh
                conversation = next.conversation
                snapshot = next
                // A truncation puts us back to nothing and asks to be re-read
                // from zero. Recording the version we just saw would mean
                // waiting for the *next* change to do it, leaving the panel
                // blank in between — so leave `seen` behind instead.
                seen = if (next.conversation.revision == 0L && next.state.entryCount > 0) {
                    -1L
                } else {
                    version
                }
            }
            delay(POLL_MS)
        }
    }
    return snapshot
}
