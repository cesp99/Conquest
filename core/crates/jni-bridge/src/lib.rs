//! JNI surface for the Conquest Code engine.
//!
//! Naming contract: every function here maps to an `external` declaration in
//! `to.eyed.conquest.code.core.CoreBridge` on the Kotlin side. Keep the two
//! files in sync — this is the only place the two worlds meet.
//!
//! Design rule: calls across this boundary are coarse-grained. The Kotlin
//! layer must never loop over per-character JNI calls; batch work on one side
//! or the other.
//!
//! Error convention: functions returning `jlong` use `-1` for "unknown
//! buffer / invalid arguments" and, for undo/redo, also for "nothing to
//! undo/redo". Functions returning strings use `null` for unknown buffers.

use jni::JNIEnv;
use jni::objects::{JClass, JLongArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jintArray, jlong, jlongArray, jstring};
use std::path::Path;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};

use engine::Engine;

static ENGINE: OnceLock<Engine> = OnceLock::new();

/// Whether to log the engine's own diagnostics. Set by `initialize` before the
/// engine — and therefore the logger — comes up.
static VERBOSE_LOG: AtomicBool = AtomicBool::new(false);

fn engine() -> &'static Engine {
    ENGINE.get_or_init(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                // Debug in a debug build: the engine's own diagnostics (git
                // runs, scans) are debug-level, and chasing a problem without
                // them means rebuilding to see anything. Release stays at Info.
                //
                // `cfg!(debug_assertions)` cannot answer this: one cargo
                // invocation, always `--release`, serves every Android build
                // type, so it is false even in a debug APK. Kotlin passes
                // `BuildConfig.DEBUG` in instead.
                .with_max_level(if VERBOSE_LOG.load(Ordering::Relaxed) {
                    log::LevelFilter::Debug
                } else {
                    log::LevelFilter::Info
                })
                .with_tag("conquest-core"),
        );
        install_panic_hook();
        log::info!("engine initialized, version {}", engine::ENGINE_VERSION);
        Engine::new()
    })
}

/// Route panics to the log. Android discards a process's stderr, so without
/// this a panic on an engine thread — the runtime thread, a worktree scan —
/// is completely silent: the work simply never finishes.
fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let location = info
            .location()
            .map(|location| location.to_string())
            .unwrap_or_else(|| "unknown location".to_owned());
        let thread = std::thread::current();
        let name = thread.name().unwrap_or("<unnamed>");
        log::error!("panic on thread {name} at {location}: {}", info);
        previous(info);
    }));
}

fn get_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

fn to_jstring(env: &JNIEnv, text: String) -> jstring {
    env.new_string(text)
        .expect("failed to allocate Java string")
        .into_raw()
}

