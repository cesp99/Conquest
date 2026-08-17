package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Zed's `IconSize::Small` — a 14px glyph (crates/ui/src/components/icon.rs:75). */
private val EntryIconSize = 14.dp

/**
 * The slot the icon sits in. Wider than the glyph so every row's name starts
 * at the same column whatever its icon is — Zed's `ListItem` start slot does
 * the same (list_item.rs:429).
 */
val EntryIconWidth = 20.dp

/**
 * The icon in front of a row: Zed's own, for the language the file is in.
 *
 * These were hand-drawn marks for a while — a filled folder and an outlined
 * page, with the *type* carried in colour — on the reasoning that Zed's icon
 * theme is a couple of hundred SVGs and Android cannot render an SVG anyway.
 * Both halves of that turned out to be wrong in the way that matters: the set
 * a file tree actually reaches is 79 icons, they convert to VectorDrawables
 * cleanly (`tools/import-zed-icons.py`), and they cost 348 KB — which buys
 * the thing the panel is *for*, telling files apart at a glance.
 *
 * Monochrome, as Zed draws them: the icon says what kind of file it is and
 * the row's colour says what git thinks of it. Two colour languages in one
 * row would be one too many.
 */
@Composable
fun EntryIconMark(
    name: String,
    isDir: Boolean,
    isExpanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val icon = if (isDir) {
        FileIcons.forDirectory(isExpanded)
    } else {
        FileIcons.forFile(name)
    }
    Image(
        painter = painterResource(icon),
        // Named for the reader, not for a screen reader: the row's own text is
        // the file's name, and an icon that repeated it would be read twice.
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(EntryIconSize),
    )
}
