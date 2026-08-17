//! User settings: a JSONC file the app and the user both edit.
//!
//! The file is hand-editable and **keeps its comments**. Writes from the
//! settings screen are surgical — Zed's `settings_json` locates the exact key
//! in the syntax tree and replaces just that value — so the explanatory
//! comments the default file ships with, and anything the user adds, survive
//! every change the UI makes. That is the whole reason this lives in the
//! engine rather than being a `SharedPreferences` blob.
//!
//! Keys follow Zed's names where the meaning is the same (`theme`,
//! `buffer_font_size`, `tab_size`), so muscle memory and documentation carry
//! over. Unknown keys are left alone rather than dropped: a settings file is
//! the user's, and a future version of the app may understand more of it.

use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use serde::{Deserialize, Serialize};

use crate::EngineError;

/// Which theme to use. `System` follows the device's light/dark setting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ThemeMode {
    #[default]
    System,
    Light,
    Dark,
}

/// How the project tree treats gitignored entries. Zed dims them rather than
/// hiding them, which is the default here too — seeing that a file is ignored
/// is usually more useful than not seeing the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum GitignoredFiles {
    /// Listed like any other file.
    Show,
    /// Listed, but greyed out.
    #[default]
    Dimmed,
    /// Left out of the tree.
    Hide,
}

/// What a line longer than the pane does — Zed's `soft_wrap`, with the two
/// values that mean something on a screen this size. Zed's other two,
/// `preferred_line_length` and `bounded`, both wrap at a column the user
/// picks; a phone is narrower than any column worth picking, so they would
/// only ever behave as one of these.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum SoftWrap {
    /// Zed's default: the line runs off the right edge and scrolls.
    #[default]
    None,
    /// Wrap at the width of the text area.
    EditorWidth,
}

/// Which side of the workspace a panel lives on — Zed's `dock`, minus
/// `bottom`, which here belongs to the terminal alone.
///
/// The defaults are *this app's*, not Zed's current ones: Zed moved its
/// project panel to the right, and a phone-shaped editor reads better with the
/// tree where every file manager on the platform puts it. Both are one line in
/// settings.json, which is the point of the setting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DockSide {
    Left,
    Right,
}

/// Zed's `git.inline_blame`. An object with one field rather than a bare
/// bool, because that is the shape Zed's settings file has and someone
/// pasting a line out of their Zed config should find it works.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct InlineBlameSettings {
    pub enabled: bool,
}

impl Default for InlineBlameSettings {
    fn default() -> Self {
        // Zed's own default (assets/settings/default.json).
        Self { enabled: true }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct GitSettings {
    pub inline_blame: InlineBlameSettings,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct ProjectPanelSettings {
    pub gitignored_files: GitignoredFiles,
    /// Zed's `project_panel.dock`.
    pub dock: DockSide,
    /// Zed's `project_panel.default_width`, in dp rather than px — this is
    /// Android, where a number of pixels is not a size.
    pub default_width: f32,
}

impl Default for ProjectPanelSettings {
    fn default() -> Self {
        Self {
            gitignored_files: GitignoredFiles::default(),
            dock: DockSide::Left,
            default_width: 240.0,
        }
    }
}

/// A panel that has nothing to configure but where it sits and how wide it is.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct PanelSettings {
    pub dock: DockSide,
    pub default_width: f32,
}

impl PanelSettings {
    const fn new(dock: DockSide, default_width: f32) -> Self {
        Self { dock, default_width }
    }
}

impl Default for PanelSettings {
    fn default() -> Self {
        Self::new(DockSide::Right, 360.0)
    }
}

/// Everything the app can be configured with. Every field here is wired to
/// something visible — a setting that does nothing is worse than no setting.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub theme: ThemeMode,
    /// Editor text size, in scale-independent pixels.
    pub buffer_font_size: f32,
    /// Spaces inserted by the Tab key.
    pub tab_size: u32,
    /// What a line longer than the pane does.
    pub soft_wrap: SoftWrap,
    pub git: GitSettings,
    pub project_panel: ProjectPanelSettings,
    /// The git panel — Zed's `git_panel.dock` and `default_width`.
    pub git_panel: PanelSettings,
    /// Search across the project. Zed has no dock for this (it is a pane item
    /// there); here it is a panel like the others and says so.
    pub project_search: PanelSettings,
    /// The Markdown and SVG preview, likewise.
    pub preview: PanelSettings,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme: ThemeMode::System,
            buffer_font_size: 14.0,
            tab_size: 4,
            soft_wrap: SoftWrap::default(),
            git: GitSettings::default(),
            project_panel: ProjectPanelSettings::default(),
            git_panel: PanelSettings::new(DockSide::Right, 360.0),
            project_search: PanelSettings::new(DockSide::Right, 360.0),
            preview: PanelSettings::new(DockSide::Right, 400.0),
        }
    }
}

