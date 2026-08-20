package to.eyed.conquest.code.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/**
 * Completions, and the request plumbing hover and go-to-definition share.
 *
 * The bridge's request half is start-and-poll, like the project search: a call
 * returns an id at once, `lspRequestVersion` says 1 in flight / 2 settled / 0
 * forgotten, and only when it settles is the (possibly tens-of-kilobytes)
 * answer read. Each kind has one slot globally, and starting a request retires
 * the previous one of that kind at the server — which is exactly what a popup
 * re-asking as the user types wants.
 *
 * Everything above the composables here is free of Compose and of the JNI
 * object so it can be tested on the host: the filter decides what the user
 * sees, the placement arithmetic decides whether they see it at all, and the
 * staleness rule decides whether what they see is about the text in front of
 * them.
 */

/** How often a request's version counter is read while it is in flight. */
private const val REQUEST_POLL_MILLIS = 60L

/**
 * How long a burst of keystrokes is allowed to collapse into one request.
 *
 * The bridge says a popup *may* call `lspRequestCompletion` on every keystroke
 * — the engine supersedes and retires the old one at the server. It still
 * costs a JNI round trip and a message to a server behind proot per key, so
 * the burst is collapsed: at typing speed this is one request per word rather
 * than one per letter, and the menu's own filtering keeps the list moving in
 * between.
 */
private const val COMPLETION_DEBOUNCE_MILLIS = 90L

/**
 * How long the menu stays quiet after a buffer answers `unavailable`.
 *
 * `unavailable` is not an answer, so it is never cached as one — but it does
 * mean nothing is listening, and asking again on every keystroke in a file
 * whose language has no server is pure JNI churn. A server can appear at any
 * moment (`apt install clangd` in the terminal, with the file open), so the
 * silence has to end by itself; a few seconds is short enough that the install
 * finishing is followed by the next word typed, and long enough to cost
 * nothing.
 */
private const val UNAVAILABLE_QUIET_MILLIS = 5_000L

/** Zed's `hover_popover_delay` (assets/settings/default.json:146). */
internal const val HOVER_DELAY_MILLIS = 300L

/** Zed's `COMPLETION_MENU_MIN_WIDTH` (code_context_menus.rs:52). */
private val MENU_WIDTH = 280.dp

/**
 * One row. Zed's completion rows are inset `ListItem`s at the density the
 * owner's call settled on — Zed's own metrics, not an inflated touch target
 * (agent-docs/DECISIONS.md, "Exact Zed density wins over the 40dp rule").
 */
private val MENU_ROW_HEIGHT = 22.dp

/** Zed's `POPOVER_Y_PADDING` (ui/src/components/popover.rs:9), 4px each side. */
private val MENU_Y_PADDING = 4.dp

/**
 * Zed's `min_entries_visible` / `max_entries_visible` defaults
 * (crates/editor/src/element.rs:3823-3831).
 */
private const val MENU_MIN_ROWS = 3
private const val MENU_MAX_ROWS = 12

/** A nullable JSON string, read the way Android's `org.json` requires. */
private fun JSONObject.jsonStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name, null)?.takeIf { it.isNotEmpty() }

/** A buffer range as the bridge spells one: 0-based rows, UTF-16 columns. */
data class LspRange(
    val row: Int,
    val colUtf16: Int,
    val endRow: Int,
    val endColUtf16: Int,
)

internal fun parseLspRange(json: JSONObject?): LspRange? {
    if (json == null) return null
    val row = json.optInt("row", 0)
    val col = json.optInt("col_utf16", 0)
    return LspRange(
        row = row,
        colUtf16 = col,
        endRow = json.optInt("end_row", row),
        endColUtf16 = json.optInt("end_col_utf16", col),
    )
}

/** The three questions the bridge will ask a server. */
enum class LspRequestKind { Completion, Hover, Definition }

/**
 * What became of a request. `done` with an empty payload is a real answer —
 * "no completions here" — and may be cached; the other three are not answers
 * at all and must never be cached as one.
 */
enum class LspRequestState { Pending, Done, Timeout, Unavailable, Cancelled }

/**
 * A settled request, with the where and when it was asked.
 *
 * [kind] is parsed but never routed on: for an id the engine has forgotten the
 * whole object is a placeholder except `id` and `state`, `kind` included, and
 * the caller is the one that knows what it asked for.
 */
