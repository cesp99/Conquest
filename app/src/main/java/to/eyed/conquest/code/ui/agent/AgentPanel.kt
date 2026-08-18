package to.eyed.conquest.code.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import to.eyed.conquest.code.core.AgentCommand
import to.eyed.conquest.code.core.AgentConversation
import to.eyed.conquest.code.core.AgentDefinition
import to.eyed.conquest.code.core.AgentEntry
import to.eyed.conquest.code.core.AgentThread
import to.eyed.conquest.code.core.AgentPhase
import to.eyed.conquest.code.core.AgentSessionState
import to.eyed.conquest.code.core.AgentSessions
import to.eyed.conquest.code.core.Agents
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.FileDiff
import to.eyed.conquest.code.core.PermissionOption
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.ProjectsRoot
import to.eyed.conquest.code.core.ProjectSummary
import to.eyed.conquest.code.core.ToolCallStatus
import to.eyed.conquest.code.core.ToolKind
import to.eyed.conquest.code.core.rememberAgentSession
import to.eyed.conquest.code.terminal.Userland
import to.eyed.conquest.code.ui.theme.BufferFontFamily
import to.eyed.conquest.code.ui.git.DiffLineRow
import to.eyed.conquest.code.ui.preview.MarkdownText
import to.eyed.conquest.code.ui.theme.LocalAppSettings
import to.eyed.conquest.code.ui.workspace.ContextMenu
import to.eyed.conquest.code.ui.workspace.ContextMenuItem
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
 * Agents run inside the Linux userland, so the `play` edition — which has no
 * userland and never will — is not offered one, greyed or otherwise. The
 * same rule the git panel, the clone action and the language-server install
 * already follow.
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
    /** Open the settings screen — where agents are added and edited. */
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val composer = remember { FocusRequester() }

    val agent = AgentSessions.agent
    val activeThread = AgentSessions.active
    val sessionId = AgentSessions.sessionId.takeIf { it >= 0 }
    val snapshot = rememberAgentSession(sessionId)
    val state = snapshot.state
    // The thread list — Zed's history view, toggled from the bar.
    var showThreads by remember { mutableStateOf(false) }

    // Opening a thread is the panel's own business, not a button's: with an
    // agent chosen and a project open there is nothing else the user could
    // mean. It is a no-op once the project has one showing.
    LaunchedEffect(agent, project.id) {
        if (agent != null) AgentSessions.open(project.id, project.rootName)
    }
    LaunchedEffect(focusToken) {
        if (agent != null) runCatching { composer.requestFocus() }
    }
    // Stamp the agent's own title onto the thread, so the history list can
    // name it after it stops being the one showing.
    LaunchedEffect(state.title, activeThread) {
        state.title?.let { title -> activeThread?.title = title }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background", theme.color("surface.background"))),
    ) {
        AgentBar(
            state = state,
            agent = agent,
            thread = activeThread,
            showingThreads = showThreads,
            onChangeAgent = { AgentSessions.reset() },
            onNewThread = {
                // Re-resolved by name first: settings.json may have been
                // edited since this definition was captured, and "+" should
                // launch what the file says *now* — a running thread
                // deliberately keeps the argv it started with, so this is the
                // moment an edit takes effect. An entry edited away entirely
                // keeps the old definition: the user asked for a thread, not
                // the picker.
                agent?.let { current ->
                    settings.agents
                        .firstOrNull { it.name == current.name }
                        ?.let(AgentSessions::choose)
                }
                AgentSessions.newThread(project.id, project.rootName)
                showThreads = false
            },
            onToggleThreads = { showThreads = !showThreads },
        )
        HorizontalDivider(color = theme.color("border"))

        when {
            // No userland at all: the command that opens this is absent in the
            // `play` edition, so this is a backstop rather than a path.
            !isAgentPanelSupported -> Notice(
                "This edition has no Linux userland, so it cannot run an agent.",
            )

            agent == null -> AgentPicker(
                agents = settings.agents,
                onChoose = { AgentSessions.choose(it) },
                onOpenSettings = onOpenSettings,
            )

            // Zed's history: every thread, grouped by project, searchable
            // (agent_ui/src/threads_archive_view.rs).
            showThreads -> ThreadsView(
                currentProject = project,
                onSelect = { thread ->
                    AgentSessions.select(thread)
                    showThreads = false
                },
                onClose = { thread -> AgentSessions.closeThread(thread) },
                onNewThread = {
                    AgentSessions.newThread(project.id, project.rootName)
                    showThreads = false
                },
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
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider(color = theme.color("border"))
                // Zed's bottom row: the mode, the model, whatever else the
                // agent's config options advertise — selectors, driven
                // entirely by what came over the wire.
                ComposerChrome(state)
                Composer(
                    enabled = state.canPrompt,
                    isBusy = state.isBusy,
                    focus = composer,
                    project = project,
                    commands = state.commands,
                    onSend = { text, mentions, onRefused ->
                        AgentSessions.prompt(text, mentions, onRefused)
                    },
                    onStop = AgentSessions::cancelTurn,
                )
            }
        }
    }
}