impl Settings {
    /// Clamp values a hand-edited file could put out of range. A settings
    /// file is user input, and a font size of 0 or 10000 should not be able
    /// to make the editor unusable — or unrecoverable, since fixing it means
    /// reading the very screen it broke.
    fn sanitized(mut self) -> Self {
        self.buffer_font_size = self.buffer_font_size.clamp(6.0, 48.0);
        self.tab_size = self.tab_size.clamp(1, 16);
        // A panel 4dp wide is a panel nobody can grab the edge of, and one
        // wider than a tablet leaves no editor at all. The UI clamps against
        // the *window* as well; this is the hand-edited-file guard.
        self.project_panel.default_width = self.project_panel.default_width.clamp(120.0, 900.0);
        for panel in [
            &mut self.git_panel,
            &mut self.project_search,
            &mut self.preview,
        ] {
            panel.default_width = panel.default_width.clamp(120.0, 900.0);
        }
        self
    }
}

/// The commented file written on first run. The comments are the
/// documentation, and `settings_json` preserves them through UI edits.
const DEFAULT_FILE: &str = r#"// Conquest Code settings.
//
// This file is yours: comments and formatting survive changes made from the
// settings screen. Keys follow Zed's names where they mean the same thing.
{
  // "system" follows the device's light/dark setting; "light" and "dark"
  // pin it.
  "theme": "system",

  // Editor text size, in scale-independent pixels.
  "buffer_font_size": 14,

  // Spaces inserted by the Tab key.
  "tab_size": 4,

  // What a line longer than the editor does: "none" scrolls it off the
  // right edge, "editor_width" wraps it.
  "soft_wrap": "none",

  "git": {
    // Who last touched the line the caret is on, shown after the end of it.
    // Only while the file has no unsaved edits — blame describes the file on
    // disk, and once it is edited the line numbers describe a file that is
    // not there any more.
    "inline_blame": { "enabled": true }
  },

  // Which side each panel docks on, and how wide it opens. "left" or
  // "right"; the terminal has the bottom to itself. Two panels on the same
  // side take turns — opening one closes the other — and the two sides are
  // independent, so a tree on the left and git on the right stay up together.
  "project_panel": {
    // Gitignored files in the tree: "show", "dimmed" or "hide".
    "gitignored_files": "dimmed",
    "dock": "left",
    "default_width": 240
  },

  "git_panel": {
    "dock": "right",
    "default_width": 360
  },

  "project_search": {
    "dock": "right",
    "default_width": 360
  },

  "preview": {
    "dock": "right",
    "default_width": 400
  }
}
"#;

fn settings_path_slot() -> &'static Mutex<Option<PathBuf>> {
    static PATH: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();
    PATH.get_or_init(|| Mutex::new(None))
}

/// Point the settings at a directory. Called from [`crate::initialize`].
pub(crate) fn set_directory(directory: PathBuf) {
    *settings_path_slot().lock().unwrap() = Some(directory.join("settings.json"));
}

fn settings_path() -> Option<PathBuf> {
    settings_path_slot().lock().unwrap().clone()
}