class LspAnswer(
    val id: Long,
    val kind: LspRequestKind?,
    val state: LspRequestState,
    val bufferId: Long,
    val row: Int,
    val colUtf16: Int,
    val bufferVersion: Long?,
    val payload: JSONObject?,
) {
    companion object {
        /** The answer for an id the engine no longer knows about. */
        fun forgotten(id: Long) = LspAnswer(
            id = id,
            kind = null,
            state = LspRequestState.Cancelled,
            bufferId = -1L,
            row = 0,
            colUtf16 = 0,
            bufferVersion = null,
            payload = null,
        )

        fun parse(json: String?): LspAnswer? {
            if (json.isNullOrEmpty()) return null
            return try {
                val root = JSONObject(json)
                LspAnswer(
                    id = root.optLong("id", 0L),
                    kind = when (root.jsonStringOrNull("kind")) {
                        "completion" -> LspRequestKind.Completion
                        "hover" -> LspRequestKind.Hover
                        "definition" -> LspRequestKind.Definition
                        else -> null
                    },
                    state = when (root.jsonStringOrNull("state")) {
                        "done" -> LspRequestState.Done
                        "pending" -> LspRequestState.Pending
                        "timeout" -> LspRequestState.Timeout
                        "unavailable" -> LspRequestState.Unavailable
                        else -> LspRequestState.Cancelled
                    },
                    bufferId = root.optLong("buffer_id", -1L),
                    row = root.optInt("row", 0),
                    colUtf16 = root.optInt("col_utf16", 0),
                    bufferVersion =
                        if (root.isNull("buffer_version")) null else root.optLong("buffer_version"),
                    payload = root.optJSONObject("payload"),
                )
            } catch (_: org.json.JSONException) {
                null
            }
        }
    }
}

/**
 * Whether this answer still describes the text in front of the user, for the
 * two kinds whose payload carries *positions* — hover's range and a
 * definition's target.
 *
 * The bridge echoes `row`, `col_utf16` and `buffer_version` for exactly this:
 * an answer dated against text we have since edited points at columns that
 * have moved, and one asked at a caret the user has left is about a symbol
 * they are no longer looking at. Neither is repairable here — only the server
 * knows where those positions belong now — so a late answer is dropped rather
 * than approximated.
 */
fun LspAnswer.describes(bufferId: Long, bufferVersion: Long, row: Int, col: Int): Boolean =
    state == LspRequestState.Done &&
        this.bufferId == bufferId &&
        this.bufferVersion == bufferVersion &&
        this.row == row &&
        this.colUtf16 == col

/**
 * The same question for a completion list, which has a weaker rule on purpose.
 *
 * A completion answer is asked at the start of a word and consumed while the
 * word is still being typed, so demanding the buffer version match would throw
 * away every list the user typed through — which is every list. Zed instead
 * keeps the menu and re-filters it against the growing query
 * (crates/editor/src/completions.rs:833-846), and re-asks only when the server
 * said `is_incomplete`. So what has to hold is that the caret is still on the
 * same row of the same buffer, at or after where the question was asked, with
 * nothing but word characters typed in between: anything else — a newline, a
 * space, an edit above — means the list is about somewhere else.
 */
fun LspAnswer.stillDescribes(bufferId: Long, row: Int, col: Int, line: String): Boolean {
    if (state != LspRequestState.Done) return false
    if (this.bufferId != bufferId || this.row != row) return false
    if (colUtf16 > col || colUtf16 > line.length || col > line.length) return false
    for (i in colUtf16 until col) if (!isCompletionWordChar(line[i])) return false
    return true
}

/**
 * Start a request and poll it to rest, cancelling it if this coroutine is.
 *
 * Never touches the main thread: the start, every version read and the payload
 * read all run on [Dispatchers.Default], and only the returned value crosses
 * back. The cancel in the `finally` is what frees the engine's slot and tells
 * the server to stop working on an answer nobody will read — the bridge is
 * explicit that a popup which closes MUST do this.
 */
