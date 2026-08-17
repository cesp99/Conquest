package to.eyed.conquest.code.core

/**
 * Kotlin side of the JNI boundary to the Rust engine (`core/crates/jni-bridge`).
 *
 * Naming contract: each `external` function here maps to a
 * `Java_to_eyed_conquest_code_core_CoreBridge_<name>` symbol in the Rust
 * crate. Keep the two files in sync — this is the only place the two worlds
 * meet.
 *
 * Calls across this boundary must stay coarse-grained: never loop over
 * per-character calls from Kotlin.
 *
 * Error convention: functions returning [Long] return -1 for unknown
 * buffers / invalid arguments (and, for undo/redo, when there is nothing to
 * undo/redo). Functions returning [String]? return null for unknown buffers.
 */
object CoreBridge {
    init {
        System.loadLibrary("conquestcore")
    }

    /**
     * Hands the engine the app's private files directory and brings it up.
     * Call this before anything else touches the bridge.
     *
     * Android runs apps without `$HOME`, which the vendored Zed crates assume
     * exists — a worktree scan panics without one. The engine points `HOME`
     * (and the trash) at this directory.
     *
     * [verboseLogging] raises the engine's log level from Info to Debug, which
     * is where its git and scan diagnostics live. Pass `BuildConfig.DEBUG`: the
     * Rust library cannot tell a debug APK from a release one on its own,
     * because Gradle builds it `--release` for every Android build type.
     */
    external fun initialize(filesDir: String, verboseLogging: Boolean)

    external fun engineVersion(): String

    /** Returns the id of the newly created buffer. */
    external fun createBuffer(initialText: String): Long

    external fun closeBuffer(bufferId: Long): Boolean

    /**
     * Replaces the byte range [start, end) with [text]. Offsets are UTF-8
     * byte offsets and must lie on character boundaries. Returns the new
     * buffer version, or -1 on invalid buffer id or range.
     */
    external fun applyEdit(bufferId: Long, start: Long, end: Long, text: String): Long

    /**
     * Undoes the most recent edit transaction. Returns the new buffer
     * version, or -1 if there was nothing to undo.
     */
    external fun undoBuffer(bufferId: Long): Long

    /**
     * Redoes the most recently undone transaction. Returns the new buffer
     * version, or -1 if there was nothing to redo.
     */
    external fun redoBuffer(bufferId: Long): Long

    /**
     * Monotonic content version, bumped by every edit/undo/redo. Cheap
     * staleness check for cached reads.
     */
    external fun bufferVersion(bufferId: Long): Long

    external fun bufferLineCount(bufferId: Long): Long

    /**
     * Text of rows [firstLine, lastLine) — end-exclusive, clipped to the
     * buffer — joined with '\n' and without a trailing newline. This is the
     * read path the editor should use: fetch only the visible window.
     */
    external fun bufferLines(bufferId: Long, firstLine: Long, lastLine: Long): String?

    /**
     * Assigns a tree-sitter language (grammar name, e.g. "rust") to the
     * buffer and parses it. Returns false for unknown buffers/languages.
     */
    external fun bufferSetLanguage(bufferId: Long, language: String): Boolean

    /**
     * Highlight spans for rows [firstLine, lastLine), flattened as groups
     * of four ints: row, UTF-16 start column, UTF-16 end column, style id
     * (index into the engine's style-name list, mirrored by
     * [to.eyed.conquest.code.ui.editor.SyntaxPalette]). Empty when the
     * buffer has no language; null for unknown buffers.
     */
    external fun bufferHighlights(bufferId: Long, firstLine: Long, lastLine: Long): IntArray?

    /**
     * Byte offset of (row, byte column), clipped to the buffer. -1 for an
     * unknown buffer or negative arguments.
     */
    external fun pointToOffset(bufferId: Long, row: Long, column: Long): Long

    /**
     * (row, byte column) of a byte offset, clipped to the buffer, packed as
     * `(row shl 32) or column`. -1 for an unknown buffer or negative offset.
     */
    external fun offsetToPoint(bufferId: Long, offset: Long): Long

    /**
     * Whole buffer contents. Placeholder-era convenience; real rendering
     * must use [bufferLines].
     */
    external fun bufferText(bufferId: Long): String?

