package to.eyed.conquest.code.ui.workspace

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap
import to.eyed.conquest.code.R

/**
 * Which icon a file gets, following Zed's own rule
 * (crates/file_icons/src/file_icons.rs).
 *
 * The tables are generated from Zed's `icon_theme.rs` into [ZedFileIcons];
 * this is only the lookup, and it matters that it is *this* lookup rather
 * than "split on the last dot":
 *
 *  1. the whole file name first, so `Dockerfile` and `.gitignore` resolve at
 *     all, and `eslint.config.js` gets eslint's icon rather than JavaScript's;
 *  2. then progressively shorter suffixes, so `auth.module.js` can match
 *     `module.js` before it falls back to `js`.
 *
 * Anything unmatched gets the plain file sheet, which is what Zed shows too.
 */
object FileIcons {

    /** The icon for [name], or the generic file sheet. */
    @Composable
    fun forFile(name: String): Int = drawable(resourceFor(name))

    /** Directories are a folder, open when the row is expanded — as in Zed. */
    @Composable
    fun forDirectory(isExpanded: Boolean): Int =
        drawable(if (isExpanded) "ic_file_folder_open" else "ic_file_folder")

    /** The drawable name for [fileName]; visible for tests. */
    internal fun resourceFor(fileName: String): String {
        val key = iconKey(fileName)
        return ZED_ICON_DRAWABLE[key] ?: DEFAULT
    }

    private fun iconKey(fileName: String): String? {
        var candidate = fileName
        ZED_ICON_BY_STEM[candidate]?.let { return it }
        ZED_ICON_BY_SUFFIX[candidate]?.let { return it }
        // `a.b.c` asks about `b.c`, then `c` — the loop Zed runs, and the
        // reason a dotfile like `.gitignore` is answered by its *whole* name
        // above rather than by the empty stem before its dot.
        while (true) {
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
            if (candidate.isEmpty()) return null
            ZED_ICON_BY_SUFFIX[candidate]?.let { return it }
        }
    }

    @Composable
    @DrawableRes
    private fun drawable(name: String): Int {
        val context = LocalContext.current
        // Resources by name rather than a generated `when`: the table is
        // generated from Zed's and a name it carries that we have no drawable
        // for should degrade to the file sheet, not fail to compile. The name
        // lookup is a linear scan through the resource tables, and this runs
        // for every file-tree row and editor tab on every recomposition — so
        // each name is resolved once and remembered, misses included (a name
        // with no drawable stays missing; asking again won't change that).
        // Resource ids are stable for the life of the process.
        return ids.getOrPut(name) {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) id else R.drawable.ic_file_file
        }
    }

    private val ids = ConcurrentHashMap<String, Int>()

    private const val DEFAULT = "ic_file_file"
}
