package to.eyed.conquest.code.core

import org.json.JSONObject

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
data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    /** Editor text size in sp. */
    val bufferFontSize: Float = 14f,
    /** Spaces inserted by the Tab key. */
    val tabSize: Int = 4,
    /** Show gitignored entries in the project tree, dimmed. */
    val showIgnored: Boolean = true,
) {
    companion object {
        /** Keys as the engine names them, for [CoreBridge.setSetting]. */
        const val KEY_THEME = "theme"
        const val KEY_FONT_SIZE = "buffer_font_size"
        const val KEY_TAB_SIZE = "tab_size"
        const val KEY_SHOW_IGNORED = "project_panel.show_ignored"

        fun parse(json: String): AppSettings = runCatching {
            val root = JSONObject(json)
            val panel = root.optJSONObject("project_panel")
            AppSettings(
                theme = ThemeMode.fromKey(root.optString("theme", "system")),
                bufferFontSize = root.optDouble("buffer_font_size", 14.0).toFloat(),
                tabSize = root.optInt("tab_size", 4),
                showIgnored = panel?.optBoolean("show_ignored", true) ?: true,
            )
        }.getOrDefault(AppSettings())

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
