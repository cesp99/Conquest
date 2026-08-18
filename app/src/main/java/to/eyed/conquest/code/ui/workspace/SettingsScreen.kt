package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import to.eyed.conquest.code.core.AgentDefinition
import to.eyed.conquest.code.core.AppSettings
import to.eyed.conquest.code.core.DockSide
import to.eyed.conquest.code.core.GitignoredFiles
import to.eyed.conquest.code.core.ThemeMode
import to.eyed.conquest.code.ui.agent.isAgentPanelSupported
import to.eyed.conquest.code.ui.editor.SoftWrapMode
import to.eyed.conquest.code.ui.theme.BufferFontFamily
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ThemeStore

private val FONT_SIZES = listOf(10f, 12f, 14f, 16f, 18f, 22f, 28f)

/**
 * The interface sizes offered, inside `ThemeStore`'s own 10..32 clamp. 16 is
 * Zed's default and therefore the size every chrome number in this app is
 * written against (assets/settings/default.json:71).
 */
private val UI_FONT_SIZES = listOf(12f, 14f, 16f, 18f, 20f, 24f)
private val TAB_SIZES = listOf(2, 4, 8)

/** Zed's `rounded_md`, the corner every input box in this app wears. */
private val FieldRadius = 6.dp

/**
 * What the Add Agent form is holding — Zed's `CustomAgentForm`
 * (settings_ui/src/pages/external_agents_page.rs:329-347), minus the env
 * rows: environment variables stay a settings.json affair here, and an edit
 * carries an entry's existing env through untouched.
 */
private data class AgentForm(
    /** Set when editing — used to remove the old entry on rename, as Zed does. */
    val originalName: String? = null,
    val name: String = "",
    val command: String = "",
    val args: String = "",
    val error: String? = null,
)

