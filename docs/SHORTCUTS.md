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
| `Ctrl` `Shift` `F` | Search every file in the project |
| `Ctrl` `G` | Go to a line (and column) |
| `Ctrl` `Shift` `M` | Show/hide the preview of the open file |
| `Ctrl` `Shift` `T` | Reopen the tab you closed last |
| `Ctrl` `Shift` `E` | Reveal the open file in the project panel |
| `Ctrl` `Shift` `G` | Show/hide the git panel |
| `Ctrl` `,` | Open settings |
| — | Pick a theme (`theme selector: toggle` in the palette, or the ☰ menu) |
| ``Ctrl` ` `` | Show/hide the terminal |
| ``Ctrl` `Shift` ` `` | Open another terminal |

In the file finder and project picker: `↑` `↓` move, `Enter` opens,
`Esc` closes.

In the project picker's forms — new project, and clone — `Enter` confirms,
`Tab` and `Shift` `Tab` move between fields, and `Esc` goes back to the
project list.

**Clone** has no chord: `Ctrl` `Shift` `G` is the git panel in Zed and is the
git panel here, and cloning is something one does once per repository. It is in
the command palette, the `☰` menu and the project picker's own footer. It also
exists only in the `full` edition — cloning runs the git inside the Linux
userland, so the edition with no userland leaves the command and its menu entry
out entirely rather than showing them greyed — see [USERLAND.md](USERLAND.md).

## Git in the editor

The gutter carries a bar down its left edge for every line that differs from
the last commit — Zed's own strip, at Zed's own width (floor of 0.275 × the
line height) and in the colours the project panel already uses: added,
modified, and a rounded pill on the boundary where lines were deleted.

The end of the caret's line says who last touched it — Zed's `inline_blame`,
on by default as in Zed, and switchable in **Settings** → *Inline blame*. It
appears only while the file has **no unsaved edits**: blame describes the file
on disk, and once it is edited those line numbers describe a file that is not
there any more. It runs git when the file is opened and after each save, never
on a keystroke.

## Wrapping long lines

Off by default, as in Zed. **Settings** → *Wrap long lines*, `☰` → **Wrap long
lines**, or the command palette's `editor: toggle soft wrap` — Zed's own action,
which it binds to `Ctrl` `K` `Ctrl` `Z`, a two-key sequence this keymap cannot
express yet. Whichever route, it writes `soft_wrap` to settings.json, so it
survives a restart.

Wrapped or not, the caret keys mean what they always meant: `Home` and `End` go
to the ends of the *line*, not of the screen row.

## Git panel

`Ctrl` `Shift` `G` shows the changes in the project — Zed's
`git_panel::ToggleFocus`, on Zed's own chord. Press it again to put the
keyboard back on the file list. The branch button at the left of the status bar
is the same thing for a finger or a mouse, and `☰` → **Git panel** is the route
out of a focused terminal.

| Shortcut | Action |
|---|---|
| `↑` / `↓`, `PageUp` / `PageDown` | Move through the changed files |
| `Space` | Stage or unstage the selected file |
| `Enter` | Open it |
| `Delete` / `Backspace` | Discard its changes, after a prompt that names it |
| `Ctrl` `Enter` | Commit what is staged, from anywhere in the panel |
| `Esc`, or `✕` | Close the panel |

On a wide screen it docks beside the editor; on a phone it takes the work area.
The dock shows one panel at a time — git, project search or the preview — which
is a dock's rule in Zed too, and is what stops three of them sharing a phone
screen and leaving the editor a character wide.

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

## Go to line

`Ctrl` `G` opens a small panel over the top of the editor. Type `42` for a
line, or `42:8` for a line and a column — a comma works as well as the colon,
because a soft keyboard hides the colon behind a modifier and the digit row
already has the comma.

The caret moves **as you type**, so you can watch the file scroll past and
stop where you meant to. A number past the end of the file lands on the last
line rather than being refused.

| Shortcut | Action |
|---|---|
| `Enter`, or `↵` | Keep the caret where it landed |
| `Esc`, or `✕` | Put the caret, the selection and the view back where they were |

