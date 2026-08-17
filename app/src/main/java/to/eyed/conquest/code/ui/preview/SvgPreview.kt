package to.eyed.conquest.code.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.ui.editor.EditorState
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.UiFontFamily

/** How far a pinch may take the drawing, either way. */
private const val MIN_ZOOM = 0.2f
private const val MAX_ZOOM = 24f

/** Breathing room around the drawing, so a full-bleed icon is not edge to edge. */
private val Inset = 24.dp

/**
 * The picture an SVG describes, beside the text that describes it.
 *
 * Zed keeps SVG out of its image viewer by name (`image_store.rs:261`) and
 * opens it in the editor with a preview behind the toolbar's eye, which is the
 * right way round: the person who opens `icon.svg` in an IDE is usually the
 * person editing it. So this is a *preview*, exactly like the Markdown one —
 * same dock, same header, same rule that the source stays where it was.
 *
 * It follows the buffer rather than the file: the drawing updates as the `d`
 * attribute is typed, which is what makes it worth having open. [SvgDocument]
 * does the reading; everything Android-shaped happens here.
 *
 * Zoom is a pinch or a scroll, pan is a drag, and a double tap puts both back.
 * A 16px icon is unreadable at 1:1 on a 420dpi screen, so the drawing is fitted
 * to the pane first and the zoom is on top of that.
 */
@Composable
fun SvgPreview(
    editor: EditorState,
    path: String,
    isDock: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val focus = remember { FocusRequester() }
    var dockWidth by remember { mutableStateOf(PreviewDockWidth) }

    val isSvg = PreviewKind.of(path) == PreviewKind.Svg

    // Two things move the text and only one of them is visible to composition:
    // the same reload-from-disk case the Markdown preview polls for.
    var engineVersion by remember(editor) { mutableLongStateOf(-1L) }
    LaunchedEffect(editor) {
        while (true) {
            engineVersion = editor.session.version
            delay(PREVIEW_VERSION_POLL_MS)
        }
    }

    var drawing by remember(editor) { mutableStateOf(SvgDrawing.EMPTY) }
    LaunchedEffect(editor, isSvg, editor.revision, engineVersion) {
        if (!isSvg) {
            drawing = SvgDrawing.EMPTY
            return@LaunchedEffect
        }
        delay(PREVIEW_REPARSE_DEBOUNCE_MS)
        // All of it off the main thread: reading past the drawn window is a
        // JNI call that takes the engine's buffer mutex, and turning a few
        // thousand `d` attributes into paths is not free either.
        drawing = withContext(Dispatchers.Default) {
            val source = cappedSource(editor.lineCount) { first, last -> editor.linesOf(first, last) }
            when {
                source == null -> SvgDrawing.TOO_LARGE
                else -> SvgDrawing.of(source)
            }
        }
    }

    var zoom by remember(editor) { mutableFloatStateOf(1f) }
    var pan by remember(editor) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = modifier.then(if (isDock) Modifier.fillMaxHeight() else Modifier.fillMaxSize())
    ) {
        val available = if (constraints.hasBoundedWidth) maxWidth else Dp.Infinity
        Row(
            modifier = if (isDock) {
                Modifier.width(clampDockWidth(dockWidth, available)).fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            }
        ) {
            if (isDock) {
                PreviewResizeHandle { delta -> dockWidth = clampDockWidth(dockWidth - delta, available) }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(theme.color("editor.background"))
                    .focusRequester(focus)
                    .focusable()
                    // Focus is taken on a press, never requested when the panel
                    // appears: opening the preview must not pull the keyboard
                    // out of the editor it is following.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            focus.requestFocus()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape && !event.isCtrlPressed -> {
                                onDismiss()
                                true
                            }
                            // The keyboard's zoom, for a device with no touch
                            // screen and for anyone who cannot pinch.
                            event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.Plus) -> {
                                zoom = (zoom * 1.25f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                true
                            }
                            event.isCtrlPressed && event.key == Key.Minus -> {
                                zoom = (zoom / 1.25f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                true
                            }
                            event.isCtrlPressed && event.key == Key.Zero -> {
                                zoom = 1f
                                pan = Offset.Zero
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                PreviewHeader(path = path, onDismiss = onDismiss)
                HorizontalDivider(color = theme.color("border"))
                when {
                    !isSvg -> PreviewNotice(
                        "The SVG preview draws a .svg file. Open one and it appears here."
                    )
                    drawing.isTooLarge -> PreviewNotice(
                        "This file is too large to preview. It is still open in the editor."
                    )
                    drawing.document == null -> PreviewNotice(
                        "This is not an SVG that can be drawn — the editor still has the source."
                    )
                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        SvgCanvas(
                            drawing = drawing,
                            zoom = zoom,
                            pan = pan,
                            onGesture = { panned, zoomed ->
                                zoom = (zoom * zoomed).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                pan += panned
                            },
                            onReset = {
                                zoom = 1f
                                pan = Offset.Zero
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Footer(drawing)
                    }
                }
            }
        }
    }
}

@Composable
private fun SvgCanvas(
    drawing: SvgDrawing,
    zoom: Float,
    pan: Offset,
    onGesture: (Offset, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    // What `currentColor` means here. Zed's own icon set is drawn entirely in
    // it, and resolving it to black would make every one of them invisible in
    // a dark theme.
    val current = theme.color("text")
    val document = drawing.document ?: return
    Canvas(
        modifier = modifier
            .padding(Inset)
            .pointerInput(Unit) {
                detectTransformGestures { _, panned, zoomed, _ -> onGesture(panned, zoomed) }
            }
            // Second gesture detector, after the transform one: a double tap is
            // the universal "put it back", and it must not eat the pinch.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onReset() })
            }
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val (scale, offsetX, offsetY) = document.fit(size.width, size.height)
        withTransform({
            translate(pan.x, pan.y)
            scale(zoom, zoom, pivot = center)
            translate(offsetX, offsetY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            for (shape in drawing.shapes) {
                shape.fill?.let { paint ->
                    drawPath(shape.path, color = paint.resolve(current, shape.fillAlpha))
                }
                shape.stroke?.let { paint ->
                    drawPath(
                        path = shape.path,
                        color = paint.resolve(current, shape.strokeAlpha),
                        style = Stroke(
                            width = shape.strokeWidth,
                            cap = if (shape.capRound) StrokeCap.Round else StrokeCap.Butt,
                            join = if (shape.joinRound) StrokeJoin.Round else StrokeJoin.Miter,
                        ),
                    )
                }
            }
        }
    }
}

/** The drawing's own size, and what it contains that we did not draw. */
@Composable
private fun Footer(drawing: SvgDrawing) {
    val theme = LocalZedTheme.current
    val document = drawing.document ?: return
    val size = "${trim(document.viewportWidth)} × ${trim(document.viewportHeight)}"
    val missing = document.unsupported
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (missing.isEmpty()) {
                size
            } else {
                // Said out loud rather than drawn wrong: an SVG with a gradient
                // comes out missing its fills, and the source is right there.
                "$size · not drawn: ${missing.joinToString(", ")}"
            },
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 12.sp),
            color = if (missing.isEmpty()) theme.color("text.muted") else theme.color("text"),
        )
    }
}

