package to.eyed.conquest.code.ui.preview

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * Enough of SVG to draw an icon or a logo, which is what an SVG in a source
 * tree usually is.
 *
 * Android has no SVG renderer and no dependency here brings one; what it does
 * have is `PathParser`, which turns an SVG `d` attribute into a `Path` —
 * because that is exactly the syntax VectorDrawable borrowed from SVG. So the
 * curves come free, and the work is the document around them: the viewport,
 * the primitives that are not `<path>`, the transform chain, and which paint
 * each shape inherits.
 *
 * None of that needs Android, and this file deliberately touches none of it —
 * no `Path`, no `Color`, no `Matrix`. Paths stay the strings they were in the
 * file and paints stay numbers, so the whole parser runs in an ordinary JVM
 * test, which is the only way the edges below ever get checked. [SvgPreview]
 * turns the result into something drawable.
 *
 * What it reads: `path`, `rect` (including rounded), `circle`, `ellipse`,
 * `line`, `polyline`, `polygon`; `fill`, `stroke`, their opacities, widths,
 * caps, joins and rule, inherited down `<g>` as SVG inherits them; and
 * `translate`/`scale`/`rotate`/`matrix` transforms, composed as matrices.
 * What it refuses: gradients, patterns, filters, masks, clip paths, text,
 * embedded images, CSS and `use`. Those are *named* in [unsupported] rather
 * than drawn wrong, and the pane says so out loud — the source is one tap
 * away, and for a gradient that is the honest answer.
 *
 * This is a viewer. Nothing here writes an SVG back.
 */
