package to.eyed.conquest.code.ui.theme

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Every theme the app ships, and the cache that keeps switching between them
 * cheap.
 *
 * Zed's own registry is the model: themes are discovered rather than listed in
 * code, they are keyed by full name across families, and a name that no longer
 * exists resolves to the default for its appearance rather than failing
 * (`crates/theme/src/registry.rs`). Ours discovers by listing `assets/themes/`,
 * so vendoring another family file is the whole change — no Kotlin edit, no
 * enum to extend.
 *
 * The index is names only: listing eleven themes in the picker must not cost
 * eleven palette parses. Palettes are parsed on first use and kept, because the
 * selector's live preview walks the list and a second visit to a theme has to
 * be free.
 */
object ZedThemes {
    /** Zed's own defaults (`settings_content/src/theme.rs:353-354`). */
    const val DEFAULT_DARK = "One Dark"
    const val DEFAULT_LIGHT = "One Light"

    private const val TAG = "ZedThemes"
    private const val DIRECTORY = "themes"

    @Volatile
    private var index: List<ZedTheme.Meta>? = null

    private val parsed = ConcurrentHashMap<String, ZedTheme>()

    /** Which family file each theme name came from, so [get] reads one file. */
    private val sources = ConcurrentHashMap<String, String>()

    /**
     * Every installed theme, dark first and then by name — Zed's own order
     * (`theme_selector.rs:171-176`), which puts the half you are likely to
     * want at the top rather than interleaving the two appearances.
     *
     * **Blocking** on first call: call it off the main thread.
     */
    fun installed(context: Context): List<ZedTheme.Meta> {
        index?.let { return it }
        val found = mutableListOf<ZedTheme.Meta>()
        val files = runCatching { context.assets.list(DIRECTORY) }.getOrNull().orEmpty()
        for (file in files) {
            if (!file.endsWith(".json")) continue
            val asset = "$DIRECTORY/$file"
            val metas = runCatching { ZedTheme.index(read(context, asset)) }
                .onFailure { Log.w(TAG, "$asset is not a theme family", it) }
                .getOrDefault(emptyList())
            for (meta in metas) {
                // First file wins, so a broken duplicate cannot shadow a
                // working theme the user is already on.
                if (sources.putIfAbsent(meta.name, asset) == null) found += meta
            }
        }
        val sorted = found.sortedWith(compareBy({ !it.isDark }, { it.name }))
        index = sorted
        return sorted
    }

    /**
     * The theme called [name], falling back to the default for [preferDark].
     *
     * A miss is expected rather than exceptional: settings.json is
     * hand-editable, and a name that was valid before a family was removed has
     * to resolve to *something* — the alternative is an app that cannot paint
     * its own settings screen to be fixed from.
     *
     * **Blocking** the first time a theme is asked for: it parses a family
     * file. Every call after that is a map lookup.
     */
    fun get(context: Context, name: String, preferDark: Boolean): ZedTheme {
        parsed[name]?.let { return it }
        installed(context)
        load(context, name)?.let { return it }
        val fallback = if (preferDark) DEFAULT_DARK else DEFAULT_LIGHT
        Log.w(TAG, "theme \"$name\" is not installed; using $fallback")
        return parsed[fallback]
            ?: load(context, fallback)
            ?: error("the bundled $fallback theme is missing from the APK")
    }

    /**
     * Parse every installed theme.
     *
     * The selector calls this when it opens: moving the cursor down the list
     * applies each theme in turn, and a parse on the frame that paints it is a
     * stutter the user reads as the app struggling. **Blocking** — it is an
     * `IO` job, not a main-thread one.
     */
    fun warm(context: Context) {
        for (meta in installed(context)) load(context, meta.name)
    }

    private fun load(context: Context, name: String): ZedTheme? {
        val asset = sources[name] ?: return null
        val theme = runCatching { ZedTheme.parse(read(context, asset), name) }
            .onFailure { Log.w(TAG, "theme \"$name\" failed to parse", it) }
            .getOrNull()
            ?: return null
        parsed[name] = theme
        return theme
    }

    private fun read(context: Context, asset: String): String =
        context.assets.open(asset).bufferedReader().use { it.readText() }
}
