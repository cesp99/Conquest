package to.eyed.conquest.code.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import to.eyed.conquest.code.core.AgentConversation
import to.eyed.conquest.code.core.AgentDefinition
import to.eyed.conquest.code.core.AgentEntry
import to.eyed.conquest.code.core.AgentPhase
import to.eyed.conquest.code.core.AgentRuntime
import to.eyed.conquest.code.core.AgentRuntimeInstaller
import to.eyed.conquest.code.core.AgentSessionState
import to.eyed.conquest.code.core.AgentSessions
import to.eyed.conquest.code.core.Agents
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.AptInstallState
import to.eyed.conquest.code.core.FileDiff
import to.eyed.conquest.code.core.PermissionOption
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.ToolCallStatus
import to.eyed.conquest.code.core.ToolKind
import to.eyed.conquest.code.core.rememberAgentSession
import to.eyed.conquest.code.terminal.Userland
import to.eyed.conquest.code.ui.theme.BufferFontFamily
import to.eyed.conquest.code.ui.git.DiffLineRow
import to.eyed.conquest.code.ui.preview.MarkdownText
import to.eyed.conquest.code.ui.theme.LocalAppSettings
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/**
 * Zed's `agent_panel.default_width` (assets/settings/default.json:1024).
 */
internal val AgentPanelDockWidth = 400.dp

/** `Tab::container_height` = 32px, as every other panel's bar is. */
private val BarHeight = 32.dp

/** Rows are `pl_2p5` / `pr_1` — 10px in, 4px out, as the git panel's are. */
private val RowStartPadding = 10.dp

/** Inputs are `rounded_md` = 6px (search_bar.rs:78). */
private val FieldRadius = 6.dp

/**
 * Six lines of composer and no more, which is what Zed pins its own panel
 * editors to (`MAX_PANEL_EDITOR_LINES = 6`, git_panel.rs:1080) — past that the
 * conversation would lose the panel.
 */
private const val ComposerLines = 6

/**
 * A tool-call diff is a card, not a document: past this many lines it is
 * summarised rather than unrolled, because the conversation scrolls and a
 * generated file would bury it.
 */
private const val MaxDiffLines = 200

/**
 * Whether this build can show an agent panel at all.
 *
 * Agents run on Node inside the Linux userland, so the `play` edition — which
 * has no userland and never will — is not offered one, greyed or otherwise.
 * The same rule the git panel, the clone action and the language-server
 * install already follow.
 */
val isAgentPanelSupported: Boolean
    get() = AgentSessions.isSupported

/**
 * The agent panel — Zed's `crates/agent_ui`, in the shape a phone can hold.
 *
 * A conversation with whatever ACP agent the user configured: their prompt,
 * the agent's reply as markdown, its plan, and a card per tool call carrying
 * the diff of anything it wants to write. Nothing it writes lands without a
 * decision — a permission request stops the turn and puts Allow and Deny in
 * the transcript where the change is, so the diff and the choice are the same
 * screen rather than two.
 *
 * A dock beside the editor on a wide screen and the whole work area on a
 * compact one, which is the split every other panel already makes.
 *
 * Unified diffs only, which is a locked decision rather than a shortcut
 * (DECISIONS.md): side-by-side is wrong on a phone.
 *
 * Touch, keyboard and mouse in the same change: every row and button is a tap
 * target with a hand cursor, `Enter` sends and `Shift+Enter` breaks the line,
 * `Esc` stops a running turn, and the composer takes focus whenever the
 * panel's chord is pressed.
 */
