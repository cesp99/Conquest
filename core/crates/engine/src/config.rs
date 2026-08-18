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

use std::collections::BTreeMap;
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
    /// The panel is switched off: no status-bar button, and its commands
    /// refuse. This app's third value rather than Zed's — Zed hides a
    /// panel's *button* with a separate per-panel `"button": false` — folded
    /// into `dock` here by the owner's design: one row per panel, three
    /// answers, and a hidden panel costs no second key.
    Hidden,
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
        Self {
            dock,
            default_width,
        }
    }
}

impl Default for PanelSettings {
    fn default() -> Self {
        Self::new(DockSide::Right, 360.0)
    }
}

/// One ACP agent the user configured by hand.
///
/// Zed's `agent_servers` entry, with the same three keys it uses
/// (settings_content/src/agent.rs:740-748): `command`, `args`, `env`. Zed's
/// has four more for modes and config-option defaults; those describe
/// surfaces this panel does not have, and a setting that does nothing is
/// worse than no setting.
///
/// The command is resolved **inside the guest**, not on Android: it is a
/// program on Debian's PATH, or an absolute path within it. That is the whole
/// reason a custom agent is possible at all — the engine already knows how to
/// enter the userland, so any program that speaks ACP on stdio is an agent.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct CustomAgent {
    pub command: String,
    pub args: Vec<String>,
    pub env: BTreeMap<String, String>,
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
    /// The agent panel — Zed's `agent_panel.dock` and `default_width`.
    pub agent_panel: PanelSettings,
    /// ACP agents the user configured, by the name the panel lists them under.
    ///
    /// A `BTreeMap`, not a `HashMap`, and that is not a detail: this is what
    /// the picker is built from, and a hash map would reorder the list on
    /// every launch.
    ///
    /// Deserialized leniently, per entry: settings.json is a file people edit
    /// by hand, and one half-written agent must cost that one entry, not the
    /// whole parse — a strict map here silently reset *every* setting to its
    /// default the moment someone typed `"Claude": "claude"`.
    #[serde(deserialize_with = "lenient_agent_servers")]
    pub agent_servers: BTreeMap<String, CustomAgent>,
}