/**
 * Settings, in sections.
 *
 * Zed's settings window is pages of sections (settings_ui/src/page_data.rs —
 * General, Appearance, Editor, …); a phone dialog has no room for a page
 * rail, so the pages become sections of one scroll, each under a header.
 * Every control writes one key into the JSONC settings file through the
 * engine, which preserves the file's comments — this screen and a hand-edited
 * file are two views of the same thing, never competing sources of truth.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    settingsPath: String?,
    isFileValid: Boolean,
    /** Set when the last write was refused; see [AppSettings.set]. */
    refusal: String? = null,
    onSet: (keyPath: String, valueJson: String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Open settings.json itself in the editor — the route to every key this
     * screen has no row for. Null where there is nowhere to open a tab, and
     * the path is then shown as plain text.
     */
    onEditFile: (() -> Unit)? = null,
    /**
     * Save an agent from the Add Agent form: remove [AgentForm.originalName]
     * when renaming, then write the entry. Null hides the whole External
     * Agents section (the `play` edition, which has no agent panel at all).
     */
    onSaveAgent: ((originalName: String?, name: String, command: String, args: List<String>) -> Unit)? = null,
    /** Remove one configured agent — Zed's trash button. */
    onRemoveAgent: ((name: String) -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    // The interface size lives in ThemeStore (a preference, not settings.json)
    // because it is the rem every composable here is measured in — it has to
    // be readable without parsing a file on every frame.
    val context = LocalContext.current
    val uiFontSize = ThemeStore.choices.collectAsState().value.uiFontSize
    var agentForm by remember { mutableStateOf<AgentForm?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface),
            modifier = Modifier.widthIn(min = 320.dp, max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                if (!isFileValid) {
                    Text(
                        text = "settings.json could not be parsed — showing defaults. " +
                            "Fix the file, or change something here to rewrite it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }

                // A value the engine refused never reached the file, and the
                // rest of the settings are untouched — but silence here is
                // what let a broken command look like a working one.
                if (refusal != null) {
                    Text(
                        text = refusal,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    // Zed's Appearance page: theme and the UI font
                    // (page_data.rs:507, 874).
                    SectionHeader("Appearance")
                    ChoiceRow(
                        label = "Theme",
                        detail = "Light and dark come from Zed's One themes",
                        options = ThemeMode.entries.map { it to it.label() },
                        selected = settings.theme,
                        onSelect = { onSet(AppSettings.KEY_THEME, "\"${it.key}\"") },
                    )
                    // Zed's rem *is* `ui_font_size`
                    // (theme_settings/src/settings.rs:619), so this row
                    // resizes the whole chrome — rows, bars, gaps, icons —
                    // rather than only its text.
                    ChoiceRow(
                        label = "Interface size",
                        detail = "Zed's ui_font_size — the whole chrome scales with it",
                        options = UI_FONT_SIZES.map { it to it.toInt().toString() },
                        selected = UI_FONT_SIZES.minByOrNull {
                            kotlin.math.abs(it - uiFontSize)
                        } ?: ThemeStore.DEFAULT_UI_FONT_SIZE,
                        onSelect = { ThemeStore.setUiFontSize(context, it) },
                    )

                    SectionDivider()
                    SectionHeader("Editor")
                    ChoiceRow(
                        label = "Editor font size",
                        detail = null,
                        options = FONT_SIZES.map { it to it.toInt().toString() },
                        selected = FONT_SIZES.minByOrNull {
                            kotlin.math.abs(it - settings.bufferFontSize)
                        } ?: 14f,
                        onSelect = { onSet(AppSettings.KEY_FONT_SIZE, it.toInt().toString()) },
                    )
                    ChoiceRow(
                        label = "Tab width",
                        detail = "Spaces inserted by the Tab key",
                        options = TAB_SIZES.map { it to it.toString() },
                        selected = settings.tabSize,
                        onSelect = { onSet(AppSettings.KEY_TAB_SIZE, it.toString()) },
                    )
                    ChoiceRow(
                        label = "Wrap long lines",
                        detail = "Zed's soft_wrap",
                        options = listOf(
                            SoftWrapMode.None to "Off",
                            SoftWrapMode.EditorWidth to "At the editor's width",
                        ),
                        selected = settings.softWrap,
                        onSelect = { onSet(AppSettings.KEY_SOFT_WRAP, "\"${it.key}\"") },
                    )

                    SectionDivider()
                    SectionHeader("Git")
                    ChoiceRow(
                        label = "Inline blame",
                        detail = "Who last touched the line the caret is on",
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.inlineBlame,
                        onSelect = { onSet(AppSettings.KEY_INLINE_BLAME, it.toString()) },
                    )

                    SectionDivider()
                    SectionHeader(
                        "Panels",
                        subtitle = "Which side each panel docks on. Hidden removes its " +
                            "button and its commands.",
                    )
                    // A panel this edition cannot show gets no row: the agent
                    // panel needs the Linux userland, and offering to place
                    // something that will never appear is the sort of dead
                    // setting the "absent, not failing" rule exists to prevent.
                    for (panel in WorkspacePanel.entries.filter {
                        it != WorkspacePanel.Agent || isAgentPanelSupported
                    }) {
                        ChoiceRow(
                            label = panel.title,
                            detail = null,
                            options = DockSide.entries.map { it to it.label },
                            selected = settings.panel(panel.settingsKey).dock,
                            onSelect = {
                                onSet(AppSettings.keyForDock(panel.settingsKey), "\"${it.key}\"")
                            },
                        )
                    }
                    ChoiceRow(
                        label = "Gitignored files",
                        detail = "In the project tree",
                        options = listOf(
                            GitignoredFiles.Show to "Show",
                            GitignoredFiles.Dimmed to "Grey out",
                            GitignoredFiles.Hide to "Hide",
                        ),
                        selected = settings.gitignoredFiles,
                        onSelect = { onSet(AppSettings.KEY_GITIGNORED, "\"${it.key}\"") },
                    )

                    // Zed's External Agents page, as a section: the same
                    // title, the same subtitle, the same list-or-empty-state,
                    // and an Add Agent form with Zed's own fields
                    // (external_agents_page.rs:51-58, 111-125, 497-544).
                    // Absent, not greyed, where there is no userland to run
                    // an agent in.
                    if (onSaveAgent != null) {
                        SectionDivider()
                        SectionHeader(
                            "External Agents",
                            subtitle = "Agents connected through the Agent Client Protocol.",
                        )
                        val form = agentForm
                        if (form == null) {
                            AgentList(
                                agents = settings.agents,
                                onEdit = { agent ->
                                    agentForm = AgentForm(
                                        originalName = agent.name,
                                        name = agent.name,
                                        command = agent.argv.firstOrNull().orEmpty(),
                                        args = agent.argv.drop(1).joinToString(" "),
                                    )
                                },
                                onRemove = onRemoveAgent,
                                onAdd = { agentForm = AgentForm() },
                            )
                        } else {
                            AgentFormFields(
                                form = form,
                                onForm = { agentForm = it },
                                onCancel = { agentForm = null },
                                onSave = {
                                    val saved = validateAgentForm(form, settings.agents)
                                    if (saved == null) {
                                        onSaveAgent(
                                            form.originalName,
                                            form.name.trim(),
                                            form.command.trim(),
                                            // Zed splits arguments on
                                            // whitespace too
                                            // (external_agents_page.rs:825-829).
                                            form.args.split(Regex("\\s+"))
                                                .filter { it.isNotEmpty() },
                                        )
                                        agentForm = null
                                    } else {
                                        agentForm = form.copy(error = saved)
                                    }
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (settingsPath != null) {
                    if (onEditFile != null) {
                        Text(
                            text = "Edit settings.json — every key, env included",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = onEditFile)
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    } else {
                        Text(
                            text = "Edit directly: $settingsPath",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        text = "Close",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Why the form cannot be saved, or null when it can — Zed's own messages and
 * rules (external_agents_page.rs:816-822, 744-759): name and command are
 * required, and a *new* agent may not take an existing name, while editing in
 * place may keep its own.
 */
private fun validateAgentForm(form: AgentForm, agents: List<AgentDefinition>): String? {
    val name = form.name.trim()
    if (name.isEmpty()) return "Agent name is required."
    if (form.command.isBlank()) return "Command is required."
    val collides = agents.any { it.name == name } && name != form.originalName
    if (collides) return "An agent named \"$name\" already exists."
    return null
}

/** The configured agents, or Zed's empty state, and the Add Agent button. */
@Composable
private fun AgentList(
    agents: List<AgentDefinition>,
    onEdit: (AgentDefinition) -> Unit,
    onRemove: ((String) -> Unit)?,
    onAdd: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (agents.isEmpty()) {
            // Zed's dashed empty-state box, in sentence form
            // (external_agents_page.rs:111-125).
            Text(
                text = "No external agents added yet. Add one here, or under " +
                    "agent_servers in settings.json.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
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
        for (agent in agents) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = agent.argv.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = BufferFontFamily),
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Zed's rows carry a configure gear and a trash can
                // (external_agents_page.rs:179-209); words are honest at this
                // size.
                LinkText("Edit") { onEdit(agent) }
                if (onRemove != null) {
                    LinkText("Remove") { onRemove(agent.name) }
                }
            }
        }
        LinkText("+ Add Agent", onClick = onAdd)
    }
}

/** Zed's Add Custom Agent fields: name, command, arguments (:497-544). */
@Composable
private fun AgentFormFields(
    form: AgentForm,
    onForm: (AgentForm) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            label = "Agent name",
            detail = "A unique name; it is the entry's key in agent_servers.",
            value = form.name,
            placeholder = "my-agent",
            onValue = { onForm(form.copy(name = it, error = null)) },
        )
        FormField(
            label = "Command",
            detail = "A program on the userland's PATH, or an absolute path in it.",
            value = form.command,
            placeholder = "/usr/local/bin/agent",
            onValue = { onForm(form.copy(command = it, error = null)) },
        )
        FormField(
            label = "Arguments",
            detail = "Space-separated. Environment variables are set in settings.json.",
            value = form.args,
            placeholder = "--acp",
            onValue = { onForm(form.copy(args = it, error = null)) },
        )
        if (form.error != null) {
            Text(
                text = form.error,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("error", MaterialTheme.colorScheme.error),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            LinkText("Cancel", onClick = onCancel)
            LinkText("Save", onClick = onSave)
        }
    }
}

@Composable
private fun FormField(
    label: String,
    detail: String?,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Zed's input box: 32px min height, 6px corners, 1px border, the
        // editor's background (external_agents_page.rs:558-576).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background"))
                .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder", theme.color("text.muted")),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
                cursorBrush = SolidColor(theme.color("editor.foreground")),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A tappable word with a hand cursor — the dialog's button idiom. */
@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/**
 * A section's header — Zed's section headers over its settings pages
 * (page_data.rs, `concat_sections!`), which is what gives every row a place
 * instead of one flat list where everything has the same priority.
 */
@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = LocalZedTheme.current.color("border").copy(alpha = 0.6f),
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

/** One setting as a row of segmented choices — touch-sized and mouse-friendly. */
@Composable
private fun <T> ChoiceRow(
    label: String,
    detail: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            for ((value, text) in options) {
                Choice(text = text, isSelected = value == selected, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
private fun Choice(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (isSelected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .background(
                if (isSelected) theme.color("element.selected") else theme.color("element.background"),
                RoundedCornerShape(6.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
