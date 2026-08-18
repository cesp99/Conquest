package to.eyed.conquest.code.core

import org.json.JSONObject
import to.eyed.conquest.code.ui.editor.SoftWrapMode

/** How the project tree treats gitignored entries. */
enum class GitignoredFiles(val key: String) {
    /** Listed like any other file. */
    Show("show"),
    /** Listed, but greyed out — what Zed does. */
    Dimmed("dimmed"),
    /** Left out of the tree. */
    Hide("hide");

    companion object {
        fun fromKey(key: String): GitignoredFiles =
            entries.firstOrNull { it.key == key } ?: Dimmed
    }
}

/** How the editor picks light or dark. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromKey(key: String): ThemeMode =
            entries.firstOrNull { it.key == key } ?: System
    }
}

/**
 * The app's resolved settings, mirroring `engine::Settings`.
 *
 * The engine owns the file — it is JSONC, hand-editable, and keeps its
 * comments through edits made here (see `core/crates/engine/src/config.rs`).
 * This is just the read model; every field is wired to something visible.
 */
/** Which side of the workspace a panel lives on — Zed's `dock`. */
enum class DockSide(val key: String, val label: String) {
    Left("left", "Left"),
    Right("right", "Right");

    companion object {
        fun fromKey(key: String?): DockSide = entries.firstOrNull { it.key == key } ?: Left
    }
}

/** Where a panel docks, and how wide it opens the first time. */
data class PanelPlacement(val dock: DockSide, val defaultWidth: Float)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    /** Editor text size in sp. */
    val bufferFontSize: Float = 14f,
    /** Spaces inserted by the Tab key. */
    val tabSize: Int = 4,
    /** What a line longer than the editor does. */
    val softWrap: SoftWrapMode = SoftWrapMode.None,
    /** Zed's `git.inline_blame.enabled`, whose default is on. */
    val inlineBlame: Boolean = true,
    /**
     * Where each panel docks and how wide it opens — Zed's `dock` and
     * `default_width`, per panel.
     */
    val panels: Map<String, PanelPlacement> = DEFAULT_PANELS,
    /** How gitignored entries appear in the project tree. */
    val gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
    /**
     * ACP agents configured by hand — Zed's `agent_servers`, in name order.
     *
     * This is what makes the panel's promise of *any* ACP agent true rather
     * than "the two we happen to name": the command runs inside the Linux
     * userland, so anything on Debian's PATH that speaks the protocol counts.
     */
    val agents: List<AgentDefinition> = emptyList(),
) {
    /**
     * Where the panel keyed [settingsKey] sits, falling back to the shipped
     * default. Keyed by string rather than by the UI's enum: settings are the
     * lower layer and cannot see it.
     */
    fun panel(settingsKey: String): PanelPlacement =
        panels[settingsKey] ?: DEFAULT_PANELS.getValue(settingsKey)

    companion object {
        /** Keys as the engine names them, for [CoreBridge.setSetting]. */
        const val KEY_THEME = "theme"
        const val KEY_FONT_SIZE = "buffer_font_size"
        const val KEY_TAB_SIZE = "tab_size"
        const val KEY_SOFT_WRAP = "soft_wrap"
        const val KEY_INLINE_BLAME = "git.inline_blame.enabled"

        /** `project_panel` → `project_panel.dock`. */
        fun keyForDock(panel: String): String = "$panel.dock"

        /**
         * What each panel does when settings.json says nothing. The project
         * tree on the left is *this app's* default rather than Zed's current
         * one — Zed moved its tree to the right — because every file manager
         * on this platform puts it left and it is one line to change.
         */
        val DEFAULT_PANELS: Map<String, PanelPlacement> = mapOf(
            "project_panel" to PanelPlacement(DockSide.Left, 240f),
            "git_panel" to PanelPlacement(DockSide.Right, 360f),
            "project_search" to PanelPlacement(DockSide.Right, 360f),
            "preview" to PanelPlacement(DockSide.Right, 400f),
            "agent_panel" to PanelPlacement(DockSide.Right, 400f),
        )
        const val KEY_GITIGNORED = "project_panel.gitignored_files"

        fun parse(json: String): AppSettings = runCatching {
            val root = JSONObject(json)
            val panel = root.optJSONObject("project_panel")
            AppSettings(
                theme = ThemeMode.fromKey(root.optString("theme", "system")),
                bufferFontSize = root.optDouble("buffer_font_size", 14.0).toFloat(),
                tabSize = root.optInt("tab_size", 4),
                softWrap = SoftWrapMode.fromKey(root.optString("soft_wrap", "none")),
                inlineBlame = root.optJSONObject("git")
                    ?.optJSONObject("inline_blame")
                    ?.optBoolean("enabled", true) ?: true,
                panels = DEFAULT_PANELS.mapValues { (key, fallback) ->
                    val panel = root.optJSONObject(key) ?: return@mapValues fallback
                    PanelPlacement(
                        dock = DockSide.fromKey(panel.optString("dock", fallback.dock.key)),
                        defaultWidth = panel.optDouble(
                            "default_width",
                            fallback.defaultWidth.toDouble(),
                        ).toFloat().coerceIn(120f, 900f),
                    )
                },
                gitignoredFiles = GitignoredFiles.fromKey(
                    panel?.optString("gitignored_files", "dimmed") ?: "dimmed"
                ),
                agents = parseAgents(root.optJSONObject("agent_servers")),
            )
        }.getOrDefault(AppSettings())

        /**
         * `agent_servers` as the panel's own list.
         *
         * An agent with no command is dropped rather than offered: it would
         * be a row that can only fail, and a half-written settings entry is
         * an ordinary state of a file people edit by hand.
         *
         * Sorted by name here, explicitly: the engine sends the map sorted
         * (its `BTreeMap`), but `JSONObject` promises nothing about key
         * order, and a picker that reshuffles between launches would make
         * muscle memory impossible.
         */
        private fun parseAgents(json: JSONObject?): List<AgentDefinition> {
            if (json == null) return emptyList()
            return json.keys().asSequence().mapNotNull { name ->
                val entry = json.optJSONObject(name) ?: return@mapNotNull null
                val command = entry.optString("command").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val args = entry.optJSONArray("args")
                val env = entry.optJSONObject("env")
                AgentDefinition(
                    id = "custom:$name",
                    name = name,
                    argv = listOf(command) + List(args?.length() ?: 0) {
                        args!!.optString(it)
                    },
                    env = env?.keys()?.asSequence()?.associateWith { key ->
                        env.optString(key)
                    }.orEmpty(),
                    // Configured by hand, so there is nothing for the panel to
                    // tell the user to install.
                    npmPackage = null,
                )
            }.sortedBy { it.name }.toList()
        }

        /** Read the current settings. **Blocking** — call it off the main thread. */
        fun load(): AppSettings = parse(CoreBridge.settings())

        /**
         * Write one setting and return the new resolved settings, or null if
         * the write failed. **Blocking** — call it off the main thread.
         */
        fun set(keyPath: String, valueJson: String): AppSettings? =
            CoreBridge.setSetting(keyPath, valueJson)?.let(::parse)
    }
}
