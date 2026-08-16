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
| `Ctrl` `O` | Open the project picker (switch, create, import, export) |
| `Ctrl` `P` | Find a file by name (fuzzy) |
| `Ctrl` `,` | Open settings |
| ``Ctrl` ` `` | Show/hide the terminal |
| ``Ctrl` `Shift` ` `` | Open another terminal |

In the file finder and project picker: `↑` `↓` move, `Enter` opens,
`Esc` closes.

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

All of these are also in the `☰` menu in the title bar, with their
shortcuts listed beside them.

## Terminal

The shell gets the keyboard. Every plain `Ctrl`+letter goes straight to
it — `Ctrl` `C` interrupts, `Ctrl` `D` ends input, `Ctrl` `R` searches
history — and so do `Esc`, `Alt` chords and the function keys, because vi
and htop need them. The workspace keeps only these:

| Shortcut | Action |
|---|---|
| ``Ctrl` ` `` | Hide the terminal and return to the editor |
| ``Ctrl` `Shift` ` `` | Open another terminal |
| `Ctrl` `Tab` / `Ctrl` `Shift` `Tab` | Next / previous terminal |
| `Ctrl` `PageDown` / `PageUp` | Next / previous editor tab |
| `Ctrl` `Shift` `W` | Close this terminal |
| `Ctrl` `Shift` `V` | Paste (`Ctrl` `V` would be a control code) |
| `Ctrl` `Shift` `S` / `P` / `O` / `B` / `,` | Save, find file, projects, project panel, settings |

Sessions start in the project directory and keep running while the
terminal is hidden. They close when you switch projects. In the `full`
edition the shell runs inside Debian once you install it — see
[USERLAND.md](USERLAND.md).

## Mouse

- The editor shows a text cursor; tabs, tree rows and actions show a
  hand cursor.
- Click a file in the project panel to open it, a directory to expand
  or collapse it.
- Click a tab to switch to it, its `✕` to close it, and its unsaved-changes
  dot to save — the dot is a save button, which matters because the soft
  keyboard covers the status bar while you type.
- In the terminal: the wheel scrolls the scrollback, drag selects, and the
  bar between the editor and the terminal drags to resize the dock.

## Touch

Everything above is reachable by touch too: long-press to select a word,
drag the handles to adjust a selection, and use the floating toolbar for
copy/paste. The status bar carries a **Save** action while the current
file has unsaved changes, `☰ files` toggles the project panel, `▤` toggles the project
panel and `⌕` opens the file finder, and `❯_` opens the terminal. Project,
file and settings commands live in the `☰` menu in the title bar.

In the terminal, the row above the keyboard carries the keys a soft
keyboard doesn't have: `esc`, `tab`, `ctrl`, `alt`, arrows, `home` and
`end`. `ctrl` and `alt` latch for exactly one keypress, so `ctrl` then `c`
sends `^C`. Pinch to change the terminal's font size; long-press to select
text, with the usual handles and copy/paste toolbar.