internal suspend fun requestLsp(
    kind: LspRequestKind,
    bufferId: Long,
    row: Int,
    colUtf16: Int,
): LspAnswer? {
    val id = withContext(Dispatchers.Default) {
        when (kind) {
            LspRequestKind.Completion ->
                CoreBridge.lspRequestCompletion(bufferId, row.toLong(), colUtf16.toLong())
            LspRequestKind.Hover ->
                CoreBridge.lspRequestHover(bufferId, row.toLong(), colUtf16.toLong())
            LspRequestKind.Definition ->
                CoreBridge.lspRequestDefinition(bufferId, row.toLong(), colUtf16.toLong())
        }
    }
    // 0 cannot be a live id — it is the value `lspRequestVersion` uses for one
    // the engine has forgotten, so an id of 0 could never be polled — and a
    // negative id is the bridge's failure convention.
    if (id <= 0L) return null
    var settled = false
    try {
        // One hop for the whole wait, not one per tick: the loop stays on
        // Default and the caller gets the answer when it lands.
        return withContext(Dispatchers.Default) {
            while (true) {
                val answer = when (CoreBridge.lspRequestVersion(id)) {
                    // Forgotten: superseded, cancelled, or its buffer closed.
                    // Nothing more is coming, exactly like a search whose
                    // version reads 0.
                    0L -> LspAnswer.forgotten(id)
                    2L -> LspAnswer.parse(CoreBridge.lspRequestResult(id)) ?: LspAnswer.forgotten(id)
                    else -> null
                }
                if (answer != null) {
                    settled = true
                    return@withContext answer
                }
                delay(REQUEST_POLL_MILLIS)
            }
            // The loop only ends through the return above.
            @Suppress("UNREACHABLE_CODE")
            LspAnswer.forgotten(id)
        }
    } finally {
        if (!settled) {
            withContext(NonCancellable + Dispatchers.Default) { CoreBridge.lspRequestCancel(id) }
        }
    }
}

// ---- the completion payload -------------------------------------------------

/**
 * LSP's `CompletionItemKind`, and the letter Zed puts in front of a row for it
 * (`completion_kind_letter`, code_context_menus.rs:1724-1753).
 *
 * The letter is the whole badge: Zed colours it from the syntax theme, which
 * this side reaches only by engine style id, so the letter here is drawn in
 * `text.muted` — the shape survives, the tint does not.
 */
enum class CompletionKind(internal val wire: String, val letter: String?) {
    Text("text", "t"),
    Method("method", "m"),
    Function("function", "f"),
    Constructor("constructor", "C"),
    Field("field", "f"),
    Variable("variable", "v"),
    Class("class", "c"),
    Interface("interface", "i"),
    Module("module", "M"),
    Property("property", "p"),
    Unit("unit", "u"),
    Value("value", "v"),
    Enum("enum", "e"),
    Keyword("keyword", "k"),
    Snippet("snippet", "s"),
    ColorKind("color", "c"),
    File("file", "F"),
    Reference("reference", "r"),
    Folder("folder", "D"),
    EnumMember("enum_member", "e"),
    Constant("constant", "c"),
    Struct("struct", "S"),
    Event("event", "E"),
    Operator("operator", "o"),
    TypeParameter("type_parameter", "T"),
    ;

    companion object {
        private val BY_WIRE = CompletionKind.entries.associateBy { it.wire }

        fun from(name: String?): CompletionKind? = name?.let(BY_WIRE::get)
    }
}

/** One row of the menu, as `lspRequestResult`'s completion payload lists it. */
data class CompletionItem(
    val label: String,
    val detail: String?,
    val kind: CompletionKind?,
    /** Never null on the wire: it falls back to the label. */
    val insertText: String,
    /** True when [insertText] carries `${1:placeholder}` syntax. */
    val isSnippet: Boolean,
    /** What the query is matched against; falls back to the label, not the insert. */
    val filterText: String,
    /** The server's own ordering key; falls back to the label. */
    val sortText: String,
    val documentation: String?,
    val deprecated: Boolean,
    val preselect: Boolean,
    /**
     * The range [insertText] replaces, or null meaning "the UI picks the word
     * around the caret" — Zed's behaviour, and what [CompletionMenuState]
     * does with it.
     */
    val edit: LspRange?,
)

/** A whole completion answer. */
class CompletionList(
    /** Re-ask after the next character: the server truncated this list. */
    val isIncomplete: Boolean,
    val items: List<CompletionItem>,
) {
    companion object {
        val EMPTY = CompletionList(isIncomplete = false, items = emptyList())

        fun parse(payload: JSONObject?): CompletionList {
            if (payload == null) return EMPTY
            val array = payload.optJSONArray("items")
            val count = array?.length() ?: 0
            val items = ArrayList<CompletionItem>(count)
            for (i in 0 until count) {
                val entry = array?.optJSONObject(i) ?: continue
                val label = entry.optString("label", "")
                if (label.isEmpty()) continue
                items.add(
                    CompletionItem(
                        label = label,
                        detail = entry.jsonStringOrNull("detail"),
                        kind = CompletionKind.from(entry.jsonStringOrNull("kind")),
                        insertText = entry.jsonStringOrNull("insert_text") ?: label,
                        isSnippet = entry.optBoolean("is_snippet", false),
                        filterText = entry.jsonStringOrNull("filter_text") ?: label,
                        sortText = entry.jsonStringOrNull("sort_text") ?: label,
                        documentation = entry.jsonStringOrNull("documentation"),
                        deprecated = entry.optBoolean("deprecated", false),
                        preselect = entry.optBoolean("preselect", false),
                        edit = parseLspRange(entry.optJSONObject("edit")),
                    )
                )
            }
            return CompletionList(payload.optBoolean("is_incomplete", false), items)
        }
    }
}

