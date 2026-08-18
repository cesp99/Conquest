package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.conquest.code.R
import to.eyed.conquest.code.ui.editor.Diagnostic
import to.eyed.conquest.code.ui.editor.DiagnosticSummary
import to.eyed.conquest.code.ui.editor.LspServer
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** One panel's button: which panel, whether its dock is showing it, and the tap. */
data class PanelButton(
    val panel: WorkspacePanel,
    val isOpen: Boolean,
    val onClick: () -> Unit,
)

/**
 * Every item is Zed's IconButton at `ButtonSize::Default`: a 22px box
 * (button_like.rs:469) with `rounded_sm` corners (button_like.rs:527) around
 * an `IconSize::Small` 14px glyph (status_bar.rs:187 spec; dock.rs:1398-1400).
 * Two of them plus the bar's 4px `p(Base04)` is the whole 30px height.
 */
private val ItemBox = 22.dp
private val ItemIconSize = 14.dp

/**
 * Zed-style status bar: **state, not actions**.
 *
 * Zed splits these deliberately — the title bar holds commands, the status bar
 * reports where you are and which panels are up. Everything that *does*
 * something to a project or a file lives in the title-bar menu, which also
 * keeps it reachable when the soft keyboard covers the bottom of the screen.
 *
 * The panel buttons follow their docks, exactly as Zed's do: the left dock's
 * buttons at the left end of the bar, the right dock's at the right end, and
 * the bottom dock's — the terminal — at the right after them
 * (`workspace.rs:1757-1759`). Move a panel across in settings and its button
 * moves with it, which is the only arrangement in which the button says where
 * the panel will appear.
 */
@Composable
fun StatusBar(
    cursorRow: Int,
    cursorCol: Int,
    modifier: Modifier = Modifier,
    language: String? = null,
    hasFile: Boolean = false,
    /** Panels docked left, in the order they appear in the enum. */
    leftPanels: List<PanelButton> = emptyList(),
    rightPanels: List<PanelButton> = emptyList(),
    isTerminalOpen: Boolean = false,
    onToggleTerminal: (() -> Unit)? = null,
    /**
     * The project's diagnostics, from `lspDiagnostics`. Null with no project
     * open, which is when Zed's indicator has nothing to summarise either.
     */
    diagnostics: DiagnosticSummary? = null,
    /** The project's language servers, from `lspServers`. */
    servers: List<LspServer> = emptyList(),
    /** The diagnostic under the caret in the active editor, if any. */
    cursorDiagnostic: Diagnostic? = null,
    /** Go to the next diagnostic in the active editor — Zed's button action. */
    onGoToDiagnostic: (() -> Unit)? = null,
    /**
     * Install the server that could not start. Null where there is no
     * userland to install into, which leaves the note as plain text rather
     * than a button that cannot work.
     */
    onInstallServer: ((LspServer) -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 30 = a 22px default button plus 4px of padding on each side,
            // which is how Zed's status bar gets its height rather than by
            // declaring one (crates/workspace/src/status_bar.rs:153).
            .height(30.dp)
            .background(theme.color("status_bar.background"))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        // `gap_1` = 4px within a group (status_bar.rs:196, 215).
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (button in leftPanels) {
            PanelStatusButton(button)
        }

        // Zed registers both of these as *left* items, the language-server
        // button first and the diagnostic summary next to it
        // (crates/zed/src/zed.rs:640-641).
        val blocked = servers.firstOrNull { it.note != null }
        if (blocked != null) {
            LanguageServerNote(
                note = blocked.note!!,
                others = servers.count { it.note != null } - 1,
                onClick = onInstallServer?.let { install -> { install(blocked) } },
            )
        }
        if (diagnostics != null) {
            DiagnosticIndicator(summary = diagnostics, onClick = onGoToDiagnostic)
        }

        // The message of the diagnostic under the caret, taking whatever room
        // is left before the cursor position — Zed's `Button` at
        // `LabelSize::Small` with `.truncate(true)`, whose click is
        // `go_to_next_diagnostic` (crates/diagnostics/src/items.rs:60-85).
        // A Box rather than a Spacer so the message has somewhere to be
        // clipped: without one it would push the right-hand group off the
        // edge of a phone the first time a server said anything long.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (cursorDiagnostic != null) {
                CursorDiagnosticMessage(cursorDiagnostic, onGoToDiagnostic)
            }
        }

        if (hasFile) {
            // Zed writes the caret as line:column — both it and the language
            // are `Label`s at the default colour, `text`, not muted
            // (cursor_position.rs:210-247).
            Text(
                text = "${cursorRow + 1}:${cursorCol + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (language != null) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // A dock group is fenced with a 1px × 16px divider on the side facing
        // the middle (dock.rs:1433-1446, divider.rs:29, 147-149).
        if (rightPanels.isNotEmpty() || onToggleTerminal != null) {
            GroupDivider(theme.color("border"))
        }
        for (button in rightPanels) {
            PanelStatusButton(button)
        }
        if (onToggleTerminal != null) {
            // The touch twin of Ctrl+`, and the only way to reach a terminal
            // on a device with no keyboard attached. At the right end, where
            // Zed puts its bottom dock's buttons.
            StatusIconAction(
                icon = R.drawable.ic_ui_terminal,
                label = "Toggle the terminal",
                emphasised = isTerminalOpen,
                onClick = onToggleTerminal,
            )
        }
    }
}

