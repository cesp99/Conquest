package to.eyed.conquest.code.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.eyed.conquest.code.core.ThemeMode

/**
 * Which theme each appearance uses, and how big the UI font is.
 *
 * Zed keeps all of this in settings.json: `theme` is either a name or
 * `{ "mode": …, "light": …, "dark": … }`, and `ui_font_size` sits beside
 * `buffer_font_size` (`settings_content/src/theme.rs:338-350`,
 * `assets/settings/default.json:71`). Ours cannot yet — the engine's
 * `Settings::theme` is a bare `system`/`light`/`dark` enum and it has no
 * `ui_font_size` at all, so writing either shape into the file would either
 * fail to parse (taking every other setting down with it, since a malformed
 * file falls back to defaults wholesale) or be dropped on the way back through
 * `Engine::settings`.
 *
 * So the *mode* keeps living in settings.json, where it already works and
 * where a user editing the file by hand expects it, and the two theme names
 * and the UI font size live in app-private preferences until the engine's
 * schema catches up. When it does, this file becomes a migration that reads
 * the prefs once and writes them into settings.json — the shapes below are
 * deliberately the ones Zed's JSON already uses, so nothing has to be
 * reinterpreted.
 */
object ThemeStore {
    /**
     * Zed's `ui_font_size` default, which is also gpui's `BASE_REM_SIZE_IN_PX`
     * (`assets/settings/default.json:71`, `ui/src/styles/units.rs:4`). One rem
     * is this many dp, and every chrome dimension is a multiple of it.
     */
    const val DEFAULT_UI_FONT_SIZE = 16f

    /**
     * Zed clamps font sizes to 6..100 (`theme_settings/src/settings.rs:18-19`);
     * the UI font is narrower here, because this one scales the chrome rather
     * than a paragraph. Below 10 the tab bar's 2rem height is a 20dp strip no
     * finger can hit, and above 32 a title bar is half a phone's screen — both
     * ends leave the app unusable from the screen you would have to fix it on.
     */
    private const val MIN_UI_FONT_SIZE = 10f
    private const val MAX_UI_FONT_SIZE = 32f

    /** Zed's step for the Ctrl+= / Ctrl+- chords (`zed.rs:1191-1200`). */
    const val FONT_SIZE_STEP = 1f

    private const val PREFS = "theme"
    private const val KEY_DARK = "dark"
    private const val KEY_LIGHT = "light"
    private const val KEY_UI_FONT_SIZE = "ui_font_size"

    private val _choices = MutableStateFlow(ThemeChoices())

    /** What the theme layer is currently painting with. */
    val choices: StateFlow<ThemeChoices> = _choices.asStateFlow()

    /** Read the stored choices. **Blocking** — call it off the main thread. */
    fun load(context: Context) {
        val prefs = prefs(context)
        _choices.update {
            ThemeChoices(
                dark = prefs.getString(KEY_DARK, null) ?: ZedThemes.DEFAULT_DARK,
                light = prefs.getString(KEY_LIGHT, null) ?: ZedThemes.DEFAULT_LIGHT,
                uiFontSize = prefs.getFloat(KEY_UI_FONT_SIZE, DEFAULT_UI_FONT_SIZE)
                    .coerceIn(MIN_UI_FONT_SIZE, MAX_UI_FONT_SIZE),
            )
        }
    }

    /**
     * Commit [name] as the theme for its own appearance, and stop previewing.
     *
     * Only the matching half moves: picking a light theme leaves the dark one
     * alone, which is what makes "follow the system" keep working after you
     * have chosen both. Zed does the same
     * (`theme_selector.rs:retain_original_opposing_theme`).
     */
    fun choose(context: Context, theme: ZedTheme.Meta) {
        val key = if (theme.isDark) KEY_DARK else KEY_LIGHT
        prefs(context).edit().putString(key, theme.name).apply()
        _choices.update {
            if (theme.isDark) {
                it.copy(dark = theme.name, preview = null)
            } else {
                it.copy(light = theme.name, preview = null)
            }
        }
    }

    /**
     * Show [name] without saving it, or clear the preview when null.
     *
     * This is the whole point of the selector: Zed applies the theme under the
     * cursor to the real window rather than to a swatch, because a theme is
     * judged on the code you were already reading. Nothing here touches disk,
     * so dismissing the picker leaves no trace.
     */
    fun preview(name: String?) {
        _choices.update { if (it.preview == name) it else it.copy(preview = name) }
    }

    /** The UI font size, clamped. Zed's `ui_font_size`, and our rem base. */
    fun setUiFontSize(context: Context, size: Float) {
        val clamped = size.coerceIn(MIN_UI_FONT_SIZE, MAX_UI_FONT_SIZE)
        prefs(context).edit().putFloat(KEY_UI_FONT_SIZE, clamped).apply()
        _choices.update { it.copy(uiFontSize = clamped) }
    }

    /** Ctrl+= / Ctrl+- while the chrome has focus. */
    fun adjustUiFontSize(context: Context, delta: Float) =
        setUiFontSize(context, _choices.value.uiFontSize + delta)

    /** Ctrl+0. */
    fun resetUiFontSize(context: Context) = setUiFontSize(context, DEFAULT_UI_FONT_SIZE)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The theme for each appearance, plus the transient preview.
 *
 * [mode] is not here on purpose — it comes from settings.json through
 * `AppSettings.theme`, and having one source for it is worth more than having
 * all four fields in one place.
 */
data class ThemeChoices(
    val dark: String = ZedThemes.DEFAULT_DARK,
    val light: String = ZedThemes.DEFAULT_LIGHT,
    val uiFontSize: Float = ThemeStore.DEFAULT_UI_FONT_SIZE,
    /** Set while the selector's cursor moves; never written to disk. */
    val preview: String? = null,
) {
    /** The theme to paint with. A preview outranks both stored choices. */
    fun resolve(isDark: Boolean): String = preview ?: if (isDark) dark else light
}

/** Whether this mode means dark right now. */
fun ThemeMode.isDark(systemIsDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemIsDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