// ---- what the query is, and what matches it ---------------------------------

/**
 * A character that may stand inside an identifier.
 *
 * Zed asks the language's own char classifier (`CharKind::Word` under
 * `CharScopeContext::Completion`); the classifier lives in the engine and is
 * not on this bridge, so this is the classifier's default: letters, digits and
 * `_`. Every language we have a server for agrees about those.
 */
internal fun isCompletionWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

/**
 * Characters that open a menu without a word being typed.
 *
 * Zed asks the buffer for the server's own `completion_triggers` (the
 * `triggerCharacters` from `initialize`); the frozen bridge does not carry
 * them, so this is the set the four servers we ship for actually declare —
 * `.` everywhere, `:` and `>` for `::` and `->`. Being wrong here is cheap in
 * one direction only: an extra trigger costs one request that answers "no
 * completions", a missing one costs a menu that never opens, so the set errs
 * towards asking.
 */
private val COMPLETION_TRIGGERS = setOf('.', ':', '>')

/**
 * Zed's `completion_query` (completions.rs:833-846): the identifier already
 * typed in front of the caret, or `""` when the caret does not sit inside one.
 *
 * `""` is a real state, not a failure — it is where the menu stands right
 * after a trigger character, with everything the server offered on show.
 */
internal fun completionQuery(line: String, col: Int): String {
    val end = col.coerceIn(0, line.length)
    var start = end
    while (start > 0 && isCompletionWordChar(line[start - 1])) start--
    return line.substring(start, end)
}

/**
 * Zed's `split_words` (completions.rs:1546-1560), which is what makes a query
 * starting a *word inside* the candidate rank above one that merely appears in
 * it: boundaries are camelCase humps and the first alphanumeric after
 * anything that is not one.
 */
internal fun splitWords(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val words = ArrayList<String>(4)
    var start = 0
    for (i in 1..text.length) {
        val boundary = i == text.length ||
            (!text[i - 1].isUpperCase() && text[i].isUpperCase()) ||
            (!text[i - 1].isLetterOrDigit() && text[i].isLetterOrDigit())
        if (boundary && i > start) {
            words.add(text.substring(start, i))
            start = i
        }
    }
    return words
}

/**
 * How well [query] matches [candidate], or null when it does not match at all.
 *
 * A subsequence match scored the way `fuzzy::match_strings` scores one: every
 * matched character earns, a match immediately after the previous one earns
 * more (contiguity), and one at the start of a word inside the candidate earns
 * more still. A shorter candidate wins a tie, so `push` beats `push_within`.
 *
 * [smartCase] is Zed's: a query with an uppercase letter in it is matched
 * case-sensitively, a lower-case query matches either case.
 */
internal fun matchScore(candidate: String, query: String, smartCase: Boolean): Double? {
    if (query.isEmpty()) return 1.0
    if (query.length > candidate.length) return null
    var score = 0.0
    var at = 0
    var previous = -2
    for (wanted in query) {
        var found = -1
        var i = at
        while (i < candidate.length) {
            val here = candidate[i]
            val same = if (smartCase) here == wanted else here.lowercaseChar() == wanted.lowercaseChar()
            if (same) {
                found = i
                break
            }
            i++
        }
        if (found < 0) return null
        score += 1.0
        if (found == previous + 1) score += 0.8
        if (found == 0 || !isCompletionWordChar(candidate[found - 1]) ||
            (candidate[found].isUpperCase() && !candidate[found - 1].isUpperCase())
        ) {
            score += 0.6
        }
        previous = found
        at = found + 1
    }
    // Longer candidates dilute the same number of hits, which is what keeps an
    // exact-length match at the top of a list of prefixes of itself.
    return score / (1.0 + 0.01 * (candidate.length - query.length))
}

/**
 * The rows the menu shows for [query], in the order Zed shows them.
 *
 * Zed's `sort_string_matches` (code_context_menus.rs:1513-1607) sorts into two
 * tiers and then by a stack of keys. Kept here: the tiers (a candidate none of
 * whose words *start* with the query's first character is demoted wholesale),
 * an exact filter-text match first, then score, then the server's own
 * `sort_text`, then the label. Dropped: the snippet ordering setting and the
 * exact-case tiebreak, which need state this side does not have.
 */
