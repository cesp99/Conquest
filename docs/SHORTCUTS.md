# Keyboard shortcuts

Conquest Code targets foldables, tablets and **Samsung DeX**, where a
keyboard and mouse are ordinary rather than exotic — and where, in DeX,
touch isn't available at all. Everything you can do by tapping should
also have a binding here.

Bindings are hard-coded for now. A user-editable Zed-style keymap JSON is
planned; see the roadmap.

## Command palette

You do not have to memorise the tables below. `Ctrl` `Shift` `P` (or `F1`, or
**Command palette…** in the `☰` menu) opens the palette: every command in
the workspace, searchable by name, with the chord that also runs it shown
beside it.

| Shortcut | Action |
|---|---|
| `Ctrl` `Shift` `P` / `F1` | Open the command palette |
| `↑` `↓`, `Ctrl` `P` / `Ctrl` `N`, `Tab` / `Shift` `Tab` | Move the selection |
| `Enter` | Run the selected command |
| `Esc`, or `Ctrl` `Shift` `P` again | Close it |

Commands are named the way Zed names them — `terminal panel: toggle`,
`pane: close active item` — so typing `term` finds everything the terminal
can do. Matching is fuzzy and the matched letters are highlighted, exactly
as in the file finder; typing an uppercase letter makes the search
case-sensitive. The commands you ran most recently come first.

A command that cannot run right now — saving with no file open, opening a
terminal with no project — is listed greyed rather than hidden, so the
palette is also the honest list of what exists. Commands this edition does
not have at all (cloning, without a userland) are not listed.

The palette is a touch surface as much as a keyboard one: tap a row to run
it, and the list stays above the soft keyboard while you type.

## Workspace

These work wherever focus is, including while you're typing in the
editor.

| Shortcut | Action |
|---|---|
| `Ctrl` `Shift` `P` / `F1` | Open the command palette |
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
| `Ctrl` `Shift` `G` | Clone a git repository into a new project |
| `Ctrl` `,` | Open settings |
| ``Ctrl` ` `` | Show/hide the terminal |
| ``Ctrl` `Shift` ` `` | Open another terminal |

In the file finder and project picker: `↑` `↓` move, `Enter` opens,
`Esc` closes.

In the project picker's forms — new project, and clone — `Enter` confirms,
`Tab` and `Shift` `Tab` move between fields, and `Esc` goes back to the
project list.

`Ctrl` `Shift` `G` exists only in the `full` edition. Cloning runs the git
inside the Linux userland, so the edition that has no userland leaves the
command and its menu entry out entirely rather than showing them greyed —
see [USERLAND.md](USERLAND.md).

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
| `Ctrl` `Shift` `S` / `O` / `B` / `,` | Save, projects, project panel, settings |
| `Ctrl` `Shift` `G` | Clone a repository (`full` edition) |
| `Ctrl` `Shift` `P` | Open the command palette |

`Ctrl` `Shift` `P` means the command palette here as everywhere else,
which is why *find file* is the one command with no shifted twin: from a
shell it is `Ctrl` `Shift` `P` and then "file". `F1` is not taken, because
it belongs to whatever is running in the terminal.

Sessions start in the project directory and keep running while the
terminal is hidden. They close when you switch projects. In the `full`
edition the shell runs inside Debian once you install it — see
[USERLAND.md](USERLAND.md).

## Mouse

- The editor shows a text cursor; tabs, tree rows and actions show a
  hand cursor. Dialog fields — the project name, the clone URL — show a
  text cursor too, so a mouse tells you where it can type.
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
file and settings commands live in the `☰` menu in the title bar, and
**Command palette…** at the top of that menu reaches everything else —
it is the touch route to any command that has no button of its own. The
project picker's own footer carries **New**, **Clone…** and **Import
folder**; a clone in progress shows what git is doing and a **Cancel**
that stops it and removes the half-cloned directory.

In the terminal, the row above the keyboard carries the keys a soft
keyboard doesn't have: `esc`, `tab`, `ctrl`, `alt`, arrows, `home` and
`end`. `ctrl` and `alt` latch for exactly one keypress, so `ctrl` then `c`
sends `^C`. Pinch to change the terminal's font size; long-press to select
text, with the usual handles and copy/paste toolbar.