    // -----------------------------------------------------------------------
    // Projects. Opening and expanding are asynchronous: they hand work to the
    // engine's gpui runtime and return at once. Watch [projectVersion] to know
    // when there is something new to read; every other call reads a mirrored
    // snapshot and never waits on the runtime.
    // -----------------------------------------------------------------------

    /** Starts scanning [path] as a project. Returns its id (always > 0). */
    external fun openProject(path: String): Long

    external fun closeProject(projectId: Long): Boolean

    /**
     * Monotonic version of the mirrored worktree snapshot; 0 while there is
     * nothing to show. Poll it to know when to re-read entries.
     */
    external fun projectVersion(projectId: Long): Long

    /** Whether the initial scan has finished. Entries are readable before it. */
    external fun projectScanComplete(projectId: Long): Boolean

    /** Why the project failed to open, or null if it did not fail. */
    external fun projectError(projectId: Long): String?

    /** Display name of the project root; null for an unknown project. */
    external fun projectRootName(projectId: Long): String?

    /**
     * Direct children of a project-relative directory ("" for the root), as a
     * JSON array of objects with `path`, `name`, `is_dir`, `is_ignored`,
     * `is_hidden`, `is_unloaded` and `size`. One call per expanded directory —
     * never one per entry. Unknown projects and unscanned directories give
     * `[]`, never null.
     */
    external fun projectEntries(projectId: Long, dir: String): String

    /**
     * Scans a directory the worktree deferred (gitignored, hidden, or past
     * Zed's `file_scan_depth`). Asynchronous: results show up as a version
     * bump. False if the project or path is unknown.
     */
    external fun expandDirectory(projectId: Long, dir: String): Boolean

    /**
     * Absolute path of a project-relative entry; null if the project is
     * unknown or the path tries to escape the root.
     */
    external fun projectEntryPath(projectId: Long, path: String): String?

    // -----------------------------------------------------------------------
    // Git status. The engine has no git of its own: it runs the one inside the
    // Debian userland, through proot. We know where that lives and the engine
    // must not guess, so [setUserland] is what turns the feature on — and the
    // `play` flavour simply never calls it, leaving every query below
    // answering "nothing to show".
    // -----------------------------------------------------------------------

    /**
     * Tells the engine where proot and the Debian rootfs are. Call once the
     * userland reports [to.eyed.conquest.code.terminal.UserlandState.Ready];
     * never in the `play` flavour, which has no userland to point at.
     *
     * The engine binds [projectsDir] into the guest at its *own* path, so host
     * and guest agree on every path and nothing needs translating.
     */
    external fun setUserland(
        proot: String,
        rootfs: String,
        tmpDir: String,
        projectsDir: String,
    )

    /** Forgets the userland — after the rootfs is deleted. Status goes empty. */
    external fun clearUserland()

    /**
     * Generation counter for a project's git status; 0 while there is nothing
     * to show. Poll it exactly like [projectVersion]. Polling is also what
     * schedules a refresh, so this must be called for status to stay current —
     * it never waits on git.
     */
    external fun gitStatusVersion(projectId: Long): Long

    /**
     * The status map as a JSON object of project-relative path to status
     * (`modified`, `added`, `deleted`, `renamed`, `conflicted`, `untracked`,
     * `ignored`). Ancestor directories of a changed file are included with a
     * rolled-up status, so the panel needs one lookup per row. Reads a cache:
     * never blocks, never null, `{}` when there is nothing to show.
     */
    external fun gitStatus(projectId: Long): String

    // -----------------------------------------------------------------------
    // Settings. The file is JSONC and hand-editable; writes are surgical, so
    // comments survive. All of these touch the filesystem — call them off the
    // main thread.
    // -----------------------------------------------------------------------

    /** Resolved settings as JSON. Falls back to defaults if the file is broken. */
    external fun settings(): String

    /** The settings file's raw JSONC, created with documented defaults on first use. */
    external fun settingsText(): String

    /** Whether the file parses. False means [settings] is showing defaults. */
    external fun settingsAreValid(): Boolean

    /**
     * Sets one setting. [keyPath] is dot-separated
     * (`project_panel.show_ignored`), [valueJson] is JSON (`true`, `18`,
     * `"dark"`). Returns the resolved settings as JSON, or null on failure.
     */
    external fun setSetting(keyPath: String, valueJson: String): String?