@Composable
private fun PanelStatusButton(button: PanelButton) {
    StatusIconAction(
        icon = button.panel.icon,
        label = if (button.isOpen) "Close the ${button.panel.title}" else button.panel.title,
        emphasised = button.isOpen,
        onClick = button.onClick,
    )
}

@Composable
private fun StatusIconAction(
    icon: Int,
    label: String,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ItemBox)
            .clip(RoundedCornerShape(4.dp))
            // `Subtle`, a ghost button: transparent at rest,
            // `ghost_element.hover` under the pointer, `ghost_element.active`
            // while pressed, swapped instantly — no ripple
            // (button_like.rs:298-303, 324-329).
            .background(
                when {
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            // An open panel's button is `toggle_state(true)` (dock.rs:1400):
            // the box stays ghost and the glyph swaps to `Color::Selected` =
            // `text.accent` (icon_button.rs:246-248, color.rs:108). At rest
            // the glyph is `Color::Default` = `text` (color.rs:92).
            colorFilter = ColorFilter.tint(
                if (emphasised) {
                    theme.color("text.accent", MaterialTheme.colorScheme.onSurface)
                } else {
                    theme.color("text", MaterialTheme.colorScheme.onSurface)
                }
            ),
            modifier = Modifier.size(ItemIconSize),
        )
    }
}

/**
 * Zed's diagnostic summary: a check when the project is clean, otherwise an
 * error icon and a count and a warning icon and a count
 * (crates/diagnostics/src/items.rs:35-58).
 *
 * Two details are Zed's and easy to get wrong. The clean case is `(0, 0)` on
 * *errors and warnings* — a project with nothing but hints still shows the
 * check. And each half appears only when its own count is above zero, so a
 * file with warnings and no errors shows one icon, not a zero.
 *
 * Zed's click deploys its project-diagnostics editor, which we do not have
 * yet; ours goes to the next diagnostic in the active file, which is what
 * Zed's *other* diagnostic button does (items.rs:83).
 */
@Composable
private fun DiagnosticIndicator(summary: DiagnosticSummary, onClick: (() -> Unit)?) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .height(ItemBox)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    onClick == null -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = summary.label,
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Zed's `gap_1` inside the indicator (items.rs:42).
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (summary.isClean) {
            CheckIcon(theme.color("text", MaterialTheme.colorScheme.onSurface), summary.label)
        } else {
            if (summary.errors > 0) {
                XCircleIcon(theme.color("error"), summary.label)
                CountLabel(summary.errors)
            }
            if (summary.warnings > 0) {
                WarningIcon(theme.color("warning"), summary.label)
                CountLabel(summary.warnings)
            }
        }
    }
}

/** `Label::new(count.to_string()).size(LabelSize::Small)` (items.rs:50). */
@Composable
private fun CountLabel(count: Int) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The first line of the diagnostic under the caret, which is what Zed puts
 * beside the counts (`current_diagnostic`, items.rs:60-85). Clicking it goes
 * to the next diagnostic, exactly as Zed's does.
 */
