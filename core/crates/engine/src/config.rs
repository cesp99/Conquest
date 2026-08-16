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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ProjectPanelSettings {
    /// Show gitignored entries in the project tree (dimmed).
    pub show_ignored: bool,
}

impl Default for ProjectPanelSettings {
    fn default() -> Self {
        Self { show_ignored: true }
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
    pub project_panel: ProjectPanelSettings,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme: ThemeMode::System,
            buffer_font_size: 14.0,
            tab_size: 4,
            project_panel: ProjectPanelSettings::default(),
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

  "project_panel": {
    // Show gitignored files in the tree, dimmed.
    "show_ignored": true
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
        let mut text = self.settings_text();
        let indent = settings_json::infer_json_indent_size(&text).max(1);
        // Surgical: this returns the byte range of just that key's value (or
        // where to insert it), so everything around it — comments included —
        // is untouched.
        let (range, replacement) =
            settings_json::replace_value_in_json_text(&text, key_path, indent, Some(&value), None);
        text.replace_range(range, &replacement);
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

    #[test]
    fn nested_keys_can_be_set() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["project_panel", "show_ignored"], json!(false))
                .unwrap();
            assert!(!updated.project_panel.show_ignored);
            assert!(!engine.settings().project_panel.show_ignored);
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