/// Each `agent_servers` entry parsed on its own, a broken one dropped with a
/// log rather than sinking its neighbours — and, because a bad field anywhere
/// fails the whole `Settings` parse, rather than sinking the entire file. The
/// Kotlin parser (`AppSettings.parseAgents`) is lenient the same way.
fn lenient_agent_servers<'de, D>(deserializer: D) -> Result<BTreeMap<String, CustomAgent>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    let raw: serde_json::Map<String, serde_json::Value> = match value {
        serde_json::Value::Object(map) => map,
        other => {
            log::warn!("settings: agent_servers is {other} rather than an object; ignored");
            return Ok(BTreeMap::new());
        }
    };
    Ok(raw
        .into_iter()
        .filter_map(|(name, value)| match serde_json::from_value(value) {
            Ok(agent) => Some((name, agent)),
            Err(err) => {
                log::warn!("settings: agent_servers entry {name:?} is malformed ({err}); dropped");
                None
            }
        })
        .collect())
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
            agent_panel: PanelSettings::new(DockSide::Right, 400.0),
            agent_servers: BTreeMap::new(),
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
            &mut self.agent_panel,
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
  },

  "agent_panel": {
    "dock": "right",
    "default_width": 400
  },

  // ACP agents, by the name the panel lists them under. The command runs
  // inside the Linux userland, so it is a program on Debian's PATH — which
  // is what "npm install -g" puts one on. Anything that speaks the Agent
  // Client Protocol on stdin and stdout works here; the editor installs
  // nothing for you.
  //
  //   "agent_servers": {
  //     "Claude Code": { "command": "claude-code-acp" },
  //     "Gemini CLI": { "command": "gemini", "args": ["--experimental-acp"] }
  //   }
  "agent_servers": {}
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
        self.edit_settings_value(key_path, Some(value))
    }

    /// Add or replace one `agent_servers` entry — what the settings screen's
    /// Add Agent form saves, mirroring Zed's own form, which writes the same
    /// key (settings_ui/src/pages/external_agents_page.rs:762-770).
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn set_agent_server(
        &self,
        name: &str,
        agent: CustomAgent,
    ) -> Result<Settings, EngineError> {
        let value = serde_json::to_value(&agent)
            .map_err(|err| EngineError::InvalidSettings(err.to_string()))?;
        // The name goes into the path verbatim — never through the bridge's
        // dot-split `set_setting` route, where an agent called "my.agent"
        // would silently become a nested object.
        self.edit_settings_value(&["agent_servers", name], Some(value))
    }

    /// Remove one `agent_servers` entry, as Zed's trash button does
    /// (external_agents_page.rs:226-249). Removing a name that is not there
    /// is not an error: the entry is gone either way.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn remove_agent_server(&self, name: &str) -> Result<Settings, EngineError> {
        self.edit_settings_value(&["agent_servers", name], None)
    }

    fn edit_settings_value(
        &self,
        key_path: &[&str],
        value: Option<serde_json::Value>,
    ) -> Result<Settings, EngineError> {
        let Some(path) = settings_path() else {
            return Err(EngineError::NoSettingsFile);
        };
        let original = self.settings_text();
        let mut text = original.clone();
        let indent = settings_json::infer_json_indent_size(&text).max(1);
        // Surgical: this returns the byte range of just that key's value (or
        // where to insert it — or, with `None`, what to cut), so everything
        // around it, comments included, is untouched.
        let (range, replacement) = settings_json::replace_value_in_json_text(
            &text,
            key_path,
            indent,
            value.as_ref(),
            None,
        );
        text.replace_range(range, &replacement);
        // A value of the wrong shape — a string where an enum has two names,
        // a bool where a number goes — does not break one setting, it breaks
        // the *file*, and `settings()` answers an unparseable file with the
        // defaults. Written blind, one bad key silently reset every other one.
        // So: if the file parsed before and does not now, this write is what
        // broke it, and it is put back.
        let was_valid = settings_json::parse_json_with_comments::<Settings>(&original).is_ok();
        if was_valid && settings_json::parse_json_with_comments::<Settings>(&text).is_err() {
            return Err(EngineError::InvalidSettings(match &value {
                Some(value) => format!("\"{}\" cannot be set to {value}", key_path.join(".")),
                None => format!("\"{}\" cannot be removed", key_path.join(".")),
            }));
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
        let parsed: Settings = settings_json::parse_json_with_comments(DEFAULT_FILE).unwrap();
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
            assert!(
                engine
                    .set_setting(&["soft_wrap"], json!("bounded"))
                    .is_err()
            );
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

    /// A hand-configured agent, in Zed's own `agent_servers` shape.
    ///
    /// This is what makes "any ACP agent" true rather than "the two we
    /// happen to name": the command is resolved inside the guest, so any
    /// program on Debian's PATH that speaks the protocol is an agent.
    #[test]
    fn a_custom_agent_is_read_from_settings() {
        with_settings_dir(|engine, _dir| {
            let settings = engine
                .set_settings_text(
                    r#"{
                        "agent_servers": {
                            "My agent": {
                                "command": "python3",
                                "args": ["/root/agent.py", "--acp"],
                                "env": { "TOKEN": "x" }
                            },
                            "Bare": { "command": "some-agent" }
                        }
                    }"#,
                )
                .unwrap();

            assert_eq!(settings.agent_servers.len(), 2);
            let mine = &settings.agent_servers["My agent"];
            assert_eq!(mine.command, "python3");
            assert_eq!(mine.args, ["/root/agent.py", "--acp"]);
            assert_eq!(mine.env.get("TOKEN").map(String::as_str), Some("x"));
            // Everything but the command is optional.
            let bare = &settings.agent_servers["Bare"];
            assert_eq!(bare.command, "some-agent");
            assert!(bare.args.is_empty());
            assert!(bare.env.is_empty());

            // Sorted, because this list *is* the picker and a hash map would
            // reorder it on every launch.
            let names: Vec<&str> = settings.agent_servers.keys().map(String::as_str).collect();
            assert_eq!(names, ["Bare", "My agent"]);
        });
    }

    /// A half-written `agent_servers` entry costs that entry, nothing else.
    ///
    /// It used to cost everything: the strict map failed the whole `Settings`
    /// parse, so `"Claude": "claude"` — the obvious first guess at the shape —
    /// silently reset the theme, the font size and every panel to defaults.
    /// The Kotlin parser was already lenient; now both sides agree.
    #[test]
    fn a_malformed_agent_entry_costs_only_itself() {
        with_settings_dir(|engine, _dir| {
            let settings = engine
                .set_settings_text(
                    r#"{
                        "theme": "dark",
                        "agent_servers": {
                            "Not an object": "claude",
                            "Wrong types": { "command": 7 },
                            "Works": { "command": "fine-agent" }
                        }
                    }"#,
                )
                .unwrap();
            let names: Vec<&str> = settings.agent_servers.keys().map(String::as_str).collect();
            assert_eq!(names, ["Works"]);
            // The rest of the file still counted — the parse did not fall
            // back to defaults.
            assert_eq!(settings.theme, ThemeMode::Dark);

            // And `agent_servers` itself being rubbish ignores the key, not
            // the file.
            let settings = engine
                .set_settings_text(r#"{ "theme": "dark", "agent_servers": 17 }"#)
                .unwrap();
            assert!(settings.agent_servers.is_empty());
            assert_eq!(settings.theme, ThemeMode::Dark);
        });
    }

    /// The settings screen's Add Agent form and its trash button, at the
    /// engine seam: an entry written by name lands under `agent_servers`
    /// exactly, comments survive, and removal takes only that entry. The name
    /// goes into the key path verbatim — a dot in it must not open a nested
    /// object, which is what the dot-split `setSetting` route would do.
    #[test]
    fn an_agent_server_can_be_added_and_removed_by_name() {
        with_settings_dir(|engine, dir| {
            engine.settings_text(); // materialize the commented default file
            let agent = CustomAgent {
                command: "python3".to_owned(),
                args: vec!["/root/agent.py".to_owned()],
                env: BTreeMap::from([("KEY".to_owned(), "v".to_owned())]),
            };
            let settings = engine.set_agent_server("my.agent", agent.clone()).unwrap();
            assert_eq!(settings.agent_servers.get("my.agent"), Some(&agent));

            // Replacing the same name is an edit, not a second entry.
            let mut edited = agent.clone();
            edited.command = "node".to_owned();
            let settings = engine.set_agent_server("my.agent", edited.clone()).unwrap();
            assert_eq!(settings.agent_servers.len(), 1);
            assert_eq!(settings.agent_servers.get("my.agent"), Some(&edited));

            // A neighbour survives the removal, and so do the file's comments.
            engine
                .set_agent_server(
                    "other",
                    CustomAgent {
                        command: "other-agent".to_owned(),
                        ..CustomAgent::default()
                    },
                )
                .unwrap();
            let settings = engine.remove_agent_server("my.agent").unwrap();
            let names: Vec<&str> = settings.agent_servers.keys().map(String::as_str).collect();
            assert_eq!(names, ["other"]);
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// Conquest Code settings."));
            assert!(!text.contains("my.agent"));

            // Removing what is not there is not an error — it is gone.
            assert!(engine.remove_agent_server("my.agent").is_ok());
        });
    }

    /// `"hidden"` is a real third answer for a panel's dock: it parses, it
    /// survives a write, and it round-trips to the app as `"hidden"`.
    #[test]
    fn a_panel_can_be_hidden_by_its_dock_setting() {
        with_settings_dir(|engine, _dir| {
            let updated = engine
                .set_setting(&["git_panel", "dock"], serde_json::json!("hidden"))
                .unwrap();
            assert_eq!(updated.git_panel.dock, DockSide::Hidden);
            // And back from disk, not only from the in-memory return.
            assert_eq!(engine.settings().git_panel.dock, DockSide::Hidden);
            let json = serde_json::to_value(engine.settings()).unwrap();
            assert_eq!(json["git_panel"]["dock"], "hidden");
        });
    }

    /// The agent panel's dock is a real setting, not one the engine drops.
    ///
    /// It was: the settings screen wrote `agent_panel.dock` and `Settings` had
    /// no such field, so serde ignored it and the row did nothing at all.
    #[test]
    fn the_agent_panels_dock_survives_a_write() {
        with_settings_dir(|engine, _dir| {
            assert_eq!(engine.settings().agent_panel.dock, DockSide::Right);
            let updated = engine
                .set_setting(&["agent_panel", "dock"], serde_json::json!("left"))
                .unwrap();
            assert_eq!(updated.agent_panel.dock, DockSide::Left);
            // And it is still there when the file is read back from disk.
            assert_eq!(engine.settings().agent_panel.dock, DockSide::Left);
        });
    }
}