/// Hand the engine the app's private files directory, then bring it up.
/// Must be the first call into the bridge — see `engine::initialize`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_initialize(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    verbose_logging: jboolean,
) {
    let files_dir = get_string(&mut env, &files_dir);
    VERBOSE_LOG.store(verbose_logging != JNI_FALSE, Ordering::Relaxed);
    engine::initialize(Path::new(&files_dir));
    engine();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_engineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    engine();
    to_jstring(&env, engine::ENGINE_VERSION.to_owned())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_createBuffer(
    mut env: JNIEnv,
    _class: JClass,
    initial_text: JString,
) -> jlong {
    let text = get_string(&mut env, &initial_text);
    engine().create_buffer(&text) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_closeBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().close_buffer(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_applyEdit(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start: jlong,
    end: jlong,
    text: JString,
) -> jlong {
    if start < 0 || end < 0 {
        return -1;
    }
    let text = get_string(&mut env, &text);
    match engine().edit(buffer_id as u64, start as usize, end as usize, &text) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("applyEdit failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_undoBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().undo(buffer_id as u64) {
        Ok(Some(version)) => version as jlong,
        Ok(None) => -1,
        Err(err) => {
            log::warn!("undoBuffer failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_redoBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().redo(buffer_id as u64) {
        Ok(Some(version)) => version as jlong,
        Ok(None) => -1,
        Err(err) => {
            log::warn!("redoBuffer failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().version(buffer_id as u64) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("bufferVersion failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferLineCount(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().line_count(buffer_id as u64) {
        Ok(count) => count as jlong,
        Err(err) => {
            log::warn!("bufferLineCount failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferLines(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    first_line: jlong,
    last_line: jlong,
) -> jstring {
    let first = first_line.max(0).min(u32::MAX as jlong) as u32;
    let last = last_line.max(0).min(u32::MAX as jlong) as u32;
    match engine().lines(buffer_id as u64, first, last) {
        Ok(text) => to_jstring(&env, text),
        Err(err) => {
            log::warn!("bufferLines failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Assign a tree-sitter language (grammar name, e.g. "rust") to a buffer.
/// Returns false for unknown buffers or unknown language names.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferSetLanguage(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    language: JString,
) -> jboolean {
    let language = get_string(&mut env, &language);
    match engine().set_language(buffer_id as u64, &language) {
        Ok(true) => JNI_TRUE,
        Ok(false) => {
            log::warn!("bufferSetLanguage: unknown language {language:?}");
            JNI_FALSE
        }
        Err(err) => {
            log::warn!("bufferSetLanguage failed: {err}");
            JNI_FALSE
        }
    }
}

/// Highlight spans for rows [first_line, last_line), flattened as groups of
/// four ints: row, UTF-16 start column, UTF-16 end column, style id (index
/// into the engine's STYLE_NAMES). Empty array when the buffer has no
/// language; null for unknown buffers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferHighlights(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    first_line: jlong,
    last_line: jlong,
) -> jintArray {
    let first = first_line.max(0).min(u32::MAX as jlong) as u32;
    let last = last_line.max(0).min(u32::MAX as jlong) as u32;
    match engine().highlights(buffer_id as u64, first, last) {
        Ok(spans) => {
            let mut flat = Vec::with_capacity(spans.len() * 4);
            for span in &spans {
                flat.push(span.row as i32);
                flat.push(span.start_col_utf16 as i32);
                flat.push(span.end_col_utf16 as i32);
                flat.push(span.style as i32);
            }
            let array = env
                .new_int_array(flat.len() as i32)
                .expect("failed to allocate highlight array");
            env.set_int_array_region(&array, 0, &flat)
                .expect("failed to fill highlight array");
            array.into_raw()
        }
        Err(err) => {
            log::warn!("bufferHighlights failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// The symbol path containing the caret — Zed's breadcrumbs after the file
/// name — as a JSON array of strings, outermost first. Empty array when the
/// buffer has no language or no symbol contains the caret; null for an
/// unknown buffer. Columns are UTF-16, like every caret the UI holds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferOutlinePath(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jstring {
    let row = row.max(0).min(u32::MAX as jlong) as u32;
    let col = col_utf16.max(0).min(u32::MAX as jlong) as u32;
    match engine().outline_path(buffer_id as u64, row, col) {
        Ok(path) => {
            let json = serde_json::to_string(&path).unwrap_or_else(|err| {
                log::warn!("bufferOutlinePath failed to serialize: {err}");
                "[]".to_owned()
            });
            to_jstring(&env, json)
        }
        Err(err) => {
            log::warn!("bufferOutlinePath failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Every outline item in the buffer, in source order — the rows of Zed's
/// outline picker — as a JSON array of `{label, depth, row, col_utf16}`.
/// Empty array when the buffer has no language; null for unknown buffers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferOutline(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().outline(buffer_id as u64) {
        Ok(items) => {
            let json = serde_json::to_string(&items).unwrap_or_else(|err| {
                log::warn!("bufferOutline failed to serialize: {err}");
                "[]".to_owned()
            });
            to_jstring(&env, json)
        }
        Err(err) => {
            log::warn!("bufferOutline failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Byte offset of (row, byte column), clipped to the buffer. -1 for an
/// unknown buffer or negative arguments.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_pointToOffset(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    column: jlong,
) -> jlong {
    if row < 0 || column < 0 {
        return -1;
    }
    let row = row.min(u32::MAX as jlong) as u32;
    let column = column.min(u32::MAX as jlong) as u32;
    match engine().point_to_offset(buffer_id as u64, row, column) {
        Ok(offset) => offset as jlong,
        Err(err) => {
            log::warn!("pointToOffset failed: {err}");
            -1
        }
    }
}

/// (row, byte column) of a byte offset, clipped to the buffer, packed as
/// `(row << 32) | column`. -1 for an unknown buffer or negative offset.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_offsetToPoint(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    offset: jlong,
) -> jlong {
    if offset < 0 {
        return -1;
    }
    match engine().offset_to_point(buffer_id as u64, offset as usize) {
        Ok((row, column)) => ((row as jlong) << 32) | column as jlong,
        Err(err) => {
            log::warn!("offsetToPoint failed: {err}");
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// Projects (P3-2). Opening and expanding are asynchronous — they queue work on
// the engine's gpui runtime and return at once. The UI learns that something
// changed by watching `projectVersion`; every other call reads the mirrored
// snapshot and never blocks on the runtime.
// ---------------------------------------------------------------------------

/// Start scanning a directory as a project. Returns its id (always > 0).
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_openProject(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path = get_string(&mut env, &path);
    engine().open_project(Path::new(&path)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_closeProject(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jboolean {
    if engine().close_project(project_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Monotonic version of the mirrored worktree snapshot; 0 while there is
/// nothing to show. Poll this to know when to re-read entries.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectVersion(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jlong {
    engine().project_version(project_id as u64) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectScanComplete(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jboolean {
    if engine().project_scan_complete(project_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Why the project failed to open, or null if it did not fail.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectError(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().project_error(project_id as u64) {
        Some(error) => to_jstring(&env, error),
        None => std::ptr::null_mut(),
    }
}

/// Display name of the project root; null for an unknown project.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectRootName(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().project_root_name(project_id as u64) {
        Some(name) => to_jstring(&env, name),
        None => std::ptr::null_mut(),
    }
}

/// Direct children of a project-relative directory ("" for the root), as a
/// JSON array — one coarse call per expanded directory rather than one per
/// entry. Never null: unknown projects and unscanned directories give `[]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectEntries(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    dir: JString,
) -> jstring {
    let dir = get_string(&mut env, &dir);
    let entries = engine().project_entries(project_id as u64, &dir);
    let json = serde_json::to_string(&entries).unwrap_or_else(|err| {
        log::warn!("projectEntries failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Scan a directory the worktree deferred (ignored, hidden, or past
/// `file_scan_depth`). Asynchronous; the results arrive as a version bump.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_expandDirectory(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    dir: JString,
) -> jboolean {
    let dir = get_string(&mut env, &dir);
    if engine().expand_directory(project_id as u64, &dir) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Absolute path of a project-relative entry; null if the project is unknown
/// or the path tries to escape the root.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectEntryPath(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
) -> jstring {
    let path = get_string(&mut env, &path);
    match engine().project_entry_abs_path(project_id as u64, &path) {
        Some(path) => to_jstring(&env, path.to_string_lossy().into_owned()),
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// Git status (P3-8). The engine has no git of its own: it runs the one inside
// the Debian userland, through proot. Kotlin knows where that is — the engine
// must not guess — so `setUserland` is what turns the feature on. The `play`
// flavour never calls it, and every query below then answers "nothing to
// show", which is exactly what a clean repository looks like.
// ---------------------------------------------------------------------------

/// Tell the engine where proot and the Debian rootfs are. Call it once the
/// userland reports itself installed; never in the `play` flavour.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_setUserland(
    mut env: JNIEnv,
    _class: JClass,
    proot: JString,
    rootfs: JString,
    tmp_dir: JString,
    projects_dir: JString,
) {
    let proot = get_string(&mut env, &proot);
    let rootfs = get_string(&mut env, &rootfs);
    let tmp_dir = get_string(&mut env, &tmp_dir);
    let projects_dir = get_string(&mut env, &projects_dir);
    engine().set_userland(
        Path::new(&proot),
        Path::new(&rootfs),
        Path::new(&tmp_dir),
        Path::new(&projects_dir),
    );
}

/// Forget the userland — after the user deletes the rootfs. Git status then
/// degrades to empty, as in a build that never had one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_clearUserland(
    _env: JNIEnv,
    _class: JClass,
) {
    engine().clear_userland();
}

/// Generation counter for a project's git status; 0 until there is something
/// to show. Poll it exactly like `projectVersion`. Polling is also what
/// schedules refreshes — it never waits for git.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitStatusVersion(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jlong {
    engine().git_status_version(project_id as u64) as jlong
}

/// The whole status map as a JSON object of project-relative path to status
/// (`modified`, `added`, `deleted`, `renamed`, `conflicted`, `untracked`,
/// `ignored`). Ancestor directories are included, so the panel needs one
/// lookup per row. Reads a cache: never blocks, never null, `{}` when there is
/// nothing to show.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitStatus(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let statuses = engine().git_status(project_id as u64);
    let json = serde_json::to_string(&statuses).unwrap_or_else(|err| {
        log::warn!("gitStatus failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Everything the git panel draws, as JSON: `scanned`, `has_repo`, `branch`
/// (`{name, ahead, behind, unborn}` or null) and `entries`, each
/// `{path, staged, unstaged, conflicted, in_head}` with the two statuses using
/// the same names `gitStatus` does, or null.
///
/// Reads the same cache `gitStatus` does and is versioned by the same
/// `gitStatusVersion`: one `git status` serves the project panel and this.
/// Never blocks, never null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitChanges(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let changes = engine().git_changes(project_id as u64);
    let json = serde_json::to_string(&changes).unwrap_or_else(|err| {
        log::warn!("gitChanges failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Paths from a JSON array, for the four commands below. An unparseable
/// argument is an empty list, which every command refuses.
fn path_list(env: &mut JNIEnv, paths_json: &JString) -> Vec<String> {
    let json = get_string(env, paths_json);
    serde_json::from_str(&json).unwrap_or_else(|err| {
        log::warn!("git command: {json:?} is not a path list: {err}");
        Vec::new()
    })
}

/// null when it worked, and the reason when it did not — usually git's own
/// sentence, which is the only thing that explains an unconfigured identity or
/// a merge in progress.
fn command_result(env: &JNIEnv, result: Result<(), String>) -> jstring {
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(message) => to_jstring(env, message),
    }
}

/// Stage every listed path (`git add -A`), deletions included. **Blocking**:
/// it waits for a process inside the guest — call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitStage(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    paths_json: JString,
) -> jstring {
    let paths = path_list(&mut env, &paths_json);
    command_result(&env, engine().git_stage(project_id as u64, &paths))
}

/// Take every listed path back out of the index. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitUnstage(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    paths_json: JString,
) -> jstring {
    let paths = path_list(&mut env, &paths_json);
    command_result(&env, engine().git_unstage(project_id as u64, &paths))
}

/// **Destructive.** Throw away every uncommitted change to the listed paths: a
/// path HEAD knows is restored to what HEAD has, and a path it does not —
/// untracked, or staged-new — is moved to the app's trash rather than deleted.
/// Confirm with the user, naming the files, before calling this. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitDiscard(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    paths_json: JString,
) -> jstring {
    let paths = path_list(&mut env, &paths_json);
    command_result(&env, engine().git_discard(project_id as u64, &paths))
}

/// Commit what is staged. An empty or whitespace-only message is refused here
/// rather than becoming an empty commit. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitCommit(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    message: JString,
) -> jstring {
    let message = get_string(&mut env, &message);
    command_result(&env, engine().git_commit(project_id as u64, &message))
}

/// Push the named branch to `origin`, setting its upstream when it has none —
/// Zed's "Publish". Null when it worked (git's own output is logged), and the
/// reason when it did not. **Blocking**: it talks to the network.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitPush(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    branch: JString,
    set_upstream: jboolean,
) -> jstring {
    let branch = get_string(&mut env, &branch);
    let result = engine().git_push(project_id as u64, &branch, set_upstream != 0);
    command_result(&env, result.map(|_| ()))
}

/// The working tree's diff as a patch, as JSON — `{"files":[…]}` with each
/// file `{path, original, is_binary, hunks}` and each hunk
/// `{old_start, new_start, heading, lines:[{kind, text, old_line, new_line}]}`.
/// An empty `path` means every changed file. `{"error":…}` when git failed.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitPatch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
    staged: jboolean,
) -> jstring {
    let path = get_string(&mut env, &path);
    let path = Some(path.as_str()).filter(|path| !path.is_empty());
    let json = match engine().git_patch(project_id as u64, path, staged != 0) {
        Ok(files) => serde_json::json!({ "files": files }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// A page of commit history, newest first, as a JSON array of
/// `{sha, parents, author, author_email, author_time, subject, refs}`.
/// `[]` for a repository with no commits; the error text when git failed.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitLog(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    limit: jlong,
    skip: jlong,
) -> jstring {
    let json = match engine().git_log(project_id as u64, limit as u32, skip.max(0) as u32) {
        Ok(commits) => serde_json::json!({ "commits": commits }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// One commit in full: the fields above plus `message` and `files`, each
/// `{status, path, original}`. `{"error":…}` when git could not read it.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitCommitDetails(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    sha: JString,
) -> jstring {
    let sha = get_string(&mut env, &sha);
    let json = match engine().git_commit_details(project_id as u64, &sha) {
        Ok(details) => serde_json::to_string(&details)
            .unwrap_or_else(|_| "{\"error\":\"could not encode that commit\"}".to_owned()),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// Who commits are recorded as, as JSON `{"name":…,"email":…}` — both empty
/// when git has none, which is a fresh Debian's state and the reason every
/// commit in one fails. `{}` when there is no repository. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitIdentity(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let json = match engine().git_identity(project_id as u64) {
        Ok((name, email)) => serde_json::json!({ "name": name, "email": email }).to_string(),
        Err(_) => "{}".to_owned(),
    };
    to_jstring(&env, json)
}

/// Set that identity in the guest's global git config. Null when it worked and
/// the reason when it did not, like the other write commands. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitSetIdentity(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
    email: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    let email = get_string(&mut env, &email);
    command_result(
        &env,
        engine().git_set_identity(project_id as u64, &name, &email),
    )
}

/// Generation counter for a buffer's diff hunks; 0 until there is something to
/// show. Poll it exactly like `gitStatusVersion` — polling schedules the work
/// and never waits for it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitHunksVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().git_hunks_version(buffer_id as u64) as jlong
}

/// The buffer's diff against HEAD, flattened as groups of four ints: kind
/// (0 added, 1 modified, 2 deleted), first row, end row (exclusive), and how
/// many rows HEAD had there.
///
/// Rows are *buffer* rows and track unsaved edits: the base text comes from
/// git, the diff is computed here against the live buffer. A deletion occupies
/// no rows, so its first and end row are equal and mark the boundary the rows
/// were removed from.
///
/// Reads a cache: never blocks, never null, empty for a buffer with no file,
/// no repository, or no difference.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitHunks(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jintArray {
    let hunks = engine().git_hunks(buffer_id as u64);
    let mut flat = Vec::with_capacity(hunks.len() * 4);
    for hunk in &hunks {
        flat.push(match hunk.kind {
            engine::HunkKind::Added => 0,
            engine::HunkKind::Modified => 1,
            engine::HunkKind::Deleted => 2,
        });
        flat.push(hunk.start_row as i32);
        flat.push(hunk.end_row as i32);
        flat.push(hunk.old_rows as i32);
    }
    let array = env
        .new_int_array(flat.len() as i32)
        .expect("failed to allocate hunk array");
    env.set_int_array_region(&array, 0, &flat)
        .expect("failed to fill hunk array");
    array.into_raw()
}

/// Who last touched each run of rows, as JSON: `{"entries": [{sha, start_row,
/// row_count, author, author_time, summary}]}`, or `{"error": "…"}`.
///
/// Rows are the rows of the file **on disk**, not of the buffer: git blames
/// what it can read, and a buffer with unsaved edits has drifted from it.
///
/// **Blocking**, and uncached — it runs git every time. Call it when the user
/// asks for blame, off the main thread, not on a poll loop.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_gitBlame(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    let json = match engine().git_blame(buffer_id as u64) {
        Ok(entries) => serde_json::json!({ "entries": entries }),
        Err(message) => serde_json::json!({ "error": message }),
    };
    to_jstring(&env, json.to_string())
}

// ---------------------------------------------------------------------------
// Settings. The file is JSONC and hand-editable; writes are surgical so
// comments survive. All of these touch the filesystem — call them off the
// main thread.
// ---------------------------------------------------------------------------

/// Resolved settings as JSON. Falls back to defaults if the file is broken;
/// pair it with `settingsAreValid` to tell the user rather than silently
/// showing settings that aren't in effect.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_settings(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let settings = engine().settings();
    let json = serde_json::to_string(&settings).unwrap_or_else(|err| {
        log::warn!("settings failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// The settings file's raw JSONC text, created with documented defaults on
/// first use.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_settingsText(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().settings_text())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_settingsAreValid(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine().settings_are_valid() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Set one setting. `key_path` is dot-separated (`project_panel.show_ignored`)
/// and `value_json` is the new value as JSON (`true`, `18`, `"dark"`).
/// Returns the resolved settings as JSON, or null if the write failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_setSetting(
    mut env: JNIEnv,
    _class: JClass,
    key_path: JString,
    value_json: JString,
) -> jstring {
    let key_path = get_string(&mut env, &key_path);
    let value_json = get_string(&mut env, &value_json);
    let value: serde_json::Value = match serde_json::from_str(&value_json) {
        Ok(value) => value,
        Err(err) => {
            log::warn!("setSetting: {value_json:?} is not JSON: {err}");
            return std::ptr::null_mut();
        }
    };
    let keys: Vec<&str> = key_path.split('.').collect();
    match engine().set_setting(&keys, value) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("setSetting failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("setSetting failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Add or replace one `agent_servers` entry. `name` is the entry's key,
/// verbatim — not dot-split like `setSetting`'s path, so a name containing a
/// dot stays one key — and `spec_json` is a `CustomAgent`:
/// `{"command": …, "args": […], "env": {…}}`. Returns the resolved settings
/// as JSON, or null on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_setAgentServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    spec_json: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    let spec_json = get_string(&mut env, &spec_json);
    let agent: engine::CustomAgent = match serde_json::from_str(&spec_json) {
        Ok(agent) => agent,
        Err(err) => {
            log::warn!("setAgentServer: {spec_json:?} is not an agent spec: {err}");
            return std::ptr::null_mut();
        }
    };
    match engine().set_agent_server(&name, agent) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("setAgentServer failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("setAgentServer failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Remove one `agent_servers` entry by name. Removing a name that is not
/// there succeeds. Returns the resolved settings as JSON, or null on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_removeAgentServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    match engine().remove_agent_server(&name) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("removeAgentServer failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("removeAgentServer failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Replace the whole settings file. Returns the resolved settings as JSON, or
/// null if the text doesn't parse — in which case the file is left untouched.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_setSettingsText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let text = get_string(&mut env, &text);
    match engine().set_settings_text(&text) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("setSettingsText failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("setSettingsText failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Fuzzy-match `query` against the project's files, best first, as a JSON
/// array of objects with `path`, `name`, `positions` (UTF-16 offsets into
/// `path`) and `score`. An empty query lists files. Never null: unknown
/// projects give `[]`. **Blocking**: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectFindFiles(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    query: JString,
    limit: jlong,
) -> jstring {
    let query = get_string(&mut env, &query);
    let limit = limit.clamp(0, 1000) as usize;
    let matches = engine().find_files(project_id as u64, &query, limit);
    let json = serde_json::to_string(&matches).unwrap_or_else(|err| {
        log::warn!("projectFindFiles failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Read a file into a new buffer, with the language chosen from its name.
/// Returns the buffer id, or -1 if the file could not be read (missing,
/// unreadable, or not UTF-8). **Blocking**: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_openFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path = get_string(&mut env, &path);
    match engine().open_file(Path::new(&path)) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("openFile failed: {err}");
            -1
        }
    }
}

/// Bumped when a background reparse lands. The content version doesn't move
/// then, so the UI watches this to know its highlight spans are stale.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferHighlightVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().buffer_highlight_version(buffer_id as u64) as jlong
}

/// The grammar the buffer is highlighted with ("rust", "markdown"), or null
/// if it has no language.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferLanguage(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().buffer_language(buffer_id as u64) {
        Some(name) => to_jstring(&env, name.to_owned()),
        None => std::ptr::null_mut(),
    }
}

/// A language's whole editing config as JSON, straight from the grammar's own
/// `config.toml` — comment tokens, bracket pairs with their `close`,
/// `surround`, `newline` and `not_in` flags, `autoclose_before`, `hard_tabs`
/// and `increase_indent_pattern`. Null for a grammar we do not carry.
///
/// One call per language, for the life of the process: the answer is the same
/// for every buffer in it, and the UI caches it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_languageConfig(
    mut env: JNIEnv,
    _class: JClass,
    language: JString,
) -> jstring {
    let language = get_string(&mut env, &language);
    match engine::language_config_json(&language) {
        Some(json) => to_jstring(&env, json.to_owned()),
        None => std::ptr::null_mut(),
    }
}

/// For each byte offset, a bitmask of the bracket pairs live there — bit *i*
/// for pair *i* of `languageConfig`'s `brackets`. This is what
/// `not_in = ["string", "comment"]` needs: the tree-sitter scope at the caret,
/// which only the engine has.
///
/// Every bit is set for a buffer with no language, for an unknown buffer, and
/// for a language whose pairs are all unconditional — so the UI must only ask
/// when the pair it is about to insert actually carries a `not_in`, which no
/// plain bracket does.
///
/// It reparses the buffer if the tree is stale, so it takes every caret's
/// offset in one call and belongs on the pair-character path, not the typing
/// path.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferBracketScopes<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer_id: jlong,
    offsets: JLongArray<'local>,
) -> jlongArray {
    let count = env.get_array_length(&offsets).unwrap_or(0).max(0) as usize;
    let mut raw = vec![0 as jlong; count];
    // Never null and never short: the caller indexes this array by caret, and
    // "everything is live" is the answer that leaves autoclose as it was.
    let all_live = || vec![u64::MAX as jlong; count];
    let flat = if count > 0 && env.get_long_array_region(&offsets, 0, &mut raw).is_err() {
        log::warn!("bufferBracketScopes: could not read the offsets");
        all_live()
    } else {
        let offsets: Vec<usize> = raw.iter().map(|&at| at.max(0) as usize).collect();
        match engine().bracket_scopes(buffer_id as u64, &offsets) {
            Ok(masks) => masks.into_iter().map(|mask| mask as jlong).collect(),
            Err(err) => {
                log::warn!("bufferBracketScopes failed: {err}");
                all_live()
            }
        }
    };
    let array = env
        .new_long_array(flat.len() as i32)
        .expect("failed to allocate bracket-scope array");
    env.set_long_array_region(&array, 0, &flat)
        .expect("failed to fill bracket-scope array");
    array.into_raw()
}

/// Absolute path of the file behind a buffer; null for scratch buffers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferPath(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().buffer_path(buffer_id as u64) {
        Some(path) => to_jstring(&env, path.to_string_lossy().into_owned()),
        None => std::ptr::null_mut(),
    }
}

/// Whether the buffer has edits not yet written to disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferIsDirty(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().buffer_is_dirty(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Whether the file changed on disk since the buffer last synced with it.
/// Set by the worktree's watcher; cleared by save or reload.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferHasDiskChange(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().buffer_has_disk_change(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Whether the file behind the buffer has been deleted from disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferFileDeleted(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().buffer_file_deleted(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Write the buffer to its file. Returns the version now on disk, or -1 if
/// the buffer has no file or the write failed. **Blocking**: call it off the
/// main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_saveBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().save_buffer(buffer_id as u64) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("saveBuffer failed: {err}");
            -1
        }
    }
}

/// Re-read the file into the buffer, discarding local edits (undoably).
/// Returns the new version, or -1. **Blocking**: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_reloadBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().reload_buffer(buffer_id as u64) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("reloadBuffer failed: {err}");
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// Search. Both searches take the same options object, as JSON, so one search
// bar can drive either without reshaping its state:
//
//     {"query": "needle", "regex": false, "case_sensitive": false,
//      "whole_word": false, "include_ignored": false,
//      "include_globs": [], "exclude_globs": []}
//
// Every field may be omitted. The last three are project-search only.
//
// `whole_word` means one thing for every kind of query: a hit counts only when
// neither neighbouring character is a word character (`alphanumeric || '_'`).
// A regex is filtered on where its match landed, never rewritten.
//
// Buffer search answers on the calling thread because it is a single pass over
// a rope — milliseconds on a 100k-line file, which is what lets the search bar
// re-run it on every keystroke of the query. Project search cannot answer at
// all: it reads thousands of files, so it runs on a thread of its own and
// publishes a generation counter to poll, the same shape as `gitStatusVersion`.
//
// Project search silently skips four kinds of file: unreadable ones, ones over
// 4 MiB, ones holding a NUL byte anywhere, and ones that are not valid UTF-8.
// They are counted in `files_searched` but can never produce a hit.
// ---------------------------------------------------------------------------

/// Why a query will not compile, or null if it will. The search bar calls this
/// to explain a half-typed regex rather than silently showing nothing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_searchQueryError(
    mut env: JNIEnv,
    _class: JClass,
    query_json: JString,
) -> jstring {
    let query_json = get_string(&mut env, &query_json);
    match serde_json::from_str(&query_json) {
        Ok(options) => match engine().search_query_error(&options) {
            Some(error) => to_jstring(&env, error),
            None => std::ptr::null_mut(),
        },
        Err(err) => to_jstring(&env, err.to_string()),
    }
}

/// Every match in a buffer, as longs: element 0 is the total number of matches
/// in the buffer, and the rest are groups of four — byte start, byte end, row,
/// byte column. The total can exceed the groups present, which is how the
/// caller knows `limit` bit; the group layout is a primitive array copy rather
/// than JSON because this runs on the keystroke path.
///
/// Null for an unknown buffer or a query that does not compile — ask
/// `searchQueryError` which it was.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferSearch(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    query_json: JString,
    limit: jlong,
) -> jlongArray {
    let query_json = get_string(&mut env, &query_json);
    let options = match serde_json::from_str(&query_json) {
        Ok(options) => options,
        Err(err) => {
            log::warn!("bufferSearch: {query_json:?} is not a query: {err}");
            return std::ptr::null_mut();
        }
    };
    let limit = limit.clamp(0, MAX_BUFFER_MATCHES) as usize;
    let found = match engine().search_buffer(buffer_id as u64, &options, limit) {
        Ok(found) => found,
        Err(err) => {
            log::warn!("bufferSearch failed: {err}");
            return std::ptr::null_mut();
        }
    };

    let mut flat = Vec::with_capacity(1 + found.matches.len() * 4);
    flat.push(found.total as jlong);
    for found in &found.matches {
        flat.push(found.start as jlong);
        flat.push(found.end as jlong);
        flat.push(found.row as jlong);
        flat.push(found.column as jlong);
    }
    let array = env
        .new_long_array(flat.len() as i32)
        .expect("failed to allocate search array");
    env.set_long_array_region(&array, 0, &flat)
        .expect("failed to fill search array");
    array.into_raw()
}

/// The most matches `bufferSearch` will hand back at once. Ten thousand is
/// Zed's own cap, and 320 KB of longs is already more than any UI will draw.
const MAX_BUFFER_MATCHES: jlong = 10_000;

/// Start searching a project. Returns a search id to poll with, or -1 if the
/// project is unknown or the query does not compile. Returns at once: the
/// search runs on a thread of its own.
///
/// A project still being scanned is neither of those things: the search starts,
/// reports `"scanning"` until the scan lands, and then searches the whole tree.
///
/// Starting a search cancels whatever was already running for that project.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectSearchStart(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    query_json: JString,
) -> jlong {
    let query_json = get_string(&mut env, &query_json);
    let options = match serde_json::from_str(&query_json) {
        Ok(options) => options,
        Err(err) => {
            log::warn!("projectSearchStart: {query_json:?} is not a query: {err}");
            return -1;
        }
    };
    match engine().start_project_search(project_id as u64, &options) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("projectSearchStart failed: {err}");
            -1
        }
    }
}

/// Generation counter for a search, bumped whenever there is something new to
/// read. Non-zero from the moment `projectSearchStart` returns, so 0 means
/// only one thing: an id the engine has forgotten. Poll it like
/// `projectVersion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectSearchVersion(
    _env: JNIEnv,
    _class: JClass,
    search_id: jlong,
) -> jlong {
    engine().project_search_version(search_id as u64) as jlong
}

/// Everything a search has found from `from_file` onwards, as JSON. Results
/// only grow, so a caller holding `n` files passes `n` and gets the rest.
/// Never null: a forgotten id reports itself cancelled with nothing in it.
///
/// `state` is `scanning`, `running`, `done` or `cancelled`. Not free: this
/// clones and serializes every file found since the last call, which after a
/// 100 ms publish interval can be megabytes — call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectSearchResults(
    env: JNIEnv,
    _class: JClass,
    search_id: jlong,
    from_file: jlong,
) -> jstring {
    let from_file = from_file.max(0) as usize;
    let results = engine().project_search_results(search_id as u64, from_file);
    let json = serde_json::to_string(&results).unwrap_or_else(|err| {
        log::warn!("projectSearchResults failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Stop a search and forget it. False if the engine no longer knows the id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_projectSearchCancel(
    _env: JNIEnv,
    _class: JClass,
    search_id: jlong,
) -> jboolean {
    if engine().cancel_project_search(search_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferText(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().text(buffer_id as u64) {
        Ok(text) => to_jstring(&env, text),
        Err(err) => {
            log::warn!("bufferText failed: {err}");
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// Language servers.
//
// The engine has no LSP client of its own: it drives Zed's, over the same
// proot the git calls above go through, so a server is whatever `apt` put in
// the Debian rootfs. That makes every call here degrade the way the git ones
// do — no userland, no server installed, or a language nobody packages one for
// all report "nothing to show" rather than an error.
//
// Two shapes, both already used elsewhere on this boundary:
//
// * **Diagnostics are pushed and polled.** The server sends them when it feels
//   like it; the engine caches them and bumps a counter. Poll `lspVersion` per
//   project and `bufferDiagnosticsVersion` per open buffer, exactly as the
//   panel polls `projectVersion` — and read the JSON only when one moves.
//   Polling `lspVersion` is also what *starts* servers for files that were
//   already open when the userland appeared, so a project view must poll it.
// * **Requests are started and polled.** `lspRequestCompletion` and friends
//   return an id at once and never block. Poll `lspRequestVersion` (1 while in
//   flight, 2 once settled, 0 once forgotten) and then read
//   `lspRequestResult`. Starting a request cancels the previous one *of the
//   same kind*, which is what a completion popup re-asking on every keystroke
//   wants; `lspRequestCancel` frees the slot when the popup closes.
//
// Positions are UTF-16 columns in both directions, like every other position
// on this boundary (`bufferHighlights`, `bufferOutlinePath`).
// ---------------------------------------------------------------------------

/// Generation counter for everything a project's language servers have said:
/// diagnostics for any of its files, and the servers' own state. 0 until
/// something has. Poll it exactly like `projectVersion`.
///
/// Polling also schedules server startup for files that were already open when
/// the userland arrived — `apt install clangd` in the terminal while the editor
/// is running. It never waits for a server.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspVersion(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jlong {
    engine().lsp_version(project_id as u64) as jlong
}

/// What each of the project's servers is doing, as a JSON array of
/// `{name, state, error, languages}`. `state` is `starting`, `running` or
/// `unavailable`; `error` carries the server's own last line of stderr when it
/// could not be started, which is usually "command not found" and is the user's
/// cue to install it. Versioned by `lspVersion`. Never blocks, never null,
/// `[]` when there is nothing running.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspServers(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let servers = engine().lsp_servers(project_id as u64);
    let json = serde_json::to_string(&servers).unwrap_or_else(|err| {
        log::warn!("lspServers failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Diagnostic totals for a project, as JSON:
/// `{version, errors, warnings, infos, hints, files: [{path, errors, warnings,
/// infos, hints}]}`. Paths are project-relative and `/`-separated — the same
/// spelling `projectEntries` and `gitChanges` use — except for a file outside
/// the project, which keeps its absolute path. Versioned by `lspVersion`.
/// Never blocks, never null.
///
/// Diagnostics are **project-wide**, as Zed's are: closing a tab does not
/// retract what a server said about that file, because a workspace-wide
/// analysis (rust-analyzer's `cargo check`) is still right about it. Only an
/// empty publish from the server, or `closeProject`, clears them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspDiagnostics(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let diagnostics = engine().lsp_diagnostics(project_id as u64);
    let json = serde_json::to_string(&diagnostics).unwrap_or_else(|err| {
        log::warn!("lspDiagnostics failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Generation counter for one buffer's diagnostics; 0 until a server has
/// published for its file. Poll this per open tab — it is a hash lookup, where
/// `bufferDiagnostics` clones and serializes every row.
///
/// It does **not** move when the buffer is edited: a UI must not be woken by
/// its own typing. `bufferDiagnostics().stale` is what says the rows have
/// drifted.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferDiagnosticsVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().buffer_diagnostics_version(buffer_id as u64) as jlong
}

/// Everything a server has said about this buffer's file, as JSON:
/// `{version, buffer_version, stale, rows: [{row, col_utf16, end_row,
/// end_col_utf16, severity, message, source, code}]}`.
///
/// `severity` is `error`, `warning`, `info` or `hint` — never absent, because a
/// diagnostic the server left unrated is treated as a warning. `source` and
/// `code` may be null. Rows are sorted by position, so painting a visible
/// window is one walk.
///
/// `buffer_version` is the buffer version the rows describe, or null when the
/// server dated them against text we no longer have; `stale` is true when the
/// buffer has moved since — dim the underlines rather than moving them.
///
/// Reads a cache: never blocks, never null, empty for a buffer with no file, no
/// server, or nothing wrong with it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferDiagnostics(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    let diagnostics = engine().buffer_diagnostics(buffer_id as u64);
    let json = serde_json::to_string(&diagnostics).unwrap_or_else(|err| {
        log::warn!("bufferDiagnostics failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// A caret, clamped the way `bufferOutlinePath` clamps one.
fn caret(row: jlong, col_utf16: jlong) -> (u32, u32) {
    (
        row.max(0).min(u32::MAX as jlong) as u32,
        col_utf16.max(0).min(u32::MAX as jlong) as u32,
    )
}

/// Ask for completions at a caret. Returns a request id to poll with — never
/// blocks and never fails: a buffer with no server behind it gets an id that
/// reports `unavailable` immediately, so the UI has one code path.
///
/// Cancels whatever completion request was already in flight, including at the
/// server, so a popup may call this on every keystroke.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspRequestCompletion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_completion(buffer_id as u64, row, col) as jlong
}

/// Hover documentation at a caret. Same contract as `lspRequestCompletion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspRequestHover(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_hover(buffer_id as u64, row, col) as jlong
}

/// Where the symbol under the caret is defined. Same contract as
/// `lspRequestCompletion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspRequestDefinition(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_definition(buffer_id as u64, row, col) as jlong
}

/// Generation counter for a request: 1 while it is in flight, 2 once it has
/// settled, 0 for an id the engine has forgotten (superseded, cancelled, or its
/// buffer closed). Poll it like `projectSearchVersion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspRequestVersion(
    _env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) -> jlong {
    engine().lsp_request_version(request_id as u64) as jlong
}

/// A request's answer, as JSON:
/// `{id, kind, state, version, buffer_id, row, col_utf16, buffer_version,
/// payload}`.
///
/// `kind` is `completion`, `hover` or `definition`. `state` is `pending`,
/// `done`, `timeout`, `unavailable` or `cancelled` — `done` with an empty
/// payload is a real answer ("no completions here"), the other three are not,
/// and a UI should not cache them. `row`, `col_utf16` and `buffer_version` echo
/// where and when it was asked, so a late answer can be dropped by a caller
/// whose caret has moved.
///
/// `payload` is null until it settles, and then depends on `kind`:
///
/// * `completion` — `{is_incomplete, items: [{label, detail, kind, insert_text,
///   is_snippet, filter_text, sort_text, documentation, deprecated, preselect,
///   edit}]}`. `insert_text`, `filter_text` and `sort_text` are never null
///   (they fall back to the label); `is_snippet` means `insert_text` carries
///   `${1:placeholder}` syntax; `edit` is `{row, col_utf16, end_row,
///   end_col_utf16}` — the range to replace — or null, meaning the UI picks the
///   word around the caret itself.
/// * `hover` — `{contents, range}`. `contents` is markdown and is `""` when the
///   server had nothing to say; `range` is the same shape as `edit`, or null.
/// * `definition` — `{targets: [{path, row, col_utf16, end_row,
///   end_col_utf16}]}`. `path` is absolute and openable with `openFile`;
///   targets in URIs that are not files are dropped rather than handed over.
///
/// Never null. A forgotten id reports itself `cancelled` with a null payload;
/// every other field of that answer is a placeholder, `kind` included — the
/// caller is the one that knows what it asked for.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspRequestResult(
    env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) -> jstring {
    let result = engine().lsp_request_result(request_id as u64);
    let json = serde_json::to_string(&result).unwrap_or_else(|err| {
        log::warn!("lspRequestResult failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Stop a request and forget it — how a closed completion popup frees its slot,
/// and how the server is told to stop working on an answer nobody will read.
/// False if the id was already gone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_lspRequestCancel(
    _env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) -> jboolean {
    if engine().lsp_cancel_request(request_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------------------
// ACP agents (phase 6)
//
// The engine runs an agent inside the Debian userland and keeps one state
// machine per session; this is the coarse read/write surface over it. Two
// shapes, both already on this boundary:
//
//  * **The conversation is pushed and polled.** The agent streams whenever it
//    likes; the engine folds each update into the session and bumps a
//    revision. Poll `acpSessionVersion`, then read `acpSessionState` for the
//    chrome and `acpEntriesSince` for the rows that actually moved — the same
//    counter-then-payload contract as `lspVersion`, and for the same reason.
//  * **Everything the user does returns at once.** Prompting, cancelling and
//    answering a permission request all hand work to the connection thread and
//    come straight back; what happened shows up behind the counter.
//
// Positions inside a tool call's diff are 1-based rows in the shape `gitPatch`
// already speaks, so an agent's edit renders with the diff view the git panel
// uses. Nothing else here carries a position.
//
// The `play` flavour has no userland, so it has no agent: `acpStartSession`
// answers with a session that reports itself unavailable, and the panel is
// absent rather than broken.
// ---------------------------------------------------------------------------

/// Start (or join) the agent described by `specJson` and open a session on
/// `projectId`.
///
/// `specJson` is `{"name": …, "argv": [program, …], "env": {…}}` — the argv is
/// the *guest* command line, so the program must be on the userland's PATH.
///
/// Returns a session id to poll, or -1 when the request itself was malformed
/// (bad JSON, no command, unknown project) — a caller's bug, with nothing to
/// show a user. Everything a user can act on arrives as a session instead: no
/// userland and an agent that will not start both come back as a real id whose
/// state is `unavailable` with a sentence.
///
/// **Blocking** — it spawns a process. Call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpStartSession(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    spec_json: JString,
) -> jlong {
    let spec = get_string(&mut env, &spec_json);
    match engine().acp_start_session(project_id as u64, &spec) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("acpStartSession refused: {err}");
            -1
        }
    }
}

/// Generation counter for a session: it moves whenever anything about the
/// conversation does. 0 means one thing only — an id the engine has forgotten.
/// Poll it exactly like `projectSearchVersion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpSessionVersion(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jlong {
    engine().acp_session_version(session_id as u64) as jlong
}

/// Everything about a session except its rows, as JSON — see the Kotlin
/// declaration for the shape. `"null"` for a forgotten id. Reads a cache;
/// never blocks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpSessionState(
    env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jstring {
    to_jstring(&env, engine().acp_session_state(session_id as u64))
}

/// The conversation rows whose revision is newer than `since`, as JSON:
/// `{revision, total, entries: [{index, rev, kind, …}]}`.
///
/// Only what moved comes back, with the index it sits at, so a caller merges
/// in place and pays for the whole transcript once. `total` is how many rows
/// there are now — when it is smaller than what the caller holds, a refusal
/// has removed some and the caller re-reads from 0.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpEntriesSince(
    env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    since: jlong,
) -> jstring {
    let entries = engine().acp_entries_since(session_id as u64, since.max(0) as u64);
    to_jstring(&env, entries)
}

/// Send a prompt. Returns at once; the turn arrives behind the counter. False
/// for a forgotten id or a session that is over.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpPrompt(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    text: JString,
    mentions_json: JString,
) -> jboolean {
    let text = get_string(&mut env, &text);
    let mentions_json = get_string(&mut env, &mentions_json);
    if engine().acp_prompt(session_id as u64, &text, &mentions_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Change one of the agent's session configuration options —
/// `session/set_config_option`, the request behind model/effort selectors.
/// `value_json` is `true`/`false` for a boolean option or a JSON string for
/// a select's value id. False for a forgotten id or a value that is neither.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpSetConfigOption(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    config_id: JString,
    value_json: JString,
) -> jboolean {
    let config_id = get_string(&mut env, &config_id);
    let value_json = get_string(&mut env, &value_json);
    if engine().acp_set_config_option(session_id as u64, &config_id, &value_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Stop the running turn. False for a forgotten id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpCancel(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    if engine().acp_cancel(session_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Answer a permission request: `optionId` is one of the ids the tool call's
/// `options` offered. False when nothing was waiting under that tool call, or
/// the option is not one it offered.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpRespondPermission(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    tool_call_id: JString,
    option_id: JString,
) -> jboolean {
    let tool_call = get_string(&mut env, &tool_call_id);
    let option = get_string(&mut env, &option_id);
    if engine().acp_respond_permission(session_id as u64, &tool_call, &option) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Switch the session's mode. The change lands when the agent confirms it, so
/// watch the counter rather than assuming. False when the session has no modes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpSetMode(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    mode_id: JString,
) -> jboolean {
    let mode = get_string(&mut env, &mode_id);
    if engine().acp_set_mode(session_id as u64, &mode) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Run one of the agent's advertised auth methods, then retry the sessions
/// that were waiting on it. False when there is no agent to authenticate with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpAuthenticate(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    method_id: JString,
) -> jboolean {
    let method = get_string(&mut env, &method_id);
    if engine().acp_authenticate(session_id as u64, &method) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Close a session and forget it. Closing the last one stops the agent, the
/// careful way proot needs. False if the id was already gone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpCloseSession(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    if engine().acp_close_session(session_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Files the agent has written through the client, from `since` onwards:
/// `{"total": n, "paths": [absolute, …]}`.
///
/// The engine flags any open buffer among them the way it flags any other
/// external change; this is how the UI learns *which* ones, so it can reload
/// them through `reloadBuffer` — undoably, and with highlighting and the
/// language server kept in step. Pass the `total` you were last given.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpWrittenFiles(
    env: JNIEnv,
    _class: JClass,
    since: jlong,
) -> jstring {
    to_jstring(&env, engine().acp_written_files(since.max(0) as u64))
}

/// Reopen one of the agent's own past conversations in a new thread —
/// `session/load` when the agent can replay the history, `session/resume`
/// when it can only continue. `session_id` comes from `acpSessionList`.
/// Errors exactly as `acpStartSession` does.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpResumeSession(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    spec_json: JString,
    session_id: JString,
) -> jlong {
    let spec_json = get_string(&mut env, &spec_json);
    let session_id = get_string(&mut env, &session_id);
    match engine().acp_resume_session(project_id as u64, &spec_json, &session_id) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("acpResumeSession refused: {err}");
            -1
        }
    }
}

/// The agent's own past conversations — `session/list`, which not every agent
/// has (`agent.capabilities.list` in `acpSessionState` says). Pass `refresh`
/// when the user asked for the list; `false` while polling.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpSessionList(
    env: JNIEnv,
    _class: JClass,
    refresh: jboolean,
) -> jstring {
    to_jstring(&env, engine().acp_session_list(refresh != JNI_FALSE))
}

/// Forget one of the agent's past conversations — `session/delete`. False
/// when the agent has no such method or there is no agent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpDeleteSession(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) -> jboolean {
    let session_id = get_string(&mut env, &session_id);
    if engine().acp_delete_session(&session_id) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Sign out of whatever `acpAuthenticate` signed into — `logout`. False when
/// the agent has no such method or there is no agent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpLogout(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine().acp_logout() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Interrupt the running turn and send this prompt as soon as it stops — the
/// deliberate version of a follow-up. `acpPrompt` queues instead, which is
/// what a follow-up typed mid-turn should do.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpPromptImmediately(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    text: JString,
    mentions_json: JString,
) -> jboolean {
    let text = get_string(&mut env, &text);
    let mentions_json = get_string(&mut env, &mentions_json);
    if engine().acp_prompt_immediately(session_id as u64, &text, &mentions_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Drop one queued prompt, by the `id` its row carries in `queue`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpRemoveQueuedPrompt(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    queued_id: jlong,
) -> jboolean {
    if engine().acp_remove_queued_prompt(session_id as u64, queued_id.max(0) as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// The agent's questions that belong to no session — a JSON array in the same
/// shape `elicitations` takes in `acpSessionState`. Poll it whenever an agent
/// is running: one of these can be raised before any session exists, and an
/// unanswered one blocks the agent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpPendingElicitations(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().acp_pending_elicitations())
}

/// Put away the notice saying why the last mode or config change did not
/// take. False for a session the engine has forgotten.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpClearNotice(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    if engine().acp_clear_notice(session_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Answer one of the agent's questions — `elicitation/create`, the shape
/// every ask that is not a permission arrives in. `action_json` is
/// `{"action":"accept","content":{…}}`, `{"action":"decline"}` or
/// `{"action":"cancel"}`, and the content's JSON types are the protocol's, so
/// a switch comes back as a bool and a number field as a number. False for a
/// question that is already gone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpRespondElicitation(
    mut env: JNIEnv,
    _class: JClass,
    elicitation_id: JString,
    action_json: JString,
) -> jboolean {
    let elicitation_id = get_string(&mut env, &elicitation_id);
    let action_json = get_string(&mut env, &action_json);
    if engine().acp_respond_elicitation(&elicitation_id, &action_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// One agent terminal, for the card that draws it. Poll it with the
/// `revision` you were last given: an unchanged terminal answers
/// `{"revision": n}` and nothing else, which is what makes polling a
/// megabyte-capable buffer cheap. `{"revision": 0}` means the engine no
/// longer has it — the agent released it, or its session closed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_acpTerminalOutput(
    mut env: JNIEnv,
    _class: JClass,
    terminal_id: JString,
    since: jlong,
) -> jstring {
    let terminal_id = get_string(&mut env, &terminal_id);
    let json = engine().acp_terminal_output(&terminal_id, since.max(0) as u64);
    to_jstring(&env, json)
}
