package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * `IconSize::Medium` — the panel's `Icon::from_path` never asks for a size,
 * so it gets the 16px default (project_panel.rs:6247, icon.rs:61-63, 76).
 */
private val EntryIconSize = 16.dp

/**
 * The slot the icon sits in. The same 16px: Zed's alignment spacer for a row
 * with no icon is `IconSize::default().rems()` (project_panel.rs:6253-6259),
 * so every row's name starts at the same column whatever its icon is.
 */
val EntryIconWidth = 16.dp

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