Cancelling really does put everything back: the selection you had, every extra
cursor, and the exact scroll position — not just the line number.

`Ctrl` `G` is deliberately not offered while a terminal has the keyboard, where
it is BEL and readline's abort.

## Preview

`Ctrl` `Shift` `M` previews the open file — a `.md` rendered, or a `.svg`
drawn. Zed offers exactly these two, from one 👁 button in its toolbar
(`quick_action_bar/preview.rs`), and so does this: the eye appears at the right
of the toolbar whenever the open file has a preview, and nothing appears when
it does not. Zed's own chord is `Ctrl` `Shift` `V`, which here is paste in the
editor, paste in the project panel and paste in the terminal — a workspace
command may not take a clipboard chord from any of them.
On a wide screen it docks beside the editor and its left edge drags to resize;
on a phone it takes the work area, the way project search and the terminal do.
`✕` in its title bar closes it, which is the route for a finger.

It follows the buffer: type in the editor and the rendering catches up, without
either pane's scrolling moving the other. The preview keeps its own place on
the page while you edit.

An SVG is text first — Zed keeps it out of its image viewer by name and opens
it in the editor, because whoever opens an icon file is usually editing it — so
the drawing is a preview like the Markdown one. Pinch or `Ctrl` `+` / `Ctrl` `-`
to zoom, drag to pan, double tap or `Ctrl` `0` to put it back. Gradients,
filters, masks and text are named under the drawing rather than drawn wrong.

A picture that is *only* a picture — `.png`, `.jpg`, `.webp` and the rest —
opens as one instead, with no buffer behind it: nothing to save, nothing to be
dirty, and `Ctrl` `S` and `Ctrl` `F` are refused on it rather than doing
nothing quietly.

| Shortcut | Action |
|---|---|
| `PageUp` / `PageDown` | A screenful |
| `↑` / `↓` | A few lines |
| `Ctrl` `Home` / `Ctrl` `End` | Top / bottom |
| `Esc`, or `✕` | Close it |

The scrolling keys apply once the preview has the focus — click or tap it
first; that is deliberate, so opening the preview never takes the keyboard away
from the file you are writing.

What it renders is what a README uses: headings, **bold**, *italic*,
`code`, ~~strikethrough~~, links (inline, reference and bare), nested and
ordered lists, task lists, block quotes and GitHub's `> [!NOTE]` alerts,
tables, horizontal rules and fenced code blocks — **coloured by the same
tree-sitter grammars that colour the editor**, so a Rust fence in a README
looks like Rust.

Two deliberate limits. **Images are named, not drawn**: an image shows as
`[image: alt text]`, because drawing it would mean fetching it, and this editor
does not make network requests you did not ask for. And there is **no WebView**
behind any of this — it is drawn in Compose with the colours of your Zed theme,
so it changes when the theme does.

A link to `https://…` opens in your browser. A link to another file in the
project opens that file in a tab.

## Search all files

`Ctrl` `Shift` `F` opens project search. On a wide screen it docks beside
the editor and its left edge drags to resize; on a phone it takes the work
area, the way the terminal does. **Search all files…** in the `☰` menu
opens it too — which is the route from a terminal, where the shell keeps
`Ctrl` `Shift` `F` for itself.

Results arrive while the search runs: files appear as they are found, and
the line under the query says how far the walk has got — `12 results in 3
files · searched 480 of 1200` — with a progress bar that leaves when the
search finishes. A project still being scanned says so and waits, rather
than answering "no results" over half a repository.

| Shortcut | Action |
|---|---|
| `↑` `↓` | Move through files and matches |
| `PageUp` / `PageDown` | Move ten rows |
| `Enter` | Open the selected match, or fold the selected file |
| `Esc` | Close the panel and stop the search |
| `Aa` `ab` `.*` | Match case / whole word / regular expression |
| `⋯` | Show the include and exclude patterns |
| `⊘` | Also search files git ignores |

The query keeps the caret while the arrows walk the results, so you can
keep typing without clicking back into the field. Clicking or tapping a
match opens the file with the cursor on the hit; clicking a file's row
folds its matches away.

Include and exclude take comma-separated globs — `src/**/*.rs`,
`vendor/*, *.lock` — and a pattern that isn't a valid glob outlines both
fields rather than searching the whole project instead.