    /**
     * Replaces the whole settings file. Returns the resolved settings as JSON,
     * or null if the text doesn't parse — the file is then left untouched.
     */
    external fun setSettingsText(text: String): String?

    /**
     * Fuzzy-matches [query] against the project's files, best first, as a JSON
     * array of objects with `path`, `name`, `positions` (UTF-16 offsets into
     * `path`, for highlighting) and `score`. An empty query lists files rather
     * than matching nothing. Never null. **Blocking** — call it off the main
     * thread.
     */
    external fun projectFindFiles(projectId: Long, query: String, limit: Long): String

    /**
     * Reads a file into a new buffer, choosing the language from its name.
     * Opening the same file twice returns the *same* buffer — a file must
     * never fork into two edit histories. Returns the buffer id, or -1 if the
     * file could not be read. **Blocking** — call it off the main thread.
     */
    external fun openFile(path: String): Long

    /**
     * Bumped when a background reparse lands. The content version doesn't
     * move then, so watch this to know highlight spans are stale.
     */
    external fun bufferHighlightVersion(bufferId: Long): Long

    /**
     * The grammar the buffer is highlighted with ("rust", "markdown"), or
     * null if it has no language.
     */
    external fun bufferLanguage(bufferId: Long): String?

    /**
     * A language's whole editing config as JSON, straight from the grammar's
     * own `config.toml`:
     *
     *     {"name": "Rust", "line_comments": ["// ", "/// "],
     *      "block_comment": {"start": "/*", "end": "*/", "prefix": "* ",
     *                        "tab_size": 1},
     *      "autoclose_before": ";:.,=}])>", "hard_tabs": false,
     *      "tab_size": null, "increase_indent_pattern": null,
     *      "brackets": [{"start": "{", "end": "}", "close": true,
     *                    "surround": true, "newline": true, "not_in": []}]}
     *
     * Null for a grammar we do not carry. One call per language for the life
     * of the process — [to.eyed.conquest.code.ui.editor.EditorLanguage] caches
     * what comes back, and nothing may call this on the typing path.
     */
    external fun languageConfig(language: String): String?

    /**
     * For each byte offset, a bitmask of the bracket pairs live there: bit *i*
     * for pair *i* of [languageConfig]'s `brackets`.
     *
     * This is the half of the language config that is not data. A pair
     * carrying `not_in = ["string", "comment"]` is live or not depending on
     * where the caret sits in the *syntax tree*, and the tree is the engine's:
     * the highlight spans this side caches are style ids over the visible
     * window, they trail the text by a parse, and the character that decides
     * the answer is the one not typed yet.
     *
     * Every bit is set for an unknown buffer, for a buffer with no language,
     * and for a language whose pairs carry no `not_in` at all — which is every
     * plain bracket, so the UI never needs to ask about `(` or `{`.
     *
     * It reparses the buffer when the tree is stale, so it takes every caret's
     * offset in one call and must only be called when a pair character is
     * actually typed — never per keystroke.
     */
    external fun bufferBracketScopes(bufferId: Long, offsets: LongArray): LongArray

    /** Absolute path of the file behind a buffer; null for scratch buffers. */
    external fun bufferPath(bufferId: Long): String?

    /** Whether the buffer has edits not yet written to disk. */
    external fun bufferIsDirty(bufferId: Long): Boolean

    /**
     * Whether the file changed on disk since the buffer last synced with it.
     * Set by the worktree's file watcher; cleared by save or reload. The
     * engine only ever *flags* this — resolving it is the UI's call.
     */
    external fun bufferHasDiskChange(bufferId: Long): Boolean

    /** Whether the file behind the buffer has been deleted from disk. */
    external fun bufferFileDeleted(bufferId: Long): Boolean

    /**
     * Writes the buffer to its file. Returns the version now on disk, or -1
     * if the buffer has no file or the write failed. **Blocking** — call it
     * off the main thread.
     */
    external fun saveBuffer(bufferId: Long): Long

    /**
     * Re-reads the file into the buffer, discarding local edits. Applied as a
     * single undoable edit, so a mistaken reload is recoverable. Returns the
     * new version, or -1. **Blocking** — call it off the main thread.
     */
    external fun reloadBuffer(bufferId: Long): Long

    // -----------------------------------------------------------------------
    // Search. Both searches take the same options object, as JSON, so one
    // search bar can drive either without reshaping its state:
    //
    //     {"query": "needle", "regex": false, "case_sensitive": false,
    //      "whole_word": false, "include_ignored": false,
    //      "include_globs": [], "exclude_globs": []}
    //
    // Every field may be omitted; the last three are project-search only.
    // [SearchQuery] builds it — nothing should be spelling this out by hand.
    //
    // `whole_word` means the same thing for every kind of query: a hit counts
    // only when neither neighbouring character is a word character
    // (alphanumeric or '_'). A regex is filtered on where its match landed,
    // never rewritten, so `foo|bar` and `\w+` obey the toggle like everything
    // else does.
    //
    // Buffer search answers on the calling thread: it is one pass over a rope,
    // which is what lets the search bar re-run it on every keystroke. Project
    // search cannot answer at all — it reads thousands of files — so it runs
    // on an engine thread and publishes a counter to poll, exactly like
    // [gitStatusVersion].
    //
    // Project search silently skips four kinds of file: ones it cannot read,
    // ones over 4 MiB, ones holding a NUL byte anywhere, and ones that are not
    // valid UTF-8. They still count towards `files_searched`, so a UI that
    // says "searched 400 of 400" is telling the truth about the walk — it just
    // cannot promise a hit inside a 5 MB log or a Latin-1 file.
    // -----------------------------------------------------------------------

    /**
     * Why [queryJson] will not compile, or null if it will. Ask this to
     * explain a half-typed regex instead of silently showing no results.
     *
     * It compiles the query to find out, which a pathological pattern can drag
     * out to tens of milliseconds — so ask it when a search has *failed*, not
     * on every keystroke beside the search itself, which compiles the query
     * again. **Off the main thread** if the query is regex.
     */
    external fun searchQueryError(queryJson: String): String?

    /**
     * Every match in a buffer, flattened: element 0 is how many matches the
     * buffer holds in all, and the rest are groups of four — byte start, byte
     * end, row, byte column. When the total exceeds the groups present,
     * [limit] bit and the UI can still say "3 of 12 000".
     *
     * Null for an unknown buffer or a query that doesn't compile; ask
     * [searchQueryError] which. Wrapped by [BufferSearch].
     *
     * One pass over the whole buffer, so it costs what the buffer is big —
     * a couple of milliseconds at 100k lines. Fine on the keystroke path for
     * an ordinary file; **off the main thread** for a generated one.
     */
    external fun bufferSearch(bufferId: Long, queryJson: String, limit: Long): LongArray?

    /**
     * Starts searching a project. Returns a search id to poll with, or -1 if
     * the project is unknown or the query doesn't compile. Returns at once —
     * it only compiles the query and starts a thread.
     *
     * A project still being scanned is neither of those failures: the search
     * starts, reports [ProjectSearchState.Scanning] until the scan lands, and
     * then searches the whole tree. Results are never reported over a partly
     * scanned project, so `done` always means done over all of it.
     *
     * Starting a search cancels whatever was already running for that project,
     * so there is only ever one live id per project.
     */
    external fun projectSearchStart(projectId: Long, queryJson: String): Long

    /**
     * Generation counter for a search. Non-zero from the moment
     * [projectSearchStart] returns, so 0 means one thing only: an id the
     * engine has forgotten. Poll it exactly like [projectVersion].
     */
    external fun projectSearchVersion(searchId: Long): Long

    /**
     * Everything a search has found from [fromFile] onwards, as JSON — see
     * [ProjectSearchResults] for the shape. Results only grow, so a caller
     * holding `n` files passes `n` and gets what it is missing. Never null.
     *
     * Costs what it hands back: the engine publishes every 100 ms, so one poll
     * can carry megabytes of JSON to serialize here and parse on the Kotlin
     * side. **Call it off the main thread** — poll [projectSearchVersion],
     * which is a single load, and only read when it moves.
     */
    external fun projectSearchResults(searchId: Long, fromFile: Long): String

    /**
     * Stops a search and forgets it. False if the id is already gone. Also how
     * its results are freed: the engine holds the last search per project
     * until this is called or the project closes, which for a big result set
     * is megabytes — so a panel that closes must cancel.
     */
    external fun projectSearchCancel(searchId: Long): Boolean
}