impl crate::Engine {
    /// The settings file's contents, creating it with documented defaults on
    /// first use. Empty if the engine was never given a directory.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn settings_text(&self) -> String {
        let Some(path) = settings_path() else {
            return String::new();
        };
        match std::fs::read_to_string(&path) {
            Ok(text) => text,
            Err(_) => {
                if let Some(parent) = path.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                // Best-effort: if the write fails we still hand back the
                // defaults, so the app runs with a read-only settings file
                // rather than not at all.
                let _ = std::fs::write(&path, DEFAULT_FILE);
                DEFAULT_FILE.to_owned()
            }
        }
    }

    /// The resolved settings. A malformed file falls back to defaults rather
    /// than failing: the user must always be able to reach the settings
    /// screen and fix it.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn settings(&self) -> Settings {
        let text = self.settings_text();
        if text.is_empty() {
            return Settings::default();
        }
        match settings_json::parse_json_with_comments::<Settings>(&text) {
            Ok(settings) => settings.sanitized(),
            Err(err) => {
                log::warn!("settings.json is not valid, using defaults: {err}");
                Settings::default()
            }
        }
    }

    /// Whether the settings file currently parses. The UI uses this to say so
    /// rather than silently showing defaults that aren't in effect.
    pub fn settings_are_valid(&self) -> bool {
        let text = self.settings_text();
        text.is_empty() || settings_json::parse_json_with_comments::<Settings>(&text).is_ok()
    }

    /// Set one key, given as a path (`["project_panel", "show_ignored"]`),
    /// preserving the rest of the file including comments. Returns the
    /// resolved settings afterwards.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn set_setting(
        &self,
        key_path: &[&str],
        value: serde_json::Value,
    ) -> Result<Settings, EngineError> {
        let Some(path) = settings_path() else {
            return Err(EngineError::NoSettingsFile);
        };
        let original = self.settings_text();
        let mut text = original.clone();
        let indent = settings_json::infer_json_indent_size(&text).max(1);
        // Surgical: this returns the byte range of just that key's value (or
        // where to insert it), so everything around it — comments included —
        // is untouched.
        let (range, replacement) =
            settings_json::replace_value_in_json_text(&text, key_path, indent, Some(&value), None);
        text.replace_range(range, &replacement);
        // A value of the wrong shape — a string where an enum has two names,
        // a bool where a number goes — does not break one setting, it breaks
        // the *file*, and `settings()` answers an unparseable file with the
        // defaults. Written blind, one bad key silently reset every other one.
        // So: if the file parsed before and does not now, this write is what
        // broke it, and it is put back.
        let was_valid = settings_json::parse_json_with_comments::<Settings>(&original).is_ok();
        if was_valid && settings_json::parse_json_with_comments::<Settings>(&text).is_err() {
            return Err(EngineError::InvalidSettings(format!(
                "\"{}\" cannot be set to {value}",
                key_path.join(".")
            )));
        }
        std::fs::write(&path, &text).map_err(|err| EngineError::Io {
            path: path.display().to_string(),
            message: err.to_string(),
        })?;
        Ok(self.settings())
    }

    /// Replace the whole file — what an "edit as JSON" screen would save.
    /// Rejects text that doesn't parse, so the app can't be configured into
    /// a state where the settings screen shows defaults it isn't using.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn set_settings_text(&self, text: &str) -> Result<Settings, EngineError> {
        let Some(path) = settings_path() else {
            return Err(EngineError::NoSettingsFile);
        };
        let parsed = settings_json::parse_json_with_comments::<Settings>(text)
            .map_err(|err| EngineError::InvalidSettings(err.to_string()))?;
        std::fs::write(&path, text).map_err(|err| EngineError::Io {
            path: path.display().to_string(),
            message: err.to_string(),
        })?;
        Ok(parsed.sanitized())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;
    use serde_json::json;

    /// The settings path is process-global, so these take turns.
    fn with_settings_dir<T>(body: impl FnOnce(&Engine, &std::path::Path) -> T) -> T {
        static LOCK: Mutex<()> = Mutex::new(());
        let _guard = LOCK.lock().unwrap_or_else(|err| err.into_inner());
        let dir = tempfile::tempdir().unwrap();
        set_directory(dir.path().to_path_buf());
        let engine = Engine::new();
        body(&engine, dir.path())
    }

    #[test]
    fn first_read_writes_a_documented_default_file() {
        with_settings_dir(|engine, dir| {
            let settings = engine.settings();
            assert_eq!(settings, Settings::default());
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// Conquest Code settings."));
            assert!(text.contains("\"buffer_font_size\""));
        });
    }

    #[test]
    fn editing_a_key_preserves_comments_and_other_keys() {
        with_settings_dir(|engine, dir| {
            engine.settings_text(); // materialize the default file
            let updated = engine
                .set_setting(&["buffer_font_size"], json!(18))
                .unwrap();
            assert_eq!(updated.buffer_font_size, 18.0);

            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            // The point of settings_json: the prose survives.
            assert!(text.contains("// Conquest Code settings."));
            assert!(text.contains("// Spaces inserted by the Tab key."));
            assert!(text.contains("\"buffer_font_size\": 18"));
            // Untouched keys keep their values.
            assert!(text.contains("\"tab_size\": 4"));
        });
    }

    /// The documented default file has to *parse* as the settings it
    /// documents, or a first run writes a file the next read falls back from
    /// — silently, since a bad parse is defaults.
    #[test]
    fn the_default_file_is_the_default_settings() {
        let parsed: Settings =
            settings_json::parse_json_with_comments(DEFAULT_FILE).unwrap();
        assert_eq!(parsed, Settings::default());
    }

    /// Where a panel sits is a setting, and the file that documents it has to
    /// parse as what it documents — see the test above.
    #[test]
    fn a_panel_can_be_moved_to_the_other_side() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            assert_eq!(engine.settings().project_panel.dock, DockSide::Left);
            let updated = engine
                .set_setting(&["project_panel", "dock"], json!("right"))
                .unwrap();
            assert_eq!(updated.project_panel.dock, DockSide::Right);
            // The git panel is on the right by default and stays where it is.
            assert_eq!(updated.git_panel.dock, DockSide::Right);
            // A side that is not a side is refused rather than resetting the
            // whole file to defaults.
            assert!(
                engine
                    .set_setting(&["project_panel", "dock"], json!("bottom"))
                    .is_err()
            );
            assert_eq!(engine.settings().project_panel.dock, DockSide::Right);
        });
    }

    /// A hand-edited width that would leave no editor, or no grabbable edge.
    #[test]
    fn a_panel_width_out_of_range_is_clamped() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["git_panel", "default_width"], json!(5000))
                .unwrap();
            assert_eq!(updated.git_panel.default_width, 900.0);
        });
    }

    #[test]
    fn soft_wrap_takes_zeds_two_names_and_refuses_others() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["soft_wrap"], json!("editor_width"))
                .unwrap();
            assert_eq!(updated.soft_wrap, SoftWrap::EditorWidth);
            // A value that is not one of the two leaves the setting alone
            // rather than turning wrapping off under the user.
            assert!(engine.set_setting(&["soft_wrap"], json!("bounded")).is_err());
            assert_eq!(engine.settings().soft_wrap, SoftWrap::EditorWidth);
        });
    }

    /// The rollback above, on the key that has the most ways to be wrong.
    #[test]
    fn a_write_that_would_break_the_file_leaves_every_other_setting_alone() {
        with_settings_dir(|engine, dir| {
            engine.settings_text();
            engine.set_setting(&["tab_size"], json!(8)).unwrap();
            assert!(engine.set_setting(&["tab_size"], json!("eight")).is_err());
            // Not defaults: the bad write never reached the file.
            assert_eq!(engine.settings().tab_size, 8);
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("\"tab_size\": 8"));
            assert!(engine.settings_are_valid());
        });
    }

    #[test]
    fn nested_keys_can_be_set() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["project_panel", "gitignored_files"], json!("hide"))
                .unwrap();
            assert_eq!(
                updated.project_panel.gitignored_files,
                GitignoredFiles::Hide
            );
            assert_eq!(
                engine.settings().project_panel.gitignored_files,
                GitignoredFiles::Hide
            );
        });
    }

    #[test]
    fn an_unrecognised_value_falls_back_rather_than_breaking_the_file() {
        with_settings_dir(|engine, dir| {
            // A key we no longer understand (the old boolean form) must not
            // make the whole file unreadable.
            std::fs::write(
                dir.join("settings.json"),
                "{ \"project_panel\": { \"show_ignored\": false }, \"tab_size\": 2 }",
            )
            .unwrap();
            let settings = engine.settings();
            assert_eq!(settings.tab_size, 2);
            assert_eq!(
                settings.project_panel.gitignored_files,
                GitignoredFiles::Dimmed
            );
        });
    }

    #[test]
    fn a_users_own_comments_and_unknown_keys_survive() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                "{\n  // mine\n  \"tab_size\": 2,\n  \"future_option\": true\n}\n",
            )
            .unwrap();
            engine.set_setting(&["tab_size"], json!(8)).unwrap();

            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// mine"));
            assert!(text.contains("\"future_option\": true"));
            assert!(text.contains("\"tab_size\": 8"));
        });
    }

    #[test]
    fn a_broken_file_falls_back_to_defaults_and_says_so() {
        with_settings_dir(|engine, dir| {
            std::fs::write(dir.join("settings.json"), "{ this is not json").unwrap();
            assert_eq!(engine.settings(), Settings::default());
            assert!(!engine.settings_are_valid());
            // …and the file is left alone, so the user can repair it.
            assert_eq!(
                std::fs::read_to_string(dir.join("settings.json")).unwrap(),
                "{ this is not json"
            );
        });
    }

    #[test]
    fn out_of_range_values_are_clamped_not_obeyed() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                "{ \"buffer_font_size\": 0, \"tab_size\": 9999 }",
            )
            .unwrap();
            let settings = engine.settings();
            assert_eq!(settings.buffer_font_size, 6.0);
            assert_eq!(settings.tab_size, 16);
        });
    }

    #[test]
    fn whole_file_writes_reject_invalid_json() {
        with_settings_dir(|engine, dir| {
            engine.settings_text();
            let before = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(matches!(
                engine.set_settings_text("{ nope"),
                Err(EngineError::InvalidSettings(_))
            ));
            assert_eq!(
                std::fs::read_to_string(dir.join("settings.json")).unwrap(),
                before
            );

            let updated = engine.set_settings_text("{ \"tab_size\": 2 }").unwrap();
            assert_eq!(updated.tab_size, 2);
        });
    }
}