fun filterCompletions(items: List<CompletionItem>, query: String): List<CompletionItem> {
    val smartCase = query.any(Char::isUpperCase)
    val first = query.firstOrNull()?.lowercaseChar()
    val scored = ArrayList<Triple<CompletionItem, Boolean, Double>>(items.size)
    for (item in items) {
        val score = matchScore(item.filterText, query, smartCase) ?: continue
        val startsAWord = first == null || splitWords(item.filterText).any {
            it.firstOrNull()?.lowercaseChar() == first
        }
        scored.add(Triple(item, startsAWord, score))
    }
    scored.sortWith(
        compareBy<Triple<CompletionItem, Boolean, Double>> { !it.second }
            .thenBy { if (it.first.filterText == query) 0 else 1 }
            .thenByDescending { it.third }
            .thenBy { it.first.sortText }
            .thenBy { it.first.label }
    )
    return scored.map { it.first }
}

/**
 * Plain text and a caret offset from an LSP snippet body.
 *
 * There is no snippet engine here — tabstops need a mode of their own, and
 * that is not P5-3 — so `${1:value}` becomes `value`, a bare `$1` becomes
 * nothing, and the caret lands on the *first* tabstop rather than at the end,
 * which is where the user would have to click anyway. `\$` is a literal `$`.
 */
internal fun expandSnippet(body: String): Pair<String, Int> {
    val out = StringBuilder(body.length)
    var caret = -1
    var i = 0
    while (i < body.length) {
        val char = body[i]
        if (char == '\\' && i + 1 < body.length && body[i + 1] == '$') {
            out.append('$')
            i += 2
            continue
        }
        if (char != '$') {
            out.append(char)
            i++
            continue
        }
        // `${n:default}` — the default is what the user gets, selected in Zed
        // and merely typed here.
        if (i + 1 < body.length && body[i + 1] == '{') {
            val close = body.indexOf('}', i + 2)
            if (close < 0) {
                out.append(char)
                i++
                continue
            }
            val inner = body.substring(i + 2, close)
            val colon = inner.indexOf(':')
            val number = (if (colon < 0) inner else inner.take(colon)).toIntOrNull()
            if (number == null) {
                out.append(char)
                i++
                continue
            }
            if (caret < 0) caret = out.length
            if (colon >= 0) out.append(inner.substring(colon + 1))
            i = close + 1
            continue
        }
        var digits = i + 1
        while (digits < body.length && body[digits].isDigit()) digits++
        if (digits == i + 1) {
            out.append(char)
            i++
            continue
        }
        if (caret < 0) caret = out.length
        i = digits
    }
    return out.toString() to (if (caret < 0) out.length else caret)
}

// ---- where the menu goes ----------------------------------------------------

/** Where a caret-anchored popup landed, in pane-local pixels. */
data class MenuPlacement(
    val x: Float,
    val y: Float,
    val height: Float,
    /** True when it opens upward: its bottom edge is the top of the caret's row. */
    val above: Boolean,
)

/**
 * Zed's own above-or-below-the-line arithmetic
 * (crates/editor/src/element.rs:4099-4170), with one bound changed — and this
 * is the deviation P5-3 is *required* to make.
 *
 * Zed measures the room below the caret against the bottom of the editor's
 * text bounds. On a phone the bottom of the pane is not the bottom of what can
 * be seen: the soft keyboard sits over it, and so does the action row that
 * rides above the keyboard. A menu opened downward off a caret anywhere in the
 * lower half of the file would be drawn underneath the IME and be, exactly,
 * invisible. So [areaBottom] is the top of whatever covers the pane, and the
 * flip is measured against that. Everything else — flip only when the wanted
 * height does not fit below *and* there is more room above, shrink to what
 * there is, fall back to the roomier side when neither fits the minimum, snap
 * the right edge inside the pane — is Zed's, unchanged.
 *
 * [caretTop] is the top of the caret's display row; the menu opens from the
 * *bottom* of that row when it opens downward, and rises from its top when it
 * flips, so it never covers the line the user is typing on.
 */
