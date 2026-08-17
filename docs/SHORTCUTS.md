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
| `Ctrl` `F` | Find in the open file |
| `Ctrl` `Shift` `T` | Reopen the tab you closed last |
| `Ctrl` `Shift` `E` | Reveal the open file in the project panel |
| `Ctrl` `Shift` `G` | Clone a git repository into a new project |
| `Ctrl` `,` | Open settings |
| — | Pick a theme (`theme selector: toggle` in the palette, or the ☰ menu) |
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

## Find in file

`Ctrl` `F` opens a bar above the editor. Every match in the file is
highlighted and the current one is picked out; the count is honest about a
file with more matches than the engine will hand back at once.

| Shortcut | Action |
|---|---|
| `Enter` / `F3` | Next match |
| `Shift` `Enter` / `Shift` `F3` | Previous match |
| `Esc` | Close the bar and clear the highlights |
| `Aa` `ab` `.*` | Match case / whole word / regular expression |

A regular expression that does not compile yet — `[`, halfway through
typing — outlines the field rather than clearing the file's highlights and
claiming there are no results.

## Editor

### Moving around

| Shortcut | Action |
|---|---|
| `Home` / `End` | Start of the line — first press stops at the indent — / end of it |
| `Ctrl` `Home` / `Ctrl` `End` | Start / end of the file |
| `PageUp` / `PageDown` | A screenful, measured from what is actually on screen |
| `Ctrl` `←` / `Ctrl` `→` | One word (or one run of punctuation) at a time |
| `Shift` + any of the above | Select instead of jump |

Every one of them moves all your cursors, not just the first.


| Shortcut | Action |
|---|---|
| `Ctrl` `Z` | Undo |
| `Ctrl` `Shift` `Z` / `Ctrl` `Y` | Redo |
| `Ctrl` `A` | Select all |
| `Ctrl` `C` / `X` / `V` | Copy / cut / paste |
| `←` `→` `↑` `↓` | Move every cursor |
| `Shift` + arrows | Extend the selection |
| `Backspace` | Delete backwards (joins lines at column 0) |
| `Enter` | New line, keeping the indent |
| `Tab` | Insert one indent's worth of spaces |

Undo and redo, copy, cut, paste and select-all are also in the `☰` menu in
the title bar, with their shortcuts listed beside them.

### Multiple cursors

The bindings are Zed's, from its Linux keymap.

| Shortcut | Action |
|---|---|
| `Ctrl` `D` | Select the word under the cursor, then add a cursor on the next occurrence of it |
| `Ctrl` `Shift` `L` | Put a cursor on every occurrence at once |
| `Shift` `Alt` `↑` / `↓` | Add a cursor above / below (press the other way to take one back) |
| `Ctrl` `Alt` `↑` / `↓` | The same, for keyboards that spend `Shift` `Alt` on a layout switch |
| `Alt` + click | Place a cursor where you click |
| `Esc` | Back to one cursor, then to no selection |

Everything else applies to every cursor at once: typing, `Backspace`,
paste, the line operations below, and comment toggling. A multi-cursor
edit is one step in the undo history, so a single `Ctrl` `Z` takes all of
it back.

### Lines

| Shortcut | Action |
|---|---|
| `Alt` `↑` / `↓` | Move the line (or the selected lines) up or down |
| `Ctrl` `Alt` `Shift` `↑` / `↓` | Duplicate the line above or below |
| `Ctrl` `Shift` `K` | Delete the line |
| `Ctrl` `Shift` `J` | Join the next line onto this one |
| `Ctrl` `/` | Comment or uncomment, with the token for the file's language |

`Ctrl` `/` does nothing in a language with no line comment — CSS,
Markdown and diffs among them.

### Brackets, quotes and indentation

These need no binding; they are how typing behaves.

- Typing an opening bracket or quote brings its partner with it, unless
  what follows the cursor is a word — the closer would land in the middle
  of it. A quote right after a word character stays a plain apostrophe,
  so `don't` types as you'd expect.
- Typing the closer when it is already in front of the cursor steps over
  it rather than doubling it.
- With text selected, an opening bracket or quote wraps the selection
  instead of replacing it, and the selection survives.
- `Backspace` between the two halves of an empty pair deletes both.
- `Enter` keeps the current line's indent, adds one level after an
  opening bracket (or after a `:` in Python), and if the closing bracket
  was waiting on the other side of the cursor it goes down onto a line of
  its own.

Indent width is the `tab_size` setting. Whether it is tabs or spaces is
read off the line you are on, so an existing file keeps its own style.

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
copy/paste.

While the soft keyboard is up, a row above it carries the editor commands
the on-screen keyboard has no keys for: `esc`, `tab`, undo and redo, add a
cursor above or below, select the next occurrence, move a line up or down,
duplicate, delete and join lines, and toggle the comment. It appears with
the keyboard and goes away with it, so a paired keyboard or DeX never
sees it.

The status bar carries a **Save** action while the current
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
