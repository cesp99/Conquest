# Keyboard shortcuts

Conquest Code targets foldables, tablets and **Samsung DeX**, where a
keyboard and mouse are ordinary rather than exotic — and where, in DeX,
touch isn't available at all. Everything you can do by tapping should
also have a binding here.

Bindings are hard-coded for now. A user-editable Zed-style keymap JSON is
planned; see the roadmap.

## Workspace

These work wherever focus is, including while you're typing in the
editor.

| Shortcut | Action |
|---|---|
| `Ctrl` `S` | Save the active file |
| `Ctrl` `W` | Close the active tab |
| `Ctrl` `Tab` | Next tab (wraps) |
| `Ctrl` `Shift` `Tab` | Previous tab (wraps) |
| `Ctrl` `PageDown` / `PageUp` | Next / previous tab |
| `Ctrl` `1`…`8` | Jump to tab by position |
| `Ctrl` `9` | Jump to the last tab |
| `Ctrl` `B` | Show/hide the project panel |

## Editor

| Shortcut | Action |
|---|---|
| `Ctrl` `Z` | Undo |
| `Ctrl` `Shift` `Z` / `Ctrl` `Y` | Redo |
| `Ctrl` `A` | Select all |
| `Ctrl` `C` / `X` / `V` | Copy / cut / paste |
| `←` `→` `↑` `↓` | Move the cursor |
| `Shift` + arrows | Extend the selection |
| `Backspace` | Delete backwards (joins lines at column 0) |
| `Enter` | Insert a line break |

## Mouse

- The editor shows a text cursor; tabs, tree rows and actions show a
  hand cursor.
- Click a file in the project panel to open it, a directory to expand
  or collapse it.
- Click a tab to switch to it, its `✕` to close it, and its unsaved-changes
  dot to save — the dot is a save button, which matters because the soft
  keyboard covers the status bar while you type.

## Touch

Everything above is reachable by touch too: long-press to select a word,
drag the handles to adjust a selection, and use the floating toolbar for
copy/paste. The status bar carries a **Save** action while the current
file has unsaved changes, and `☰ files` toggles the project panel.