fun placeMenuAtCaret(
    caretX: Float,
    caretTop: Float,
    lineHeight: Float,
    wantedWidth: Float,
    wantedHeight: Float,
    minHeight: Float,
    areaWidth: Float,
    areaTop: Float,
    areaBottom: Float,
): MenuPlacement {
    val targetY = caretTop + lineHeight
    val bottomWhenFlipped = caretTop
    val availableAbove = (bottomWhenFlipped - areaTop).coerceAtLeast(0f)
    val availableBelow = (areaBottom - targetY).coerceAtLeast(0f)
    var above = wantedHeight > availableBelow && availableAbove > availableBelow
    var height = min(wantedHeight, if (above) availableAbove else availableBelow)
    // What "too small to bother" means, for a menu that is itself smaller than
    // that. Zed never meets this case — it asks for its maximum height and
    // lets the popover shrink to its contents — but here the wanted height is
    // the contents, and a two-row menu must not be treated as a squeezed
    // twelve-row one and flipped to make room it does not need.
    val needed = min(minHeight, wantedHeight)
    if (height < needed) {
        // Neither side fits what the menu needs to be worth showing: take the
        // side that fits the minimum, else the roomier one, clamped to it.
        if (availableBelow >= needed) {
            above = false
            height = needed
        } else if (availableAbove >= needed) {
            above = true
            height = needed
        } else if (availableAbove > availableBelow) {
            above = true
            height = availableAbove
        } else {
            above = false
            height = availableBelow
        }
    }
    val x = min(caretX, max(areaWidth - wantedWidth, 0f)).coerceAtLeast(0f)
    val y = if (above) bottomWhenFlipped - height else targetY
    return MenuPlacement(x = x, y = y, height = height, above = above)
}

// ---- the menu itself --------------------------------------------------------

/** A request the effect below is to make: a position, and a reason to re-ask. */
private data class CompletionRequest(val row: Int, val col: Int, val generation: Int)

/**
 * The completion menu's state: when it is open, what it holds, and what the
 * caret has done to it since.
 *
 * Zed opens the menu on *input* — a trigger character, or a character that can
 * stand in an identifier — and never on a bare caret move
 * (`is_completion_trigger`, completions.rs:1513-1539). That is why this needs
 * telling what was typed rather than watching the caret alone: a menu that
 * opened when the user pressed `→` into the middle of a word would be a menu
 * nobody asked for.
 */
@Stable
class CompletionMenuState internal constructor(private val editor: EditorState) {

    /** Everything the server offered, before the query narrowed it. */
    private var offered: CompletionList = CompletionList.EMPTY

    var rows: List<CompletionItem> by mutableStateOf(emptyList())
        private set

    var selected: Int by mutableIntStateOf(0)
        private set

    /** The row the menu belongs to, and the column its query starts at. */
    private var anchorRow = -1
    private var anchorCol = 0

    private var request: CompletionRequest? by mutableStateOf(null)
    private var generation = 0

    /** Set while a buffer has answered `unavailable`; see [UNAVAILABLE_QUIET_MILLIS]. */
    internal var unavailableSince: Long = 0L

    val isOpen: Boolean get() = rows.isNotEmpty()

    /**
     * Whether asking is worth a request right now. False only for a little
     * while after a buffer said `unavailable`, and never for an explicit
     * invoke, which is the user asking a question this cannot answer with a
     * cached "no".
     */
    private fun isQuiet(): Boolean =
        unavailableSince != 0L && now() - unavailableSince < UNAVAILABLE_QUIET_MILLIS

    /** A monotonic clock, not the wall one: this is a duration, not a date. */
    private fun now(): Long = System.nanoTime() / 1_000_000L

    /** Text just typed by the user, as [EditorState.onTextTyped] reports it. */
    internal fun onTyped(text: String) {
        // Anything but a single character is a paste, an autocorrection or a
        // swipe: Zed's trigger test takes the first char and gives up if there
        // is a second (completions.rs:1521-1528).
        if (text.length != 1) {
            dismiss()
            return
        }
        val char = text[0]
        if (isOpen) return
        if (!isCompletionWordChar(char) && char !in COMPLETION_TRIGGERS) return
        if (isQuiet()) return
        open()
    }

    /** Zed's `editor::ShowCompletions` — `ctrl-space`, and the IME row's key. */
    fun showCompletions(): Boolean {
        if (editor.sessionOrNull == null) return false
        unavailableSince = 0L
        open()
        return true
    }

    private fun open() {
        if (editor.extraCarets.isNotEmpty()) return
        val row = editor.cursorRow
        val col = editor.cursorCol
        val query = completionQuery(editor.line(row), col)
        anchorRow = row
        anchorCol = col - query.length
        generation++
        request = CompletionRequest(row, col, generation)
    }