class SvgDocument(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val shapes: List<SvgShape>,
    /** Feature names met and not drawn, for the pane to admit to. */
    val unsupported: Set<String>,
) {
    companion object {
        /** Bigger than any icon; small enough that a parse cannot hang a frame. */
        const val MAX_CHARS = 2 * 1024 * 1024

        /** Nested groups deeper than this are pathological, not documents. */
        private const val MAX_DEPTH = 64

        /** A drawing with more shapes than this is a map, and not one we draw. */
        private const val MAX_SHAPES = 20_000

        private val UNSUPPORTED = setOf(
            "linearGradient", "radialGradient", "pattern", "filter", "mask",
            "clipPath", "text", "image", "use", "style", "foreignObject",
            "switch", "marker", "symbol",
        )

        /**
         * Read [text], or null when it is not an SVG this can draw at all —
         * malformed XML, no `<svg>` root, or no usable viewport.
         *
         * The document may come from a repository the user cloned an hour ago,
         * so the reader is closed down to what an SVG needs: no DOCTYPE at all
         * (which takes billion-laughs and every entity trick with it), no
         * external entities, no network. An SVG has no legitimate use for any
         * of them.
         */
        fun parse(text: String): SvgDocument? {
            if (text.length > MAX_CHARS) return null
            val document = runCatching {
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.isXIncludeAware = false
                factory.isExpandEntityReferences = false
                factory.isNamespaceAware = true
                val builder = factory.newDocumentBuilder()
                // A parser that reaches the network for a schema is a parser
                // that leaks the fact you opened the file.
                builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
                builder.setErrorHandler(null)
                builder.parse(InputSource(StringReader(text)))
            }.getOrNull() ?: return null

            val root = document.documentElement ?: return null
            if (root.localName != "svg" && root.tagName != "svg") return null

            val (width, height) = viewport(root)
            if (width <= 0f || height <= 0f) return null

            val shapes = mutableListOf<SvgShape>()
            val unsupported = sortedSetOf<String>()
            walk(root, SvgTransform.IDENTITY, Style.ROOT, 0, shapes, unsupported)
            return SvgDocument(width, height, shapes, unsupported)
        }

        private fun walk(
            element: Element,
            inherited: SvgTransform,
            style: Style,
            depth: Int,
            shapes: MutableList<SvgShape>,
            unsupported: MutableSet<String>,
        ) {
            if (depth > MAX_DEPTH || shapes.size >= MAX_SHAPES) return
            val here = inherited * SvgTransform.parse(element.attribute("transform"), unsupported)
            val inheritedStyle = style.inherit(element)

            val tag = element.localName ?: element.tagName
            if (tag != "svg" && tag != "g" && tag != "defs" && tag != "title" && tag != "desc") {
                val data = pathData(element, tag)
                if (data != null) {
                    shapes += inheritedStyle.shape(
                        data = data,
                        transform = here,
                        isLine = tag == "line" || tag == "polyline",
                        unsupported = unsupported,
                    )
                }
            }

            var child = element.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val name = (child as Element).localName ?: child.tagName
                    when {
                        name in UNSUPPORTED -> unsupported += reportName(name)
                        // `<defs>` holds definitions for `use`, which is on the
                        // refused list; walking it would draw them all at 0,0.
                        name == "defs" -> Unit
                        else -> walk(child, here, inheritedStyle, depth + 1, shapes, unsupported)
                    }
                }
                child = child.nextSibling
            }
        }

        /** What the pane prints. Twelve tag names would be a wall of jargon. */
        private fun reportName(tag: String): String = when (tag) {
            "linearGradient", "radialGradient" -> "gradients"
            "pattern" -> "patterns"
            "filter" -> "filters"
            "mask" -> "masks"
            "clipPath" -> "clip paths"
            "text" -> "text"
            "image" -> "embedded images"
            "use", "symbol", "marker", "switch" -> "reused shapes"
            "style" -> "CSS"
            else -> tag
        }

        /** `viewBox` wins over `width`/`height`: it is the drawing's own units. */
        private fun viewport(root: Element): Pair<Float, Float> {
            root.attribute("viewBox")?.let { box ->
                val parts = box.trim().split(NUMBER_SEPARATOR).mapNotNull(String::toFloatOrNull)
                if (parts.size == 4 && parts[2] > 0f && parts[3] > 0f) return parts[2] to parts[3]
            }
            return length(root.attribute("width")) to length(root.attribute("height"))
        }

        /** `24`, `24px` and `24.0pt` are all 24 here; a percentage is not a size. */
        private fun length(value: String?): Float {
            val text = value?.trim() ?: return 0f
            if (text.endsWith("%")) return 0f
            return text.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: 0f
        }

        /** Every primitive as the `d` it is equivalent to. */
        private fun pathData(element: Element, tag: String): String? {
            fun number(name: String) = length(element.attribute(name))
            return when (tag) {
                "path" -> element.attribute("d")?.takeIf { it.isNotBlank() }
                "rect" -> {
                    val x = number("x")
                    val y = number("y")
                    val w = number("width")
                    val h = number("height")
                    if (w <= 0f || h <= 0f) return null
                    // A rounded rect is four arcs; `rx` alone implies `ry`.
                    val rx = minOf(maxOf(number("rx"), number("ry")), w / 2f)
                    val ry = minOf(maxOf(number("ry"), number("rx")), h / 2f)
                    if (rx <= 0f || ry <= 0f) {
                        "M$x,$y h$w v$h h${-w} Z"
                    } else {
                        "M${x + rx},$y h${w - 2 * rx} a$rx,$ry 0 0,1 $rx,$ry " +
                            "v${h - 2 * ry} a$rx,$ry 0 0,1 ${-rx},$ry " +
                            "h${-(w - 2 * rx)} a$rx,$ry 0 0,1 ${-rx},${-ry} " +
                            "v${-(h - 2 * ry)} a$rx,$ry 0 0,1 $rx,${-ry} Z"
                    }
                }
                // Two half-arcs rather than one: an arc of exactly 360° is
                // degenerate in path syntax and draws nothing.
                "circle" -> {
                    val r = number("r")
                    if (r <= 0f) null
                    else ellipsePath(number("cx"), number("cy"), r, r)
                }
                "ellipse" -> {
                    val rx = number("rx")
                    val ry = number("ry")
                    if (rx <= 0f || ry <= 0f) null else ellipsePath(number("cx"), number("cy"), rx, ry)
                }
                "line" -> "M${number("x1")},${number("y1")} L${number("x2")},${number("y2")}"
                "polyline", "polygon" -> {
                    val points = element.attribute("points")
                        ?.trim()
                        ?.split(NUMBER_SEPARATOR)
                        ?.mapNotNull(String::toFloatOrNull)
                        ?: return null
                    if (points.size < 4) return null
                    buildString {
                        append("M${points[0]},${points[1]}")
                        for (i in 2 until points.size - 1 step 2) append(" L${points[i]},${points[i + 1]}")
                        if (tag == "polygon") append(" Z")
                    }
                }
                else -> null
            }
        }

        private fun ellipsePath(cx: Float, cy: Float, rx: Float, ry: Float): String =
            "M${cx - rx},$cy a$rx,$ry 0 1,0 ${rx * 2},0 a$rx,$ry 0 1,0 ${-rx * 2},0 Z"

        private val NUMBER_SEPARATOR = Regex("[\\s,]+")
    }

    /**
     * How far the drawing scales to sit inside [width] × [height], and where
     * it lands — its own ratio kept, centred in whatever is left over.
     */
    fun fit(width: Float, height: Float): Triple<Float, Float, Float> {
        val scale = minOf(width / viewportWidth, height / viewportHeight)
        return Triple(
            scale,
            (width - viewportWidth * scale) / 2f,
            (height - viewportHeight * scale) / 2f,
        )
    }
}

