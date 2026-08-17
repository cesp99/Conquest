package to.eyed.conquest.code.ui.preview

/**
 * What the toolbar's eye offers for the file that is open.
 *
 * Zed decides this the same way and in the same place: its quick action bar
 * asks the active item what it is and shows one Eye button for whichever
 * preview applies (`quick_action_bar/preview.rs:24-40`). A file with no
 * preview gets no button at all rather than a disabled one.
 */
enum class PreviewKind {
    Markdown,
    Svg;

    companion object {
        /** The preview [path] has, or null when it has none. */
        fun of(path: String): PreviewKind? {
            val name = path.substringAfterLast('/')
            return when {
                MARKDOWN_SUFFIXES.any { name.endsWith(it, ignoreCase = true) } -> Markdown
                name.endsWith(".svg", ignoreCase = true) -> Svg
                else -> null
            }
        }
    }
}

/** Files the Markdown preview will render. */
internal val MARKDOWN_SUFFIXES = listOf(".md", ".markdown", ".mdown", ".mkd")