@Composable
fun AgentPanel(
    project: ProjectSession,
    /**
     * Bumped by the workspace whenever the panel's chord is pressed, so
     * pressing it again puts the keyboard back in the composer.
     */
    focusToken: Int,
    onOpenPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val composer = remember { FocusRequester() }

    val agent = AgentSessions.agent
    val sessionId = AgentSessions.sessionId.takeIf { it >= 0 }
    val snapshot = rememberAgentSession(sessionId)
    val state = snapshot.state

    // Opening a session is the panel's own business, not a button's: with an
    // agent chosen and a project open there is nothing else the user could
    // mean. It is a no-op once one is open for this project.
    LaunchedEffect(agent, project.id) {
        if (agent != null) AgentSessions.open(project.id)
    }
    LaunchedEffect(focusToken) {
        if (agent != null) runCatching { composer.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background", theme.color("surface.background"))),
    ) {
        AgentBar(
            state = state,
            agent = agent,
            onChangeAgent = { AgentSessions.reset() },
            onNewSession = {
                // Re-resolved by name first: settings.json may have been
                // edited since this definition was captured, and "New" should
                // launch what the file says *now* — the running conversation
                // deliberately keeps the argv it started with, so this is the
                // moment an edit takes effect. An entry edited away entirely
                // keeps the old definition: the user asked for a session, not
                // the picker.
                agent?.let { current ->
                    Agents.all(settings.agents)
                        .firstOrNull { it.name == current.name }
                        ?.let(AgentSessions::choose)
                }
                AgentSessions.close()
                AgentSessions.open(project.id)
            },
        )
        HorizontalDivider(color = theme.color("border"))

        when {
            // No userland at all: the command that opens this is absent in the
            // `play` edition, so this is a backstop rather than a path.
            !isAgentPanelSupported -> Notice(
                "This edition has no Linux userland, so it cannot run an agent.",
            )

            agent == null -> AgentPicker(
                agents = Agents.all(settings.agents),
                onChoose = { AgentSessions.choose(it) },
            )

            AgentSessions.startError != null -> Notice(
                AgentSessions.startError!!,
                isError = true,
            )

            sessionId == null || AgentSessions.isStarting -> Notice("Starting the agent…")

            else -> {
                Conversation(
                    state = state,
                    conversation = snapshot.conversation,
                    agent = agent,
                    onOpenPath = onOpenPath,
                    onRespond = AgentSessions::respondToPermission,
                    onAuthenticate = AgentSessions::authenticate,
                    onInstallRuntime = {
                        AgentRuntimeInstaller.offer(context, AgentRuntime.NODE)
                    },
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider(color = theme.color("border"))
                Composer(
                    enabled = state.canPrompt,
                    isBusy = state.isBusy,
                    focus = composer,
                    onSend = AgentSessions::prompt,
                    onStop = AgentSessions::cancelTurn,
                )
            }
        }
    }
}

/** The bar: which agent, what it is doing, and the ways out of both. */
@Composable
private fun AgentBar(
    state: AgentSessionState,
    agent: AgentDefinition?,
    onChangeAgent: () -> Unit,
    onNewSession: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .padding(horizontal = RowStartPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.title ?: agent?.name ?: "Agent",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        state.modes?.let { modes ->
            val current = modes.current
            if (current != null) {
                // Tap to take the next mode — Zed has a picker here; a phone's
                // bar has room for the name and one gesture, and with two or
                // three modes cycling is the same journey in fewer taps.
                val next = modes.available
                    .getOrNull((modes.available.indexOf(current) + 1) % modes.available.size)
                BarAction(current.name) {
                    next?.takeIf { it.id != current.id }?.let { AgentSessions.setMode(it.id) }
                }
            }
        }
        state.usage?.let { usage ->
            usage.fraction?.let { fraction ->
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
        if (agent != null) {
            BarAction("New", onNewSession)
            BarAction("Change", onChangeAgent)
        }
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (hovered) theme.color("text") else theme.color("text.muted"),
        maxLines = 1,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * Which agent to talk to.
 *
 * Named, not installed: the command that puts each one on the guest's PATH is
 * shown for the user to run in the terminal. Installing somebody's agent —
 * often tied to their own account — is their call, not the editor's.
 */
@Composable
private fun AgentPicker(agents: List<AgentDefinition>, onChoose: (AgentDefinition) -> Unit) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "CHOOSE AN AGENT",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = theme.color("text.muted"),
        )
        Text(
            text = "The panel runs the agent inside ${Userland.backend.displayName} and " +
                "gives it this project. Install the one you want with npm in the " +
                "terminal first.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text.muted"),
        )
        for (definition in agents) {
            AgentChoice(definition, onClick = { onChoose(definition) })
        }
    }
}

@Composable
private fun AgentChoice(agent: AgentDefinition, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FieldRadius))
            .background(
                when {
                    pressed -> theme.color("element.active", Color.Transparent)
                    hovered -> theme.color("element.hover", Color.Transparent)
                    else -> theme.color("element.background", Color.Transparent)
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = agent.name,
                onClick = onClick,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = agent.name,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
        )
        agent.installCommand?.let { command ->
            Text(
                text = command,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = BufferFontFamily),
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The transcript, its plan, and whatever the session needs said about it. */
@Composable
private fun Conversation(
    state: AgentSessionState,
    conversation: AgentConversation,
    agent: AgentDefinition?,
    onOpenPath: (String) -> Unit,
    onRespond: (toolCall: String, option: String) -> Unit,
    onAuthenticate: (String) -> Unit,
    onInstallRuntime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    // Follow the tail while the agent is talking — but only while the reader
    // is *at* the tail. Scrolling on every version bump (eight a second during
    // a turn) undid any scroll-back within 120 ms, so the transcript could not
    // be read while it was being written.
    val following by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= list.layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(conversation.entries.size, state.version, following) {
        if (conversation.entries.isEmpty() || !following) return@LaunchedEffect
        runCatching {
            val last = conversation.entries.lastIndex
            list.animateScrollToItem(last)
            // `animateScrollToItem` puts the item's *top* at the viewport's
            // top, so a reply taller than the screen would scroll away from
            // the words being written. Go the rest of the way to its end.
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == last }
            val overflow = item?.let {
                it.size - (info.viewportEndOffset - info.viewportStartOffset)
            } ?: 0
            if (overflow > 0) list.animateScrollBy(overflow.toFloat())
        }
    }

    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.plan.isNotEmpty()) {
            item(key = "plan") { PlanCard(state) }
        }
        // Keyed by position rather than by content: two identical messages are
        // perfectly ordinary, and a duplicate key throws inside LazyLayout.
        items(count = conversation.entries.size, key = { "entry:$it" }) { index ->
            when (val entry = conversation.entries[index]) {
                is AgentEntry.User -> UserRow(entry)
                is AgentEntry.Assistant -> AssistantRow(entry, onOpenPath)
                is AgentEntry.ToolCall -> ToolCallCard(entry, onOpenPath, onRespond)
                // A kind this build predates. One quiet line, so the rest of
                // the conversation stays readable and honest about the gap.
                AgentEntry.Unsupported -> Notice(
                    "This version of Conquest Code cannot show that message.",
                )
            }
        }
        if (state.phase == AgentPhase.Unavailable || state.needsAuth) {
            item(key = "trouble") {
                Trouble(state, agent, onAuthenticate, onInstallRuntime)
            }
        }
    }
}