    /**
     * The caret moved or the buffer changed. Either the query grew and the
     * list is re-filtered against it, or the menu is about somewhere else now
     * and closes.
     */
    internal fun caretMoved() {
        if (!isOpen && request == null) return
        if (editor.extraCarets.isNotEmpty() || editor.hasSelection) {
            dismiss()
            return
        }
        if (editor.cursorRow != anchorRow) {
            dismiss()
            return
        }
        val line = editor.line(anchorRow)
        val col = editor.cursorCol
        if (col < anchorCol || anchorCol > line.length || col > line.length) {
            dismiss()
            return
        }
        val query = line.substring(anchorCol, col)
        if (query.any { !isCompletionWordChar(it) }) {
            dismiss()
            return
        }
        if (!isOpen) return
        // Zed re-asks while the server says its list was truncated
        // (`is_incomplete`, completions.rs:51), and filters locally otherwise.
        if (offered.isIncomplete) {
            generation++
            request = CompletionRequest(anchorRow, col, generation)
        }
        show(filterCompletions(offered.items, query))
    }

    private fun show(filtered: List<CompletionItem>) {
        rows = filtered
        // Zed's `preselect` picks the row the server wants highlighted; with
        // none, the best match is the first one.
        selected = filtered.indexOfFirst { it.preselect }.coerceAtLeast(0)
        // Nothing matches and the server said it had told us everything: there
        // is nothing to wait for either. A truncated list is the exception —
        // the answer to this query may be in the half we were not sent.
        if (filtered.isEmpty() && !offered.isIncomplete) request = null
    }

    /** Adopt a settled answer. */
    private fun adopt(list: CompletionList, query: String) {
        offered = list
        show(filterCompletions(list.items, query))
    }

    /** Zed's `ContextMenuNext` / `ContextMenuPrevious`, which wrap at both ends. */
    fun moveSelection(delta: Int): Boolean {
        if (!isOpen) return false
        val size = rows.size
        selected = ((selected + delta) % size + size) % size
        return true
    }

    /** Zed's `editor::ConfirmCompletion`, at the selected row or at [index]. */
    fun accept(index: Int = selected): Boolean {
        val item = rows.getOrNull(index) ?: return false
        if (anchorRow < 0) return false
        val line = editor.line(anchorRow)
        // A null `edit` means "the UI picks the word around the caret", which
        // is Zed's own fallback: the word start to the caret
        // (completions.rs:468-480).
        val served = item.edit ?: LspRange(
            row = anchorRow,
            colUtf16 = anchorCol.coerceIn(0, line.length),
            endRow = anchorRow,
            endColUtf16 = editor.cursorCol.coerceIn(0, line.length),
        )
        // The server's range was measured when it answered, and the user has
        // very likely typed since — the list filters locally, so a whole word
        // can be typed against one answer. Zed clamps the end *forward* to the
        // caret for exactly this (`process_completion_for_edit`,
        // editor.rs:11280-11286); without it the characters typed after the
        // request survive the replacement and get glued on: "s" + "tr" then
        // accepting `strlen` leaves `strlentr`. Only ever extended, never
        // shrunk — a range that already reaches past the caret is the
        // insert-and-replace case the bridge documents.
        val endsBeforeCaret = served.endRow < editor.cursorRow ||
            (served.endRow == editor.cursorRow && served.endColUtf16 < editor.cursorCol)
        val range = if (endsBeforeCaret) {
            served.copy(endRow = editor.cursorRow, endColUtf16 = editor.cursorCol)
        } else {
            served
        }
        val (text, caret) =
            if (item.isSnippet) expandSnippet(item.insertText) else item.insertText to item.insertText.length
        dismiss()
        return editor.replaceRange(range, text, caret)
    }

    /** Zed's `editor::Cancel` where a menu is open: the menu goes, nothing else. */
    fun dismiss(): Boolean {
        val was = isOpen || request != null
        offered = CompletionList.EMPTY
        rows = emptyList()
        selected = 0
        anchorRow = -1
        request = null
        return was
    }

    /**
     * The polling half, run from the pane. Restarts whenever a new request is
     * asked for, which cancels the previous one — at the engine, in
     * [requestLsp]'s `finally`, and at the server behind it.
     */
    @Composable
    internal fun Poller() {
        val pending = request
        LaunchedEffect(pending) {
            if (pending == null) return@LaunchedEffect
            val bufferId = editor.sessionOrNull?.id ?: return@LaunchedEffect
            delay(COMPLETION_DEBOUNCE_MILLIS)
            val answer = requestLsp(
                LspRequestKind.Completion,
                bufferId,
                pending.row,
                pending.col,
            ) ?: return@LaunchedEffect
            if (answer.state == LspRequestState.Unavailable) {
                unavailableSince = now()
                dismiss()
                return@LaunchedEffect
            }
            val row = editor.cursorRow
            val col = editor.cursorCol
            if (row != anchorRow) return@LaunchedEffect
            val line = editor.line(row)
            if (!answer.stillDescribes(bufferId, row, col, line)) return@LaunchedEffect
            unavailableSince = 0L
            adopt(CompletionList.parse(answer.payload), line.substring(anchorCol, col))
        }
    }
}

