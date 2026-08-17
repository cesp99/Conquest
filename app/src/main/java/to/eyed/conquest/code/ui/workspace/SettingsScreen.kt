package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import to.eyed.conquest.code.core.AppSettings
import to.eyed.conquest.code.ui.editor.SoftWrapMode
import to.eyed.conquest.code.core.GitignoredFiles
import to.eyed.conquest.code.core.ThemeMode
import to.eyed.conquest.code.ui.theme.LocalZedTheme

private val FONT_SIZES = listOf(10f, 12f, 14f, 16f, 18f, 22f, 28f)
private val TAB_SIZES = listOf(2, 4, 8)

/**
 * Settings, v1.
 *
 * Every control here writes one key into the JSONC settings file through the
 * engine, which preserves the file's comments and anything it doesn't
 * recognise — so this screen and a hand-edited file are two views of the same
 * thing rather than competing sources of truth. The file's path is shown
 * because editing it directly is a supported way to work, not a workaround.
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
) {
    val theme = LocalZedTheme.current
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
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    ChoiceRow(
                        label = "Theme",
                        detail = "Light and dark come from Zed's One themes",
                        options = ThemeMode.entries.map { it to it.label() },
                        selected = settings.theme,
                        onSelect = { onSet(AppSettings.KEY_THEME, "\"${it.key}\"") },
                    )
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
                    ChoiceRow(
                        label = "Inline blame",
                        detail = "Who last touched the line the caret is on",
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.inlineBlame,
                        onSelect = { onSet(AppSettings.KEY_INLINE_BLAME, it.toString()) },
                    )
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
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (settingsPath != null) {
                    Text(
                        text = "Edit directly: $settingsPath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
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