/** What the agent says it is going to do — Zed's plan view, as a list. */
@Composable
private fun PlanCard(state: AgentSessionState) {
    val theme = LocalZedTheme.current
    var expanded by remember { mutableStateOf(true) }
    val done = state.plan.count { it.status == "completed" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("element.background", Color.Transparent))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = "Plan") { expanded = !expanded },
        ) {
            Text(
                text = "PLAN  $done/${state.plan.size}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = theme.color("text.muted"),
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = if (expanded) "hide" else "show",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        if (expanded) {
            for (entry in state.plan) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = when (entry.status) {
                            "completed" -> "✓"
                            "in_progress" -> "▸"
                            else -> "·"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.status) {
                            "completed" -> theme.color("created", theme.color("text.muted"))
                            "in_progress" -> theme.color("text.accent")
                            else -> theme.color("text.muted")
                        },
                    )
                    Text(
                        text = entry.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.status == "completed") {
                            theme.color("text.muted")
                        } else {
                            theme.color("text")
                        },
                    )
                }
            }
        }
    }
}

/** The user's own message: set apart, the way Zed sets its prompt blocks apart. */
@Composable
private fun UserRow(entry: AgentEntry.User) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("element.background", Color.Transparent))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        MarkdownText(entry.markdown)
    }
}

