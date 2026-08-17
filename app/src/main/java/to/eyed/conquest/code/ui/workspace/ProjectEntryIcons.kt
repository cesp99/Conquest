package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.ui.theme.ZedTheme

/**
 * What a row's mark says the entry is.
 *
 * Coarse on purpose: the useful question in a file tree is "is that a folder,
 * my code, a config file or a picture", not which of forty languages it is.
 */
enum class EntryIcon {
    Folder,

    /** Something you edit: source in any language. */
    Code,

    /** Prose and markup — README, HTML, XML. */
    Markup,

    /** Configuration and data: JSON, TOML, YAML, lockfiles, dotfiles. */
    Data,

    /** Not text: images, fonts, archives, compiled objects. */
    Binary,

    /** Anything else. */
    Plain,
}

/**
 * The mark drawn in front of a row, and what colour it is.
 *
 * Zed distinguishes file types with an icon theme — on the order of two
 * hundred SVGs. Shipping that would cost more APK than the entire Kotlin UI
 * for a panel two hundred pixels wide, and the Compose icon packs big enough
 * to cover the same ground (`material-icons-extended`) are worse on both
 * counts. So the marks are drawn: a filled folder and an outlined page, a
 * dozen lines of `Canvas` with no assets, no resources and nothing for R8 to
 * strip.
 *
 * The type information the icon set would have carried is in the *colour*
 * instead, taken from the theme's ANSI palette — which every Zed theme
 * defines, which is already the palette on screen in the terminal, and which
 * is deliberately not the version-control palette the row's *name* is painted
 * from. A green name means git; a green mark means JSON.
 */
@Immutable
class EntryIconColours(
    private val folder: Color,
    private val code: Color,
    private val markup: Color,
    private val data: Color,
    private val binary: Color,
    private val plain: Color,
) {
    /** Pure `when` over an enum: no map read and no allocation per row. */
    fun colorFor(icon: EntryIcon): Color = when (icon) {
        EntryIcon.Folder -> folder
        EntryIcon.Code -> code
        EntryIcon.Markup -> markup
        EntryIcon.Data -> data
        EntryIcon.Binary -> binary
        EntryIcon.Plain -> plain
    }

    companion object {
        /**
         * Resolve against [theme], once per theme. [fallback] covers a theme
         * that ships none of these keys, which leaves the marks monochrome
         * rather than magenta.
         */
        fun from(theme: ZedTheme, fallback: Color): EntryIconColours = EntryIconColours(
            folder = theme.color("icon.accent", fallback),
            code = theme.color("terminal.ansi.yellow", fallback),
            markup = theme.color("terminal.ansi.blue", fallback),
            data = theme.color("terminal.ansi.green", fallback),
            binary = theme.color("terminal.ansi.magenta", fallback),
            plain = theme.color("icon.muted", fallback),
        )
    }
}

/**
 * Which mark [name] gets. Extension-driven, with the few whole-name cases
 * (`Makefile`, `Dockerfile`, `.gitignore`) that carry no extension at all.
 */
fun entryIconFor(name: String, isDir: Boolean): EntryIcon {
    if (isDir) return EntryIcon.Folder
    val dot = name.lastIndexOf('.')
    // A leading dot is the whole name of a config file, not an extension.
    if (dot <= 0) return byWholeName(name)
    return when (name.substring(dot + 1).lowercase()) {
        "rs", "kt", "kts", "java", "c", "h", "cc", "cpp", "hpp", "cs", "go",
        "py", "rb", "swift", "js", "jsx", "ts", "tsx", "sh", "bash", "zsh",
        "fish", "lua", "php", "pl", "dart", "scala", "clj", "ex", "exs", "hs",
        "ml", "zig", "sql", "vue", "svelte" -> EntryIcon.Code

        "md", "markdown", "txt", "rst", "adoc", "org", "html", "htm", "xml",
        "svg", "tex" -> EntryIcon.Markup

        "json", "toml", "yaml", "yml", "ini", "cfg", "conf", "properties",
        "lock", "env", "gradle", "plist", "csv", "tsv" -> EntryIcon.Data

        "png", "jpg", "jpeg", "gif", "webp", "avif", "ico", "bmp", "ttf",
        "otf", "woff", "woff2", "zip", "tar", "gz", "xz", "zst", "jar", "apk",
        "so", "a", "o", "dylib", "dll", "exe", "bin", "pdf", "mp3", "mp4",
        "wav", "webm" -> EntryIcon.Binary

        else -> EntryIcon.Plain
    }
}

private fun byWholeName(name: String): EntryIcon = when (name.lowercase()) {
    "makefile", "dockerfile", "justfile", "rakefile", "cmakelists.txt" -> EntryIcon.Code
    "license", "licence", "readme", "changelog", "authors", "notice" -> EntryIcon.Markup
    else -> if (name.startsWith(".")) EntryIcon.Data else EntryIcon.Plain
}

/** Width of the mark plus its trailing gap — the panel indents rows past it. */
val EntryIconWidth = 18.dp

private val EntryIconSize = 12.dp

/**
 * Draw one mark. Filled for a folder, outlined for a file: shape carries "is
 * this a directory", which has to survive being read at a glance, in a theme
 * where two of the tints are close, or by someone who doesn't see the
 * difference between them at all.
 */
@Composable
fun EntryIconMark(icon: EntryIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(EntryIconSize)) {
        val width = size.width
        val height = size.height
        if (icon == EntryIcon.Folder) {
            val corner = CornerRadius(width * 0.12f)
            // The tab, then the body over it: two rounded rects read as a
            // folder at this size, where an outline would read as noise.
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, height * 0.16f),
                size = Size(width * 0.46f, height * 0.24f),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, height * 0.28f),
                size = Size(width, height * 0.54f),
                cornerRadius = corner,
            )
        } else {
            drawRoundRect(
                color = color,
                topLeft = Offset(width * 0.2f, height * 0.1f),
                size = Size(width * 0.6f, height * 0.8f),
                cornerRadius = CornerRadius(width * 0.14f),
                style = Stroke(width = width * 0.11f),
            )
        }
    }
}