Files the search cannot read honestly are counted but skipped: anything
over 4 MiB, anything holding a NUL byte, and anything that is not UTF-8.
The result count is what the engine found, so a search that hit its own
limit says `limit reached` rather than quietly showing you less.

Replacing across files is deliberately not here. It rewrites files you
have not opened, and it will arrive with its own confirmation.

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

`Ctrl` `/` uses the language's own tokens: `//` in Rust and Go, `#` in
Python, shell and YAML. A language with no line comment but a block one
gets that instead, wrapped around what you selected — `<!-- … -->` in
Markdown, `/* … */` in CSS — and pressing it again takes the delimiters
back off. A diff has neither, and there `Ctrl` `/` does nothing rather
than writing a token the format has no meaning for.

### Brackets, quotes and indentation

These need no binding; they are how typing behaves. Which pairs a
language has, and where, is the language's own business — the rules come
from the same grammar that colours the file.

- Typing an opening bracket or quote brings its partner with it, unless
  what follows the cursor is a word — the closer would land in the middle
  of it. A quote right after a word character stays a plain apostrophe,
  so `don't` types as you'd expect.
- **A quote typed inside a comment or a string stays a lone quote.** The
  editor asks where the cursor is in the file's syntax tree, so this is
  the real answer and not a guess about the characters around it.
- Openers longer than one character work too: `f"` in Python closes as
  one pair, and so do `r#"` in Rust and `/*` in C, Go and Rust.
- Typing the closer when it is already in front of the cursor steps over
  it rather than doubling it — inside a string as much as outside one.
- With text selected, an opening bracket or quote wraps the selection
  instead of replacing it, and the selection survives.
- `Backspace` between the two halves of an empty pair deletes both.
- `Enter` keeps the current line's indent and adds one level where the
  language says a block opens: after a bracket that expects a line of its
  own, after a `:` in Python, a `do` or `then` in a shell script, a key
  with nothing after it in YAML. If the closing bracket was waiting on
  the other side of the cursor it goes down onto a line of its own.

Rust's `<` is the one opener that never closes itself — it starts a
generic far less often than it is a comparison — but it still wraps a
selection, and `Enter` inside `<…>` still indents.

Indent width is the `tab_size` setting. Whether it is tabs or spaces is
read off the file you are in, so an existing file keeps its own style;
only a file with nothing to say falls back to the language (Go indents
with tabs).

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
| `Ctrl` `Shift` `G` | Show/hide the git panel |
| `Ctrl` `Shift` `P` | Open the command palette |

`Ctrl` `Shift` `V` is paste here and nothing else — and everywhere else, which
is why the preview is on `Ctrl` `Shift` `M` — and `Ctrl` `G` stays
BEL. Both are reachable from the `☰` menu, which is the route out of a focused
terminal.

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
- In project search: the wheel scrolls the results, a row lights up under
  the pointer, and the panel's left edge drags to make it wider.
- In the toolbar: the 👁 button shows a hand cursor and opens the preview of
  the file that is open — the same button for Markdown and for SVG, as in Zed.
- In the Markdown preview: links show a hand cursor and light up under the
  pointer, the wheel scrolls the page, and the panel's left edge drags to
  make it wider. In the SVG preview the wheel zooms and a drag pans.

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

Project search is a touch surface too: tap a match to open the file at it,
tap a file's row to fold its matches, and tap `⋯` for the include and
exclude fields. On a phone the panel takes the whole work area, so opening
a match hands the screen back to the editor — what you typed is kept, and
`☰` → **Search all files…** brings it back.

The preview is a touch surface too. The 👁 in the toolbar opens it, and `✕` in
its title bar closes it — the control it needs a finger to reach, since on a
phone it covers the editor and there is no `Ctrl` `Shift` `M` to press into.
In Markdown, tap a link to follow it and drag to scroll; in SVG, pinch to zoom,
drag to pan and double tap to put both back. The dock's left edge drags on a
wide screen. Go to line is
the same: the panel's `↵` confirms and `✕` cancels, both reachable while the
soft keyboard is up.

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