/** The reply, with its reasoning folded away until asked for. */
@Composable
private fun AssistantRow(entry: AgentEntry.Assistant, onOpenPath: (String) -> Unit) {
    val theme = LocalZedTheme.current
    var showThoughts by remember { mutableStateOf(false) }
    val thoughts = entry.thoughts
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (thoughts.isNotEmpty()) {
            Text(
                text = if (showThoughts) "hide thinking" else "thinking…",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = "Thinking") { showThoughts = !showThoughts },
            )
            if (showThoughts) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                ) {
                    MarkdownText(thoughts)
                }
            }
        }
        if (entry.spoken.isNotEmpty()) {
            MarkdownText(entry.spoken, onLink = onOpenPath)
        }
    }
}

/**
 * One tool call: what it is doing, what it produced, and — when it is waiting
 * — the decision, right beside the diff it is asking about.
 */
@Composable
private fun ToolCallCard(
    call: AgentEntry.ToolCall,
    onOpenPath: (String) -> Unit,
    onRespond: (toolCall: String, option: String) -> Unit,
) {
    val theme = LocalZedTheme.current
    val waiting = call.status == ToolCallStatus.WaitingForConfirmation
    // Open by default exactly when a decision depends on it: nobody should
    // have to expand a diff to find out what they are allowing.
    var expanded by remember(call.id) { mutableStateOf(false) }
    val showBody = expanded || waiting

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("element.background", Color.Transparent))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = call.title) { expanded = !expanded },
        ) {
            Text(
                text = glyph(call.kind),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
            )
            Text(
                text = call.title,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(call.status)
        }

        if (showBody) {
            for (content in call.content) {
                when (content) {
                    is to.eyed.conquest.code.core.ToolContent.Markdown ->
                        MarkdownText(content.markdown)

                    is to.eyed.conquest.code.core.ToolContent.Diff ->
                        DiffCard(content.file, onOpenPath)
                }
            }
        }

        if (waiting && call.options.isNotEmpty()) {
            PermissionRow(call.options) { option -> onRespond(call.id, option.id) }
        }
    }
}

/** ACP's tool kinds, as the one glyph each that a 32px row can hold. */
private fun glyph(kind: ToolKind): String = when (kind) {
    ToolKind.Read -> "◇"
    ToolKind.Edit -> "✎"
    ToolKind.Delete -> "✕"
    ToolKind.Move -> "→"
    ToolKind.Search -> "⌕"
    ToolKind.Execute -> "▸"
    ToolKind.Think -> "◌"
    ToolKind.Fetch -> "↓"
    ToolKind.SwitchMode -> "⇄"
    ToolKind.Other -> "•"
}