/** One drawable shape: the `d` it came from, and how to paint it. */
class SvgShape(
    val pathData: String,
    val transform: SvgTransform,
    val fill: SvgPaint?,
    val fillAlpha: Float,
    val stroke: SvgPaint?,
    val strokeAlpha: Float,
    val strokeWidth: Float,
    val strokeCapRound: Boolean,
    val strokeJoinRound: Boolean,
    val evenOdd: Boolean,
)

/**
 * What to paint a shape with, when there is something to paint.
 *
 * `currentColor` is a third case rather than a colour: it means "whatever the
 * text around me is", which the file cannot know and the pane can — and it is
 * what Zed's own icons are drawn with, so getting it wrong would render the
 * app's own icon set as black-on-black in a dark theme.
 */
sealed interface SvgPaint {
    object Current : SvgPaint
    data class Solid(val argb: Long) : SvgPaint
}

/**
 * The paint and stroke attributes in force at a point in the tree.
 *
 * SVG inherits these down the element tree, and icons lean on it constantly —
 * `<svg fill="none" stroke="currentColor">` with bare `<path>`s inside is the
 * shape of nearly every icon set, Zed's included. Reading each shape's own
 * attributes and stopping there would draw all of them as black silhouettes.
 */
private class Style(
    val fill: String?,
    val stroke: String?,
    val fillOpacity: Float,
    val strokeOpacity: Float,
    val opacity: Float,
    val strokeWidth: Float,
    val linecap: String?,
    val linejoin: String?,
    val fillRule: String?,
) {
    fun inherit(element: Element): Style = Style(
        fill = element.attribute("fill") ?: fill,
        stroke = element.attribute("stroke") ?: stroke,
        fillOpacity = element.attribute("fill-opacity")?.toFloatOrNull() ?: fillOpacity,
        strokeOpacity = element.attribute("stroke-opacity")?.toFloatOrNull() ?: strokeOpacity,
        // Group opacity multiplies rather than replaces: two nested groups at
        // half opacity are a quarter, which is what a compositor would do.
        opacity = opacity * (element.attribute("opacity")?.toFloatOrNull() ?: 1f),
        strokeWidth = element.attribute("stroke-width")?.toFloatOrNull() ?: strokeWidth,
        linecap = element.attribute("stroke-linecap") ?: linecap,
        linejoin = element.attribute("stroke-linejoin") ?: linejoin,
        fillRule = element.attribute("fill-rule") ?: element.attribute("clip-rule") ?: fillRule,
    )

    fun shape(
        data: String,
        transform: SvgTransform,
        isLine: Boolean,
        unsupported: MutableSet<String>,
    ): SvgShape = SvgShape(
        pathData = data,
        transform = transform,
        // An unfilled `<line>` is the common case and a filled one is almost
        // always a mistake in the file; everything else defaults to black,
        // as SVG says.
        fill = paint(fill, if (isLine) null else SvgPaint.Solid(0xFF000000L), unsupported),
        fillAlpha = (fillOpacity * opacity).coerceIn(0f, 1f),
        stroke = paint(stroke, default = null, unsupported = unsupported),
        strokeAlpha = (strokeOpacity * opacity).coerceIn(0f, 1f),
        strokeWidth = strokeWidth,
        strokeCapRound = linecap == "round",
        strokeJoinRound = linejoin == "round",
        evenOdd = fillRule == "evenodd",
    )

    private fun paint(
        value: String?,
        default: SvgPaint?,
        unsupported: MutableSet<String>,
    ): SvgPaint? = when {
        value == null -> default
        value == "none" || value == "transparent" -> null
        value == "currentColor" -> SvgPaint.Current
        // A `url(#id)` is a gradient or a pattern. Reported *here*, at the
        // reference, rather than where it is defined: the definition is
        // usually inside `<defs>`, which this does not walk into, so a shape
        // would otherwise vanish with nothing said about why.
        value.startsWith("url(") -> {
            unsupported += "gradients"
            null
        }
        else -> colour(value)?.let(SvgPaint::Solid) ?: default
    }

    companion object {
        val ROOT = Style(
            fill = null,
            stroke = null,
            fillOpacity = 1f,
            strokeOpacity = 1f,
            opacity = 1f,
            strokeWidth = 1f,
            linecap = null,
            linejoin = null,
            fillRule = null,
        )
    }
}