@Composable
internal fun rememberCompletionMenu(state: EditorState): CompletionMenuState {
    val menu = remember(state) { CompletionMenuState(state) }
    // Typing is what opens the menu, and only the state knows what was typed —
    // a keystroke reaches the buffer through three doors (hardware key, IME
    // commit, IME pair character) and they meet inside [EditorState].
    DisposableEffect(menu) {
        state.onTextTyped = menu::onTyped
        onDispose { state.onTextTyped = null }
    }
    // Everything else the caret does: growing the query, leaving the word,
    // undoing the edit that opened the menu. Watched through [snapshotFlow]
    // rather than passed as effect keys: keys are read during composition,
    // and this helper returns a value, so it composes in the pane's own scope
    // — keys here recomposed the whole pane on every caret move.
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.cursorRow, state.cursorCol, state.revision) }
            .collect { menu.caretMoved() }
    }
    menu.Poller()
    return menu
}

/**
 * The menu, anchored to the caret and kept clear of the soft keyboard.
 *
 * [caretX] and [caretTop] are pane-local pixels of the caret's display row;
 * [areaBottom] is the first pixel the pane cannot draw on — the top of the
 * IME, or of the action row riding above it. See [placeMenuAtCaret].
 */
@Composable
internal fun CompletionPopup(
    menu: CompletionMenuState,
    caretX: Float,
    caretTop: Float,
    lineHeight: Float,
    areaWidth: Float,
    areaBottom: Float,
    onAccepted: () -> Unit,
) {
    val rows = menu.rows
    if (rows.isEmpty()) return
    val theme = LocalZedTheme.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    LaunchedEffect(menu.selected, rows.size) {
        // No animation, here as everywhere: the list steps, it does not glide.
        if (menu.selected in rows.indices) listState.scrollToItem(menu.selected)
    }
    // Zed's menu has a minimum width and a window to overflow into; a pane
    // narrower than the menu has neither, so the menu is what gives.
    val widthPx = with(density) { min(MENU_WIDTH.toPx(), areaWidth) }
    val placement = with(density) {
        val rowPx = MENU_ROW_HEIGHT.toPx()
        val padding = MENU_Y_PADDING.toPx() * 2f
        placeMenuAtCaret(
            caretX = caretX,
            caretTop = caretTop,
            lineHeight = lineHeight,
            wantedWidth = widthPx,
            wantedHeight = min(rows.size, MENU_MAX_ROWS) * rowPx + padding,
            minHeight = MENU_MIN_ROWS * rowPx + padding,
            areaWidth = areaWidth,
            areaTop = 0f,
            areaBottom = areaBottom,
        )
    }
    val shape = RoundedCornerShape(8.dp)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .offset { IntOffset(placement.x.roundToInt(), placement.y.roundToInt()) }
            .width(with(density) { widthPx.toDp() })
            .heightIn(max = with(density) { placement.height.toDp() })
            // Zed's `elevation_2`: an elevated surface, `rounded_lg` 8px and a
            // 1px border in `border.variant` (ui/src/styles/elevation.rs).
            .clip(shape)
            .background(theme.color("elevated_surface.background"))
            .border(1.dp, theme.color("border.variant"), shape)
            .padding(vertical = MENU_Y_PADDING),
    ) {
        itemsIndexed(rows) { index, item ->
            CompletionRow(
                item = item,
                isSelected = index == menu.selected,
                onClick = {
                    menu.accept(index)
                    onAccepted()
                },
            )
        }
    }
}

@Composable
private fun CompletionRow(item: CompletionItem, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ROW_HEIGHT)
            // Zed's inset ListItem: 4px of surface either side, the row itself
            // `rounded_sm` (list_item.rs:309, 364).
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    isSelected -> theme.color("element.selected", Color.Transparent)
                    hovered -> theme.color("element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = item.kind?.letter.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Zed strikes a deprecated completion through and mutes it
            // (code_context_menus.rs:1046-1054).
            textDecoration = if (item.deprecated) TextDecoration.LineThrough else null,
            color = if (item.deprecated) {
                theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        val detail = item.detail ?: item.documentation?.substringBefore('\n')
        if (!detail.isNullOrEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