@Composable
private fun StatusChip(status: ToolCallStatus) {
    val theme = LocalZedTheme.current
    val (label, color) = when (status) {
        ToolCallStatus.Pending -> "pending" to theme.color("text.muted")
        ToolCallStatus.WaitingForConfirmation -> "asks" to theme.color("text.accent")
        ToolCallStatus.InProgress -> "running" to theme.color("text.muted")
        ToolCallStatus.Completed -> "done" to theme.color("created", theme.color("text.muted"))
        ToolCallStatus.Failed -> "failed" to theme.color("error", MaterialTheme.colorScheme.error)
        ToolCallStatus.Rejected -> "denied" to theme.color("text.muted")
        ToolCallStatus.Canceled -> "cancelled" to theme.color("text.muted")
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/**
 * The agent's proposed edit, drawn by the same rows a commit's diff is.
 *
 * Not [to.eyed.conquest.code.ui.git.DiffBody], which is a list of its own and
 * cannot nest inside the conversation's — but the rows, the numbers and the
 * created/deleted colours are its, so an agent's change reads exactly like a
 * git one. Unified only, as decided.
 */
@Composable
private fun DiffCard(file: FileDiff, onOpenPath: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val code = remember(settings.bufferFontSize) {
        TextStyle(
            fontFamily = BufferFontFamily,
            fontSize = settings.bufferFontSize.sp,
            lineHeight = (settings.bufferFontSize * 1.618034f).sp,
        )
    }
    val across = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val lines = remember(file) { file.hunks.flatMap { it.lines } }
    val contentWidth = remember(file, code) {
        val longest = lines.maxOfOrNull { it.text.length + 1 } ?: 0
        (longest * measurer.measure("M", code).size.width).coerceAtLeast(1)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = file.path) { onOpenPath(file.path) }
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = file.path,
                style = code.copy(fontSize = settings.bufferFontSize.sp * 0.9f),
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "+${file.added}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("created", theme.color("text.muted")),
            )
            Text(
                text = "−${file.removed}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("deleted", theme.color("text.muted")),
            )
        }
        when {
            file.isBinary -> Notice("Binary file — nothing to show line by line.")
            lines.isEmpty() -> Notice("Nothing to show.")
            else -> {
                for (line in lines.take(MaxDiffLines)) {
                    DiffLineRow(line, code, across, contentWidth)
                }
                if (lines.size > MaxDiffLines) {
                    Text(
                        text = "… and ${lines.size - MaxDiffLines} more lines. " +
                            "Open the file to see all of it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted"),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * The decision. Allow first, deny beside it, and nothing pre-selected — the
 * agent is asking, and the answer has to be the user's.
 */
@Composable
private fun PermissionRow(options: List<PermissionOption>, onChoose: (PermissionOption) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options.sortedByDescending { it.isAllow }) {
            PanelButton(option.name, isPrimary = option.isAllow) { onChoose(option) }
        }
    }
}

/** What is wrong, and the one thing that would fix it. */
@Composable
private fun Trouble(
    state: AgentSessionState,
    agent: AgentDefinition?,
    onAuthenticate: (String) -> Unit,
    onInstallRuntime: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val install = AgentRuntimeInstaller.state
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = state.error ?: "The agent stopped.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("error", MaterialTheme.colorScheme.error),
        )

        when {
            // Signing in is the agent's own business; we only run the method
            // it advertised.
            state.needsAuth -> {
                val methods = state.agent?.authMethods.orEmpty()
                if (methods.isEmpty()) {
                    Text(
                        text = "Sign in with the agent's own command in the terminal, " +
                            "then start a new session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("text.muted"),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (method in methods) {
                            PanelButton(method.name, isPrimary = true) {
                                onAuthenticate(method.id)
                            }
                        }
                    }
                }
            }

            // "not found" from the guest means one of two things, and the two
            // want different answers: Node is ours to offer, the agent is not.
            AgentRuntime.looksLikeMissingProgram(state.error) -> {
                Text(
                    text = "Neither Node nor ${agent?.name ?: "the agent"} was found in " +
                        "${Userland.backend.displayName}. Node installs from here; the " +
                        "agent installs with npm in the terminal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted"),
                )
                agent?.installCommand?.let { command ->
                    Text(
                        text = command,
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = BufferFontFamily),
                        color = theme.color("text"),
                    )
                }
                when (install) {
                    is AptInstallState.Checking ->
                        Notice("Asking apt what Node would cost…")

                    is AptInstallState.Offered -> {
                        Text(
                            text = install.target.question(install.plan),
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.color("text"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PanelButton("No, don't install it") {
                                AgentRuntimeInstaller.dismiss()
                            }
                            PanelButton("Install", isPrimary = true) {
                                AgentRuntimeInstaller.install(context, install.target)
                            }
                        }
                    }

                    is AptInstallState.Installing -> {
                        Notice(install.step)
                        // apt can run for minutes; the language-server prompt
                        // gives the same state a Cancel and so must this.
                        PanelButton("Cancel", isPrimary = true) {
                            AgentRuntimeInstaller.cancel()
                        }
                    }

                    is AptInstallState.Failed -> {
                        Notice(install.summary, isError = true)
                        install.detail?.let { Notice(it) }
                        // Without this the installer is stuck in `Failed` for
                        // the life of the process — it is a process-global —
                        // and the Install button never comes back.
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PanelButton("Dismiss") { AgentRuntimeInstaller.dismiss() }
                            PanelButton("Try again", isPrimary = true) {
                                AgentRuntimeInstaller.offer(context, AgentRuntime.NODE)
                            }
                        }
                    }

                    is AptInstallState.Finished ->
                        Notice(install.target.installedMessage())

                    is AptInstallState.AlreadyInstalled ->
                        Notice(install.target.alreadyInstalledMessage())

                    AptInstallState.Idle -> PanelButton("Install Node", isPrimary = true) {
                        onInstallRuntime()
                    }
                }
            }
        }
    }
}