@Composable
private fun CursorDiagnosticMessage(diagnostic: Diagnostic, onClick: (() -> Unit)?) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = diagnostic.label,
        style = MaterialTheme.typography.labelMedium,
        // Zed colours the message by its severity nowhere — it is a plain
        // `Button` label — but the icon beside it is already the severity, so
        // the sentence stays in `text` and the colour stays meaningful.
        color = if (hovered && onClick != null) {
            theme.color("text", MaterialTheme.colorScheme.onSurface)
        } else {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = "Go to the next problem",
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = 4.dp),
    )
}

/**
 * A language server that could not start, said in words.
 *
 * Zed shows this as a coloured dot on its `BoltOutlined` button, with the
 * detail behind a popover (language_tools/src/lsp_button.rs:1367-1379). A
 * popover is a poor fit for a 30px bar on a phone, and the detail is the
 * whole point: a server that could not start is exactly the cue to install
 * it, so the dot keeps Zed's colour and the sentence says what to do.
 */
@Composable
private fun LanguageServerNote(note: String, others: Int, onClick: (() -> Unit)? = null) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val text = if (others > 0) "$note (and $others more)" else note
    Row(
        modifier = Modifier
            .height(ItemBox)
            .clip(RoundedCornerShape(4.dp))
            // The same ghost ramp every other item in this bar wears, and
            // only when there is somewhere for the tap to go.
            .background(
                when {
                    onClick == null -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = "Install it",
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // `Indicator::dot().color(Color::Error)` — 4px, which is Zed's
        // `Indicator` dot at its default size (ui/src/components/indicator.rs).
        Canvas(modifier = Modifier.size(4.dp)) {
            drawCircle(color = theme.color("error"), radius = size.minDimension / 2f)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Zed's `IconName::Check` at `IconSize::Small`, drawn rather than shipped.
 *
 * The three glyphs below are strokes on a canvas for the same reason the
 * editor's fold chevrons are: they are three shapes, and three SVGs to
 * maintain, license and keep in step with the icon set is a worse trade than
 * nine lines of geometry. The proportions are the icon set's 16px grid
 * scaled to the 14px `IconSize::Small` box.
 */
@Composable
private fun CheckIcon(color: Color, label: String) {
    Canvas(modifier = Modifier.size(ItemIconSize).semanticsLabel(label)) {
        val stroke = size.minDimension * 0.11f
        drawLine(
            color = color,
            start = Offset(size.width * 0.22f, size.height * 0.53f),
            end = Offset(size.width * 0.42f, size.height * 0.73f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.42f, size.height * 0.73f),
            end = Offset(size.width * 0.79f, size.height * 0.29f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Zed's `IconName::XCircle`: a ring with a cross in it. */
@Composable
private fun XCircleIcon(color: Color, label: String) {
    Canvas(modifier = Modifier.size(ItemIconSize).semanticsLabel(label)) {
        val stroke = size.minDimension * 0.11f
        drawCircle(
            color = color,
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        val inset = size.minDimension * 0.33f
        drawLine(
            color = color,
            start = Offset(inset, inset),
            end = Offset(size.width - inset, size.height - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width - inset, inset),
            end = Offset(inset, size.height - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Zed's `IconName::Warning`: a triangle with a bang in it. */
@Composable
private fun WarningIcon(color: Color, label: String) {
    Canvas(modifier = Modifier.size(ItemIconSize).semanticsLabel(label)) {
        val stroke = size.minDimension * 0.11f
        val top = size.height * 0.14f
        val bottom = size.height * 0.84f
        val path = Path().apply {
            moveTo(size.width / 2f, top)
            lineTo(size.width - stroke, bottom)
            lineTo(stroke, bottom)
            close()
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(
            color = color,
            start = Offset(size.width / 2f, size.height * 0.42f),
            end = Offset(size.width / 2f, size.height * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color,
            radius = stroke * 0.55f,
            center = Offset(size.width / 2f, size.height * 0.73f),
        )
    }
}

/**
 * A canvas has no text to read out, so the icons carry the summary Zed puts
 * in `aria_label` (items.rs:117).
 */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }

/**
 * Zed's `Divider::vertical()` between the middle and a dock's button group:
 * 1px wide, `h_4` (16px) tall, in `border` (divider.rs:29, 147-149;
 * dock.rs:1433-1446).
 */
@Composable
private fun GroupDivider(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(color)
    )
}
