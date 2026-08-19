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
     * The symbol path containing the caret — Zed's breadcrumbs after the
     * file name — as a JSON array of strings, outermost first ("impl Foo",
     * "fn bar"). Empty array when the buffer has no language or the caret
     * sits outside every symbol; null for unknown buffers. The column is
     * UTF-16, like every caret the UI holds. Reads the last parsed tree, so
     * the answer can be one highlight-worker round-trip stale.
     */
    external fun bufferOutlinePath(bufferId: Long, row: Long, colUtf16: Long): String?

    /**
     * Every outline item in the buffer, in source order — the rows of Zed's
     * outline picker — as a JSON array of
     * `{label, depth, row, col_utf16, end_row}`, where row/col are the
     * *item's* start (the caret target Zed confirms onto, outline.rs:417-425)
     * and end_row closes its extent. Empty array when the buffer has no
     * language; null for unknown buffers. Same staleness contract as
     * [bufferOutlinePath].
     */
    external fun bufferOutline(bufferId: Long): String?

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

    /**
     * Everything the git panel draws, as JSON — see [GitPanelState] for the
     * shape. It is the *same* `git status` run [gitStatus] reads, unreduced:
     * one process serves both, and [gitStatusVersion] is the counter for both.
     *
     * Never blocks, never null. `scanned` is false until a run has completed,
     * which is how "nothing changed" is told from "not asked yet".
     */
    external fun gitChanges(projectId: Long): String

    /**
     * Stages every path in [pathsJson] (a JSON array of project-relative
     * paths), deletions included. Returns null when it worked, and git's own
     * message when it did not.
     *
     * **Blocking** — it waits for a process inside the Linux userland, which is
     * tens of milliseconds at best. Call it off the main thread.
     */
    external fun gitStage(projectId: Long, pathsJson: String): String?

    /** Takes every path back out of the index. **Blocking**; see [gitStage]. */
    external fun gitUnstage(projectId: Long, pathsJson: String): String?

    /**
     * **Destructive.** Throws away every uncommitted change to those paths.
     *
     * A path the last commit has is restored to what the commit holds — index
     * and worktree both. A path it does not have (untracked, staged-new, or the
     * new name of a rename) cannot be restored from anywhere, so it is moved to
     * the app's trash rather than deleted: a mistake here must not be a loss.
     *
     * A row the engine cannot explain is *refused*, with the reason as the
     * return value and nothing touched — a conflict above all, where the
     * obvious git command keeps one side of the merge and reports success.
     *
     * **Confirm with the user first, naming the files.** Nothing below this
     * call asks anything. **Blocking**.
     */
    external fun gitDiscard(projectId: Long, pathsJson: String): String?

    /**
     * Commits what is staged. An empty or whitespace-only message is refused
     * rather than becoming an empty commit, and nothing is staged implicitly —
     * a commit with an empty index comes back with git's own "nothing added to
     * commit". **Blocking**.
     */
    external fun gitCommit(projectId: Long, message: String): String?

    /**
     * Push [branch] to `origin`, setting its upstream when it has none. Null
     * when it worked, the reason when it did not. **Blocking** — network.
     */
    external fun gitPush(projectId: Long, branch: String, setUpstream: Boolean): String?

    /**
     * The working tree's diff as a patch, as JSON. An empty [path] means every
     * changed file. **Blocking** — it runs git.
     */
    external fun gitPatch(projectId: Long, path: String, staged: Boolean): String

    /**
     * A page of commit history, newest first, as JSON — `{"commits":[…]}` or
     * `{"error":…}`. **Blocking** — it runs git.
     */
    external fun gitLog(projectId: Long, limit: Long, skip: Long): String

    /**
     * One commit in full: its fields, its whole message and the paths it
     * touched. **Blocking** — it runs git.
     */
    external fun gitCommitDetails(projectId: Long, sha: String): String

    /**
     * Who commits are recorded as, as JSON `{"name":…,"email":…}`. Both empty
     * when git has none — a fresh Debian guesses `root@localhost.(none)` from
     * the hostname, refuses to use it, and every commit fails until somebody
     * says who they are. **Blocking** — it runs git.
     */
    external fun gitIdentity(projectId: Long): String

    /**
     * Set that identity globally inside the guest. Null when it worked, and
     * the reason when it did not. **Blocking**.
     */
    external fun gitSetIdentity(projectId: Long, name: String, email: String): String?

    /**
     * Generation counter for a buffer's diff hunks; 0 while there is nothing to
     * show. Poll it exactly like [gitStatusVersion] — polling is what schedules
     * the diff, and it never waits for one.
     */
    external fun gitHunksVersion(bufferId: Long): Long

    /**
     * The buffer's difference from the last commit, flattened as groups of four
     * ints: kind (0 added, 1 modified, 2 deleted), first row, end row
     * (exclusive), and how many rows the commit had there. [GitHunk] wraps it.
     *
     * Rows are *buffer* rows and follow unsaved edits: only the base text comes
     * from git, and the diff against it is computed in the engine whenever the
     * buffer moves. A deletion occupies no rows — first and end row are equal,
     * and mark the boundary the rows were removed from.
     *
     * Reads a cache: takes the engine's buffer locks briefly, never runs git
     * and never blocks on one that is running; never null. Empty for a buffer
     * with no file, one outside a repository, and one that matches the commit.
     */
    external fun gitHunks(bufferId: Long): IntArray

    /**
     * Who last touched each run of rows, as JSON — see [BlameLine].
     *
     * The rows are the rows of the file **on disk**. git blames what it can
     * read, and a buffer with unsaved edits has drifted from that.
     *
     * **Blocking and uncached**: it runs git every time. Ask when the user asks
     * for blame, off the main thread — never on a poll loop.
     */
    external fun gitBlame(bufferId: Long): String

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
     * Adds or replaces one `agent_servers` entry — the settings screen's Add
     * Agent form, saved. [name] goes into the file verbatim (never through
     * [setSetting]'s dot-split path, where "my.agent" would nest), and
     * [specJson] is `{"command": …, "args": […], "env": {…}}`. Returns the
     * resolved settings as JSON, or null on failure.
     */
    external fun setAgentServer(name: String, specJson: String): String?

    /**
     * Removes one `agent_servers` entry by name. Removing a name that is not
     * there succeeds — the entry is gone either way. Returns the resolved
     * settings as JSON, or null on failure.
     */
    external fun removeAgentServer(name: String): String?

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

    // -----------------------------------------------------------------------
    // Language servers. The engine has no LSP client of its own — it drives
    // Zed's, over the same proot the git calls go through — so a server is
    // whatever `apt` put in the Debian rootfs. Every call below therefore
    // degrades the way the git ones do: no userland, no server installed, or a
    // language nobody packages one for all report "nothing to show" rather than
    // an error, and the `play` flavour never has one at all.
    //
    // Two shapes, both already on this boundary:
    //
    //  * **Diagnostics are pushed and polled.** The server publishes when it
    //    likes; the engine caches and bumps a counter. Poll [lspVersion] per
    //    project and [bufferDiagnosticsVersion] per open tab, exactly as the
    //    panel polls [projectVersion], and read the JSON only when one moves.
    //    Polling [lspVersion] is also what starts servers for files that were
    //    already open when the userland appeared, so a project view must poll
    //    it.
    //  * **Requests are started and polled.** [lspRequestCompletion] and its
    //    two siblings return an id at once and never block. Poll
    //    [lspRequestVersion] — 1 in flight, 2 settled, 0 forgotten — then read
    //    [lspRequestResult]. Starting a request cancels the previous one *of
    //    the same kind*, which is what a completion popup re-asking on every
    //    keystroke wants; [lspRequestCancel] frees the slot when it closes.
    //
    // Positions are UTF-16 columns in both directions, like every other
    // position here ([bufferHighlights], [bufferOutlinePath]).
    // -----------------------------------------------------------------------

    /**
     * Generation counter for everything a project's language servers have
     * said: diagnostics for any of its files, and the servers' own state. 0
     * until something has. Poll it exactly like [projectVersion].
     *
     * Polling also schedules server startup for files that were already open
     * when the userland arrived — `apt install clangd` in the terminal, with
     * the editor running. It never waits for a server.
     */
    external fun lspVersion(projectId: Long): Long

    /**
     * What each of the project's servers is doing, as a JSON array of
     * `{name, state, error, languages}`. `state` is `starting`, `running` or
     * `unavailable`; `error` carries the server's own last line of stderr when
     * it could not be started — usually "command not found", which is the
     * user's cue to install it. Versioned by [lspVersion]. Never blocks, never
     * null, `[]` when nothing is running.
     */
    external fun lspServers(projectId: Long): String

    /**
     * Diagnostic totals for a project, as JSON: `{version, errors, warnings,
     * infos, hints, files: [{path, errors, warnings, infos, hints}]}`. Paths
     * are project-relative and `/`-separated — the spelling [projectEntries]
     * and [gitChanges] use — except for a file outside the project, which keeps
     * its absolute path. Versioned by [lspVersion]. Never blocks, never null.
     *
     * Diagnostics are **project-wide**, as Zed's are: closing a tab does not
     * retract what a server said about that file, because a workspace-wide
     * analysis (rust-analyzer's `cargo check`) is still right about it. Only an
     * empty publish from the server, or [closeProject], clears them.
     */
    external fun lspDiagnostics(projectId: Long): String

    /**
     * Generation counter for one buffer's diagnostics; 0 until a server has
     * published for its file. Poll this per open tab: it is a hash lookup,
     * where [bufferDiagnostics] clones and serializes every row.
     *
     * It does **not** move when the buffer is edited — a UI must not be woken
     * by its own typing. `stale` in [bufferDiagnostics] is what says the rows
     * have drifted.
     */
    external fun bufferDiagnosticsVersion(bufferId: Long): Long

    /**
     * Everything a server has said about this buffer's file, as JSON:
     * `{version, buffer_version, stale, rows: [{row, col_utf16, end_row,
     * end_col_utf16, severity, message, source, code}]}`.
     *
     * `severity` is `error`, `warning`, `info` or `hint`, never absent — a
     * diagnostic the server left unrated counts as a warning. `source` and
     * `code` may be null. Rows are sorted by position, so painting a visible
     * window is one walk and "go to next diagnostic" is a scan.
     *
     * `buffer_version` is the buffer version the rows describe, or null when
     * the server dated them against text we no longer hold; `stale` is true
     * when the buffer has moved since. Dim the underlines rather than moving
     * them: only the server knows where they belong now.
     *
     * Reads a cache — never blocks, never null, empty for a buffer with no
     * file, no server, or nothing wrong with it.
     */
    external fun bufferDiagnostics(bufferId: Long): String

    /**
     * Asks for completions at a caret. Returns a request id to poll with; never
     * blocks and never fails — a buffer with no server behind it gets an id
     * that reports `unavailable` straight away, so the UI has one code path
     * whether or not language intelligence is installed.
     *
     * Cancels whatever completion request was already in flight, at the server
     * too, so a popup may call this on every keystroke.
     */
    external fun lspRequestCompletion(bufferId: Long, row: Long, colUtf16: Long): Long

    /** Hover documentation at a caret. Same contract as [lspRequestCompletion]. */
    external fun lspRequestHover(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * Where the symbol under the caret is defined. Same contract as
     * [lspRequestCompletion].
     */
    external fun lspRequestDefinition(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * Generation counter for a request: 1 while in flight, 2 once settled, 0
     * for an id the engine has forgotten (superseded, cancelled, or its buffer
     * closed). Poll it exactly like [projectSearchVersion].
     */
    external fun lspRequestVersion(requestId: Long): Long

    /**
     * A request's answer, as JSON: `{id, kind, state, version, buffer_id, row,
     * col_utf16, buffer_version, payload}`.
     *
     * `kind` is `completion`, `hover` or `definition`. `state` is `pending`,
     * `done`, `timeout`, `unavailable` or `cancelled` — `done` with an empty
     * payload is a real answer ("no completions here"); the other three are
     * not, and must not be cached as one. `row`, `col_utf16` and
     * `buffer_version` echo where and when it was asked, so a late answer can
     * be dropped by a caller whose caret has moved.
     *
     * `payload` is null until it settles, and then depends on `kind`:
     *
     *  * `completion` — `{is_incomplete, items: [{label, detail, kind,
     *    insert_text, is_snippet, filter_text, sort_text, documentation,
     *    deprecated, preselect, edit}]}`. `insert_text`, `filter_text` and
     *    `sort_text` are never null (they fall back to the label);
     *    `is_snippet` means `insert_text` carries `${1:placeholder}` syntax;
     *    `edit` is `{row, col_utf16, end_row, end_col_utf16}` — the range to
     *    replace — or null, meaning the UI picks the word around the caret.
     *  * `hover` — `{contents, range}`. `contents` is markdown, `""` when the
     *    server had nothing to say; `range` has the same shape as `edit`, or is
     *    null.
     *  * `definition` — `{targets: [{path, row, col_utf16, end_row,
     *    end_col_utf16}]}`. `path` is absolute and openable with [openFile];
     *    targets in URIs that are not files are dropped rather than handed over
     *    as paths that do not exist.
     *
     * Never null: a forgotten id reports itself `cancelled` with a null
     * payload — and every other field of *that* answer is a placeholder,
     * `kind` included, since the caller is the one that knows what it asked
     * for. It serializes the whole answer, which for a completion list is
     * tens of kilobytes — read it when [lspRequestVersion] moves, not on a
     * timer.
     */
    external fun lspRequestResult(requestId: Long): String

    /**
     * Stops a request and forgets it — how a closed completion popup frees its
     * slot, and how the server is told to stop working on an answer nobody will
     * read. False if the id was already gone.
     */
    external fun lspRequestCancel(requestId: Long): Boolean

    // -----------------------------------------------------------------------
    // ACP agents. The engine runs an agent inside the Debian userland — one
    // process at a time, budgeted against the same 32 the terminal, git, apt
    // and the language servers share — and keeps a state machine per session.
    // It speaks the Agent Client Protocol; we do not implement the protocol on
    // either side of this boundary.
    //
    // Two shapes, both already here:
    //
    //  * **The conversation is pushed and polled.** The agent streams whenever
    //    it likes and the engine folds each update into the session, bumping a
    //    revision. Poll [acpSessionVersion] — a single load — and only when it
    //    moves read [acpSessionState] for the chrome and [acpEntriesSince] for
    //    the rows that changed. Exactly the [lspVersion] contract.
    //  * **Everything the user does returns at once.** [acpPrompt],
    //    [acpCancel] and [acpRespondPermission] hand work to the engine's
    //    connection thread and come straight back; what happened shows up
    //    behind the counter.
    //
    // The only position anything here carries is inside a tool call's diff,
    // and those are 1-based rows in the shape [gitPatch] already speaks — so
    // an agent's edit renders with the same view a commit does.
    //
    // Absent, not failing, without a userland: the `play` flavour never has
    // one, and a `full` build before Debian is installed gets a session that
    // reports itself unavailable with that sentence.
    // -----------------------------------------------------------------------

    /**
     * Starts (or joins) the agent described by [specJson] and opens a session
     * on [projectId]. Returns a session id to poll.
     *
     * [specJson] is `{"name":…,"argv":[program,…],"env":{…}}` —
     * [AgentDefinition.toSpecJson] builds it. The argv is the **guest** command
     * line, so the program must be on the userland's PATH, which is what
     * `npm install -g` puts it there for.
     *
     * A spec different from the running agent's replaces that agent; the same
     * spec joins it, so a second session costs no second process.
     *
     * Returns -1 only when the request was malformed — bad JSON, no command,
     * an unknown project — which is a bug on this side with nothing to show a
     * user. Everything a user can act on comes back as a real session whose
     * state is `unavailable` and whose `error` is the sentence to show.
     *
     * **Blocking** — it spawns a process. Call it off the main thread.
     */
    external fun acpStartSession(projectId: Long, specJson: String): Long

    /**
     * Reopens one of the agent's *own* past conversations as a new thread.
     *
     * [sessionId] is a `sessionId` from [acpSessionList]. What reopening means
     * is the agent's to decide: one that supports `session/load` replays the
     * whole conversation back as updates, so the transcript fills in by
     * itself; one that only supports `session/resume` continues it with no
     * history at all. `agent.capabilities` in [acpSessionState] says which,
     * and `canOpenHistory` says whether either is possible.
     *
     * **Blocking** — it may spawn a process. Call it off the main thread.
     */
    external fun acpResumeSession(projectId: Long, specJson: String, sessionId: String): Long

    /**
     * The agent's own past conversations — `session/list`.
     *
     * `{"version", "loading", "error", "sessions": [{sessionId, cwd, title,
     * updatedAt, …}]}`, the session objects in the protocol's own camelCase.
     * Poll it with `refresh = false` and pass `refresh = true` only when the
     * user asked — opening the threads view, or after a delete — because a
     * refresh is a round trip to the agent. Empty for an agent whose
     * `capabilities.list` is false; the view is gated on that.
     */
    external fun acpSessionList(refresh: Boolean): String

    /** Forgets one of the agent's past conversations — `session/delete`. */
    external fun acpDeleteSession(sessionId: String): Boolean

    /**
     * Signs out of whatever [acpAuthenticate] signed into — `logout`.
     *
     * Does not end the open sessions: what signing out means for a
     * conversation in flight is the agent's call, and the next thing it
     * refuses arrives through the ordinary `needsAuth` path.
     */
    external fun acpLogout(): Boolean

    /**
     * Generation counter for a session; it moves whenever anything about the
     * conversation does. 0 means one thing only: an id the engine has
     * forgotten. Poll it exactly like [projectSearchVersion].
     */
    external fun acpSessionVersion(sessionId: Long): Long

    /**
     * Everything about a session except its rows, as JSON:
     *
     *     {"version":12, "project":1, "phase":"ready", "error":null,
     *      "needs_auth":false, "title":"Fixing the parser",
     *      "stop_reason":"end_turn", "entry_count":9,
     *      "plan":[{"content":…,"priority":…,"status":…}],
     *      "usage":{"used":1200,"size":200000},
     *      "modes":{"currentModeId":"default","availableModes":[…]},
     *      "commands":[…],
     *      "agent":{"name":"Claude Code","agent_name":…,"agent_version":…,
     *               "auth_methods":[…]}}
     *
     * `phase` is `starting`, `ready`, `running` or `unavailable`. `error`
     * carries the sentence to show — an agent's own last line of stderr when
     * it would not start, which is usually why. `needs_auth` means
     * [acpAuthenticate] with one of `agent.auth_methods` is the way forward.
     * `plan`, `modes` and `commands` are ACP's own shapes, camelCase and all.
     *
     * `"null"` for a forgotten id. Reads a cache; never blocks.
     */
    external fun acpSessionState(sessionId: Long): String

    /**
     * The conversation rows whose revision is newer than [since], as JSON:
     *
     *     {"revision":12, "total":9, "entries":[{"index":8, "rev":12, …}]}
     *
     * Each entry carries the `index` it sits at, so a caller merges in place
     * rather than re-reading the transcript; pass back the `revision` you were
     * last given. When `total` is smaller than what you hold, a refusal has
     * removed rows and the whole thing should be re-read from 0.
     *
     * `kind` says what a row is, and the rest depends on it:
     *
     *  * `user` — `{markdown}`.
     *  * `assistant` — `{chunks:[{thought,markdown}]}`. A `thought` chunk is
     *    the agent's reasoning and is worth folding away by default.
     *  * `tool_call` — `{id, title, tool_kind, status, options, content,
     *    locations}`. `tool_kind` is `read`/`edit`/`execute`/… — an icon, not
     *    the row's kind. `status` is `pending`, `waiting_for_confirmation`,
     *    `in_progress`, `completed`, `failed`, `rejected` or `canceled`.
     *    `options` is non-empty only while waiting, and each option is
     *    `{optionId,name,kind}` with `kind` in `allow_once`/`allow_always`/
     *    `reject_once`/`reject_always` — answer with [acpRespondPermission].
     *    `content` is a list of `{"type":"markdown","markdown":…}` and
     *    `{"type":"diff","diff":{path,original,is_binary,hunks}}`, the diff
     *    being exactly [FileDiff], so [gitPatch]'s renderer draws it.
     *    `locations` is `[{path,line}]`, project-relative where it can be.
     *
     * Never null except for a forgotten id, which gives `"null"`.
     */
    external fun acpEntriesSince(sessionId: Long, since: Long): String

    /**
     * Sends a prompt. Returns at once; the turn arrives behind the counter.
     *
     * A prompt sent while the agent is still starting, or still answering, is
     * queued **and shown immediately** — the running turn is cancelled and
     * this one follows it, which is Zed's follow-up behaviour. False for a
     * forgotten id or a session that is over.
     *
     * [mentionsJson] is a JSON array of project-relative paths the user
     * @-mentioned; the engine sends each as a resource block beside the text
     * (embedded file text when the agent takes it, a `file://` link
     * otherwise). `[]` when there are none; a malformed list means none
     * rather than a lost message.
     */
    external fun acpPrompt(sessionId: Long, text: String, mentionsJson: String): Boolean

    /**
     * Changes one of the agent's session configuration options — model,
     * effort, whatever it advertised under `configOptions` in
     * [acpSessionState]. [valueJson] is `true`/`false` for a boolean option
     * or a JSON string (`"\"opus\""`) for a select's value id. The change
     * lands when the agent confirms it — watch the counter. False for a
     * forgotten id or a value that is neither shape.
     */
    external fun acpSetConfigOption(
        sessionId: Long,
        configId: String,
        valueJson: String,
    ): Boolean

    /**
     * Stops the running turn and any prompt queued behind it. Tool calls still
     * in flight report `canceled`, and every open permission request is
     * answered `cancelled`, as the protocol requires. False for a forgotten id.
     */
    external fun acpCancel(sessionId: Long): Boolean

    /**
     * Answers a permission request. [optionId] must be one of the ids that
     * tool call's `options` offered — anything else is refused rather than
     * guessed at, and the request stays open. False when nothing was waiting.
     */
    external fun acpRespondPermission(
        sessionId: Long,
        toolCallId: String,
        optionId: String,
    ): Boolean

    /**
     * Switches the session's mode — Claude Code's `default` / `acceptEdits` /
     * `plan`, say. The change lands when the agent confirms it, so watch the
     * counter rather than assuming. False when the session has no modes.
     */
    external fun acpSetMode(sessionId: Long, modeId: String): Boolean

    /**
     * Runs one of the agent's advertised auth methods (`agent.auth_methods` in
     * [acpSessionState]), then retries the sessions that were waiting on it.
     * False when there is no agent to authenticate with.
     */
    external fun acpAuthenticate(sessionId: Long, methodId: String): Boolean

    /**
     * Closes a session and forgets it. Closing the last one stops the agent —
     * SIGQUIT first, as proot needs, so no tracee is orphaned against
     * Android's process cap. False if the id was already gone.
     */
    external fun acpCloseSession(sessionId: Long): Boolean

    /**
     * Files the agent has written, from [since] onwards:
     * `{"total":n,"paths":[absolute,…]}`.
     *
     * The engine already flags any open buffer among them the way it flags any
     * other external change; this says *which*, so the UI can reload them with
     * [reloadBuffer] — undoably, and with highlighting and the language server
     * kept in step. Pass back the `total` you were last given.
     */
    external fun acpWrittenFiles(since: Long): String

    /**
     * One agent terminal — a command the agent asked *us* to run through
     * `terminal/create`, named by a `terminal` content block on a tool call.
     *
     * Poll it with the `revision` you were last given. An unchanged terminal
     * answers `{"revision": n}` and nothing else, so watching a build log is
     * as cheap as watching an idle one; when it has moved you get
     * `{"revision", "label", "output", "truncated", "exitStatus", "running"}`.
     * `{"revision": 0}` means the engine no longer has it: the agent released
     * it, or its session closed. The transcript keeps the tool call either
     * way — it is the live process that is gone.
     */
    external fun acpTerminalOutput(terminalId: String, since: Long): String

    /**
     * Answer one of the agent's questions — the `elicitations` in
     * [acpSessionState], which is how ACP carries every ask that is not a
     * permission: a token, a choice between branches, "open this URL and sign
     * in".
     *
     * [actionJson] is `{"action":"accept","content":{…}}`,
     * `{"action":"decline"}` or `{"action":"cancel"}`. The content's JSON
     * types are the protocol's own, so a field drawn as a switch goes back as
     * a boolean and a number field as a number — a string there would be a
     * lie the agent cannot detect.
     *
     * A URL question stays listed after an accept: the agent is watching for
     * the sign-in and takes the card away itself when it sees it. False for a
     * question that is already gone.
     */
    external fun acpRespondElicitation(elicitationId: String, actionJson: String): Boolean
}