private fun trim(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

/** ARGB and an inherited `currentColor`, resolved against the theme. */
private fun SvgPaint.resolve(current: Color, alpha: Float): Color {
    val base = when (this) {
        is SvgPaint.Current -> current
        is SvgPaint.Solid -> Color(argb)
    }
    return if (alpha >= 1f) base else base.copy(alpha = base.alpha * alpha.coerceIn(0f, 1f))
}

/**
 * A parsed document together with the [Path]s it draws as.
 *
 * The paths are built here rather than in the canvas because
 * `PathParser.createPathFromPathData` is a parse per shape, and the canvas runs
 * on the main thread on every frame.
 */
private class SvgDrawing private constructor(
    val document: SvgDocument?,
    val shapes: List<Drawable>,
    /** True when the file was refused for its size. See [MAX_PREVIEW_CHARS]. */
    val isTooLarge: Boolean = false,
) {
    class Drawable(
        val path: Path,
        val fill: SvgPaint?,
        val fillAlpha: Float,
        val stroke: SvgPaint?,
        val strokeAlpha: Float,
        val strokeWidth: Float,
        val capRound: Boolean,
        val joinRound: Boolean,
    )

    companion object {
        val EMPTY = SvgDrawing(null, emptyList())
        val TOO_LARGE = SvgDrawing(null, emptyList(), isTooLarge = true)

        /** **Blocks the thread it is called on** — it belongs on a worker. */
        fun of(source: String): SvgDrawing {
            val document = SvgDocument.parse(source) ?: return EMPTY
            val shapes = document.shapes.mapNotNull { shape ->
                // A `d` the parser chokes on loses that one shape, not the
                // drawing: half an icon still tells you what you typed wrong.
                val path = runCatching {
                    PathParser.createPathFromPathData(shape.pathData)
                }.getOrNull() ?: return@mapNotNull null
                if (!shape.transform.isIdentity) {
                    val matrix = android.graphics.Matrix()
                    matrix.setValues(
                        floatArrayOf(
                            shape.transform.a, shape.transform.c, shape.transform.e,
                            shape.transform.b, shape.transform.d, shape.transform.f,
                            0f, 0f, 1f,
                        )
                    )
                    path.transform(matrix)
                }
                val composed = path.asComposePath()
                if (shape.evenOdd) composed.fillType = PathFillType.EvenOdd
                Drawable(
                    path = composed,
                    fill = shape.fill,
                    fillAlpha = shape.fillAlpha,
                    stroke = shape.stroke,
                    strokeAlpha = shape.strokeAlpha,
                    strokeWidth = shape.strokeWidth,
                    capRound = shape.strokeCapRound,
                    joinRound = shape.strokeJoinRound,
                )
            }
            return SvgDrawing(document, shapes)
        }
    }
}

@Composable
private fun PreviewNotice(text: String) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 13.sp),
            color = theme.color("text.muted"),
        )
    }
}