/** `#abc`, `#aabbcc`, `#aabbccdd`, `rgb(…)`, or one of the names icons use. */
internal fun colour(value: String): Long? {
    val text = value.trim()
    NAMED[text.lowercase()]?.let { return it }
    if (text.startsWith("rgb")) {
        val parts = text.substringAfter('(').substringBefore(')')
            .split(',')
            .map { it.trim() }
        if (parts.size !in 3..4) return null
        val channels = parts.take(3).map { part ->
            val number = part.removeSuffix("%").toFloatOrNull() ?: return null
            val scaled = if (part.endsWith("%")) number * 255f / 100f else number
            scaled.toInt().coerceIn(0, 255).toLong()
        }
        val alpha = if (parts.size == 4) {
            ((parts[3].toFloatOrNull() ?: return null).coerceIn(0f, 1f) * 255f).toInt().toLong()
        } else {
            255L
        }
        return (alpha shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }
    if (!text.startsWith("#")) return null
    val hex = text.substring(1)
    // `#abc` is `#aabbcc` — forgetting that produces a wildly wrong colour
    // rather than a slightly wrong one.
    val expanded = if (hex.length == 3 || hex.length == 4) {
        hex.map { "$it$it" }.joinToString("")
    } else {
        hex
    }
    if (expanded.any { it.digitToIntOrNull(16) == null }) return null
    val bits = expanded.toLongOrNull(16) ?: return null
    return when (expanded.length) {
        6 -> 0xFF000000L or bits
        // SVG writes `#RRGGBBAA`; every graphics API here wants ARGB.
        8 -> (bits ushr 8) or ((bits and 0xFFL) shl 24)
        else -> null
    }
}

private val NAMED = mapOf(
    "black" to 0xFF000000L,
    "white" to 0xFFFFFFFFL,
    "red" to 0xFFFF0000L,
    "lime" to 0xFF00FF00L,
    "green" to 0xFF008000L,
    "blue" to 0xFF0000FFL,
    "yellow" to 0xFFFFFF00L,
    "cyan" to 0xFF00FFFFL,
    "aqua" to 0xFF00FFFFL,
    "magenta" to 0xFFFF00FFL,
    "fuchsia" to 0xFFFF00FFL,
    "gray" to 0xFF808080L,
    "grey" to 0xFF808080L,
    "silver" to 0xFFC0C0C0L,
    "orange" to 0xFFFFA500L,
    "purple" to 0xFF800080L,
    "navy" to 0xFF000080L,
    "teal" to 0xFF008080L,
    "olive" to 0xFF808000L,
    "maroon" to 0xFF800000L,
)

/**
 * A 2-D affine transform: `[a c e; b d f]`, the same six numbers SVG's own
 * `matrix()` takes and the same six `android.graphics.Matrix` wants back.
 *
 * Kept as a matrix rather than as translate/scale/rotate fields because the
 * three do not commute — `rotate(45) translate(10,0)` and `translate(10,0)
 * rotate(45)` put a shape in different places, and a struct of separate
 * fields quietly draws both the same way.
 */
class SvgTransform(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    operator fun times(other: SvgTransform): SvgTransform = SvgTransform(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        e = a * other.e + c * other.f + e,
        f = b * other.e + d * other.f + f,
    )

    val isIdentity: Boolean
        get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f

    /** Where this transform sends the point ([x], [y]) — the whole meaning of it. */
    fun map(x: Float, y: Float): Pair<Float, Float> = (a * x + c * y + e) to (b * x + d * y + f)

    companion object {
        val IDENTITY = SvgTransform(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(x: Float, y: Float) = SvgTransform(1f, 0f, 0f, 1f, x, y)

        fun scale(x: Float, y: Float) = SvgTransform(x, 0f, 0f, y, 0f, 0f)

        fun rotate(degrees: Float): SvgTransform {
            val radians = Math.toRadians(degrees.toDouble())
            val cos = kotlin.math.cos(radians).toFloat()
            val sin = kotlin.math.sin(radians).toFloat()
            return SvgTransform(cos, sin, -sin, cos, 0f, 0f)
        }

        /**
         * `transform="translate(4 4) rotate(45 8 8)"` — applied left to right,
         * which for matrices means multiplied in that order.
         */
        fun parse(value: String?, unsupported: MutableSet<String>? = null): SvgTransform {
            if (value.isNullOrBlank()) return IDENTITY
            var result = IDENTITY
            for (match in FUNCTION.findAll(value)) {
                val name = match.groupValues[1]
                val n = match.groupValues[2].trim().split(SEPARATOR).mapNotNull(String::toFloatOrNull)
                if (n.isEmpty()) continue
                result = result * when (name) {
                    "translate" -> translate(n[0], n.getOrElse(1) { 0f })
                    "scale" -> scale(n[0], n.getOrElse(1) { n[0] })
                    "rotate" -> if (n.size >= 3) {
                        // Rotation about a point is that point brought to the
                        // origin, turned, and put back.
                        translate(n[1], n[2]) * rotate(n[0]) * translate(-n[1], -n[2])
                    } else {
                        rotate(n[0])
                    }
                    "matrix" -> if (n.size == 6) {
                        SvgTransform(n[0], n[1], n[2], n[3], n[4], n[5])
                    } else {
                        IDENTITY
                    }
                    else -> {
                        // skewX/skewY: rare enough in icons to name rather
                        // than implement, and naming it beats drawing it flat.
                        unsupported?.add("skew")
                        IDENTITY
                    }
                }
            }
            return result
        }

        private val FUNCTION = Regex("(translate|scale|rotate|matrix|skewX|skewY)\\s*\\(([^)]*)\\)")
        private val SEPARATOR = Regex("[\\s,]+")
    }
}

/** DOM's `getAttribute` returns `""` for an absent attribute; null is clearer. */
private fun Element.attribute(name: String): String? =
    getAttribute(name).takeIf { it.isNotEmpty() }