/** The bar: which thread, what it is doing, and the ways to the others. */
@Composable
private fun AgentBar(
    state: AgentSessionState,
    agent: AgentDefinition?,
    thread: AgentThread?,
    showingThreads: Boolean,
    onChangeAgent: () -> Unit,
    onNewThread: () -> Unit,
    onToggleThreads: () -> Unit,
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
            text = if (showingThreads) {
                "Threads"
            } else {
                state.title ?: thread?.listTitle ?: agent?.name ?: "Agent"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
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
            // Zed's bar: `+` starts a thread, the history icon lists them
            // (agent_panel.rs — the panel toolbar). Words, at this size.
            BarAction("+ New", onNewThread)
            BarAction(if (showingThreads) "Back" else "Threads", onToggleThreads)
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
 * Which agent to talk to — Zed's External Agents list, as the panel's front
 * door (settings_ui/src/pages/external_agents_page.rs:51-58): the agents
 * connected through the Agent Client Protocol, which means exactly what
 * `agent_servers` configures. No agent is named in code and none is offered
 * for installation — ACP is a standard, and which agent to run (and how it
 * gets onto the userland's PATH) is the user's own business.
 */
@Composable
private fun AgentPicker(
    agents: List<AgentDefinition>,
    onChoose: (AgentDefinition) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "EXTERNAL AGENTS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = theme.color("text.muted"),
        )
        Text(
            text = "Agents connected through the Agent Client Protocol, run inside " +
                "${Userland.backend.displayName} against this project.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text.muted"),
        )
        if (agents.isEmpty()) {
            // Zed's dashed empty-state box (external_agents_page.rs:111-125),
            // pointing at the two ways in: the settings section's form, and
            // the settings.json key it writes.
            Text(
                text = "No external agents added yet. Add one in Settings, or under " +
                    "agent_servers in settings.json.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        theme.color("border").copy(alpha = 0.6f),
                        RoundedCornerShape(FieldRadius),
                    )
                    .padding(12.dp),
            )
        }
        for (definition in agents) {
            AgentChoice(definition, onClick = { onChoose(definition) })
        }
        PanelButton("Add Agent", isPrimary = agents.isEmpty(), onClick = onOpenSettings)
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
        // The command line it will run — identification, not instruction.
        Text(
            text = agent.argv.joinToString(" "),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = BufferFontFamily),
            color = theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The thread history — Zed's threads view
 * (agent_ui/src/threads_archive_view.rs): a search field, then every project
 * with its threads under it, "No threads yet" where there are none. Threads
 * live in memory with their engine sessions; a project's group is its live
 * conversations, and other projects list so the shape of the feature is
 * visible — their threads begin when a thread is opened *in* them.
 */
@Composable
private fun ThreadsView(
    currentProject: ProjectSession,
    onSelect: (AgentThread) -> Unit,
    onClose: (AgentThread) -> Unit,
    onNewThread: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(emptyList<ProjectSummary>()) }
    LaunchedEffect(Unit) {
        projects = withContext(Dispatchers.IO) { ProjectsRoot.list(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Zed's "Search threads…" field, over titles and project names.
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background", Color.Transparent))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search threads…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color("text.muted"),
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )

        val names = buildList {
            add(currentProject.rootName)
            for (project in projects) {
                if (project.name != currentProject.rootName) add(project.name)
            }
        }
        for (name in names) {
            val mine = AgentSessions.threads
                .filter { thread ->
                    thread.projectName == name &&
                        (query.isBlank() || thread.listTitle.contains(query, ignoreCase = true))
                }
                .sortedByDescending { it.ordinal }
            if (query.isNotBlank() && mine.isEmpty() && !name.contains(query, ignoreCase = true)) {
                continue
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (mine.isEmpty()) {
                Text(
                    text = "No threads yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            for (thread in mine) {
                ThreadRow(
                    thread = thread,
                    isActive = thread == AgentSessions.active,
                    onSelect = { onSelect(thread) },
                    onClose = { onClose(thread) },
                )
            }
            if (name == currentProject.rootName) {
                // The `+` in the bar, for a finger scrolling the list.
                BarAction("+ New Thread", onNewThread)
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: AgentThread,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    isActive -> theme.color("element.selected", Color.Transparent)
                    hovered -> theme.color("element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = thread.listTitle,
                onClick = onSelect,
            )
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = thread.listTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BarAction("Close", onClose)
    }
}

/**
 * The selectors under the conversation — Zed's bottom row (mode, model,
 * effort), rendered entirely from what the agent advertised: its session
 * modes, and its config options (`session/set_config_option` behind each).
 * Nothing is hardcoded; an agent with none gets no row.
 */
@Composable
private fun ComposerChrome(state: AgentSessionState) {
    val modes = state.modes
    if (modes?.current == null && state.configOptions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        modes?.current?.let { current ->
            SelectorChip(
                label = current.name,
                items = modes.available.map { mode ->
                    ContextMenuItem(mode.name) { AgentSessions.setMode(mode.id) }
                },
            )
        }
        for (option in state.configOptions) {
            when (option.kind) {
                "select" -> SelectorChip(
                    label = option.currentLabel,
                    items = option.values.map { value ->
                        ContextMenuItem(value.name) {
                            // JSONObject.quote, because a value id is wire
                            // data and may carry anything.
                            AgentSessions.setConfigOption(
                                option.id,
                                org.json.JSONObject.quote(value.id),
                            )
                        }
                    },
                )
                "boolean" -> BarAction(
                    "${option.name}: ${if (option.currentBool == true) "On" else "Off"}",
                ) {
                    AgentSessions.setConfigOption(
                        option.id,
                        (option.currentBool != true).toString(),
                    )
                }
            }
        }
    }
}

/** A tappable label that drops the choices under itself. */
@Composable
private fun SelectorChip(label: String, items: List<ContextMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarAction(label) { open = true }
        ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            items = items,
        )
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
                Trouble(state, agent, onAuthenticate)
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

/** What is wrong, said plainly — the fix is the user's, and they know how. */
@Composable
private fun Trouble(
    state: AgentSessionState,
    agent: AgentDefinition?,
    onAuthenticate: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
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

            // The guest could not find the program. Nothing is offered for
            // installation — the panel names the command and where it is
            // configured, and leaves the terminal to the developer.
            Agents.looksLikeMissingProgram(state.error) -> {
                Text(
                    text = "${Userland.backend.displayName} has no " +
                        "\"${agent?.argv?.firstOrNull() ?: "agent"}\". Install it in the " +
                        "terminal, or point this agent's entry in Settings at the right " +
                        "command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted"),
                )
            }
        }
    }
}

/**
 * A leading `/word` being typed — the composer's command token, completed
 * from the agent's advertised commands. Commands are prompt text on the
 * wire; `availableCommands` exists so the client can offer them, which is
 * exactly what Zed's completion provider does with it
 * (agent_ui/src/completion_provider.rs:1026).
 */
private val CommandToken = Regex("^/([\\w-]*)$")

/** A trailing `@path` being typed — the mention token, completed from files. */
private val MentionToken = Regex("(?:^|\\s)@([^\\s@]*)$")

/** The composer. */
@Composable
private fun Composer(
    enabled: Boolean,
    isBusy: Boolean,
    focus: FocusRequester,
    project: ProjectSession,
    /** The agent's slash commands, for the `/` popup. */
    commands: List<AgentCommand>,
    /** Send it, and put it back in the box if the engine would not take it. */
    onSend: (text: String, mentions: List<String>, onRefused: () -> Unit) -> Unit,
    onStop: () -> Unit,
) {
    val theme = LocalZedTheme.current
    // A TextFieldValue, not a String: a completion replaces the text from
    // code, and the caret must land at the end of what was inserted — with a
    // bare String the IME keeps its old offset and the next keystroke lands
    // mid-word, which is exactly what happened on the device.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val text = field.text
    /** Paths completed through the `@` popup, candidates for the send. */
    val mentioned = remember { mutableStateListOf<String>() }
    /** The text a popup was dismissed on, so Esc means no until it changes. */
    var dismissedFor by remember { mutableStateOf<String?>(null) }

    fun replaceText(newText: String) {
        field = androidx.compose.ui.text.input.TextFieldValue(
            text = newText,
            selection = androidx.compose.ui.text.TextRange(newText.length),
        )
    }

    val commandQuery = CommandToken.matchEntire(text)?.groupValues?.get(1)
        ?.takeIf { text != dismissedFor && enabled }
    val commandChoices = if (commandQuery == null) {
        emptyList()
    } else {
        commands.filter { it.name.startsWith(commandQuery, ignoreCase = true) }.take(4)
    }

    val mentionQuery = MentionToken.find(text)?.groupValues?.get(1)
        ?.takeIf { text != dismissedFor && enabled }
    var fileChoices by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(mentionQuery) {
        fileChoices = if (mentionQuery == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { findMentionFiles(project.id, mentionQuery) }
        }
    }

    fun completeCommand(command: AgentCommand) {
        replaceText("/" + command.name + " ")
    }

    fun completeMention(path: String) {
        // The token is at the end and holds the only trailing `@`.
        val at = text.lastIndexOf('@')
        if (at < 0) return
        replaceText(text.substring(0, at) + "@" + path + " ")
        if (path !in mentioned) mentioned.add(path)
    }

    fun send() {
        val message = text.trim()
        if (message.isEmpty() || !enabled) return
        // Only mentions still standing in the text count: one deleted after
        // completion was deleted on purpose.
        val mentions = mentioned.filter { message.contains("@" + it) }
        // Cleared optimistically, because the transcript shows the message the
        // instant the engine takes it and two copies would be worse than a
        // moment's blank. Restored if it turns out nothing took it.
        replaceText("")
        mentioned.clear()
        onSend(message, mentions) {
            replaceText(message)
            mentioned.addAll(mentions)
        }
    }

    if (commandChoices.isNotEmpty() || fileChoices.isNotEmpty()) {
        // The completion strip, just above the box — tap to take one; Tab
        // takes the first, Esc puts the strip away.
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            for (command in commandChoices) {
                SuggestionRow(
                    primary = "/" + command.name,
                    secondary = command.description,
                    onClick = { completeCommand(command) },
                )
            }
            for (path in fileChoices) {
                SuggestionRow(
                    primary = path.substringAfterLast('/'),
                    secondary = path,
                    onClick = { completeMention(path) },
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = field,
            onValueChange = { field = it },
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
                        // Tab takes the first suggestion — the keyboard twin
                        // of tapping it.
                        event.key == Key.Tab &&
                            (commandChoices.isNotEmpty() || fileChoices.isNotEmpty()) -> {
                            commandChoices.firstOrNull()?.let(::completeCommand)
                                ?: fileChoices.firstOrNull()?.let(::completeMention)
                            true
                        }
                        // Escape puts the suggestion strip away first; with no
                        // strip up it stops the turn rather than closing the
                        // panel — the panel is a dock with its own chord, and
                        // while an agent is running "stop" is what Escape
                        // means everywhere else in this app too.
                        event.key == Key.Escape -> {
                            when {
                                commandChoices.isNotEmpty() || fileChoices.isNotEmpty() -> {
                                    dismissedFor = text
                                    true
                                }
                                isBusy -> {
                                    onStop()
                                    true
                                }
                                else -> false
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
                            text = if (enabled) {
                            "Message the agent — @ for files, / for commands"
                        } else {
                            "The agent is not running"
                        },
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

/** One row of the completion strip: the name, and what it is, muted. */
@Composable
private fun SuggestionRow(primary: String, secondary: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("element.hover", Color.Transparent) else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = primary,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = primary,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = BufferFontFamily),
            color = theme.color("text"),
            maxLines = 1,
        )
        Text(
            text = secondary,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The `@` popup's files: [CoreBridge.projectFindFiles], paths only. */
private fun findMentionFiles(projectId: Long, query: String): List<String> = runCatching {
    val matches = JSONArray(CoreBridge.projectFindFiles(projectId, query, 6))
    List(matches.length()) { index ->
        matches.optJSONObject(index)?.optString("path").orEmpty()
    }.filter { it.isNotEmpty() }
}.getOrDefault(emptyList())

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