/** The composer. */
@Composable
private fun Composer(
    enabled: Boolean,
    isBusy: Boolean,
    focus: FocusRequester,
    /** Send it, and put it back in the box if the engine would not take it. */
    onSend: (String, onRefused: () -> Unit) -> Unit,
    onStop: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var text by remember { mutableStateOf("") }

    fun send() {
        val message = text.trim()
        if (message.isEmpty() || !enabled) return
        // Cleared optimistically, because the transcript shows the message the
        // instant the engine takes it and two copies would be worse than a
        // moment's blank. Restored if it turns out nothing took it.
        text = ""
        onSend(message) { text = message }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            maxLines = ComposerLines,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background", Color.Transparent))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focus)
                // A hardware Enter sends and Shift+Enter breaks the line — the
                // convention every chat has, and the reason it is safe here is
                // that a *soft* keyboard's Enter never arrives as a key event
                // at all (CONVENTIONS § Traps, item 4): it is committed text,
                // so on a phone Enter still inserts a newline and the button
                // is how you send.
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        // Escape stops the turn rather than closing the panel:
                        // the panel is a dock and has its own chord, and while
                        // an agent is running "stop" is what Escape means
                        // everywhere else in this app too.
                        event.key == Key.Escape -> {
                            if (isBusy) {
                                onStop()
                                true
                            } else {
                                false
                            }
                        }

                        event.key != Key.Enter && event.key != Key.NumPadEnter -> false
                        event.isShiftPressed -> false
                        else -> {
                            send()
                            true
                        }
                    }
                },
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = if (enabled) "Ask the agent…" else "The agent is not running",
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color("text.muted"),
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )
        if (isBusy) {
            PanelButton("Stop", isPrimary = true, onClick = onStop)
        } else {
            PanelButton("Send", isPrimary = true, onClick = { send() })
        }
    }
}

/** One line of explanation, `Color::Muted` as Zed's notification bodies are. */
@Composable
private fun Notice(text: String, isError: Boolean = false) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            theme.color("error", MaterialTheme.colorScheme.error)
        } else {
            theme.color("text.muted")
        },
        modifier = Modifier.padding(12.dp),
    )
}

/**
 * A panel button: filled for the answer that goes on, ghost for the way out.
 * Zed's ramps, swapped instantly with no ripple (button_like.rs:298-329).
 */
@Composable
private fun PanelButton(
    label: String,
    isPrimary: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        pressed -> theme.color(
            if (isPrimary) "element.active" else "ghost_element.active",
            Color.Transparent,
        )
        hovered -> theme.color(
            if (isPrimary) "element.hover" else "ghost_element.hover",
            Color.Transparent,
        )
        isPrimary -> theme.color("element.background", Color.Transparent)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isPrimary) {
                theme.color("text.accent", MaterialTheme.colorScheme.primary)
            } else {
                theme.color("text.muted")
            },
            maxLines = 1,
        )
    }
}
