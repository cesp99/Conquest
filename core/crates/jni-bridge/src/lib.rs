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
use jni::objects::{JClass, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jintArray, jlong, jstring};
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
