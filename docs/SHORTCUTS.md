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
| `Ctrl` `Alt` `-` | Go back — the tab and place you were before |
| `Ctrl` `Alt` `Shift` `-` | Go forward again |
| `Ctrl` `N` | New file — type a name, or a path like `src/lib.rs`, and it opens |
| `Ctrl` `B` | Show/hide the left dock |
| `Ctrl` `O` | Open the project picker (switch, create, import, export) |
| `Ctrl` `P` | Find a file by name (fuzzy) |
| `Ctrl` `F` | Find in the open file |
| `Ctrl` `Shift` `F` | Search every file in the project |
| `Ctrl` `G` | Go to a line (and column) |
| `Ctrl` `Shift` `M` | Show/hide the preview of the open file |
| `Ctrl` `Shift` `T` | Reopen the tab you closed last |
| `Ctrl` `Shift` `E` | Reveal the open file in the project panel |
| `Ctrl` `Shift` `G` | Show/hide the git panel |
| `Ctrl` `Alt` `Shift` `B` | Switch git branch (the branch picker) |
| `Ctrl` `Alt` `A` | Show/hide the agent panel |
| `Ctrl` `,` | Open settings |
| — | Edit settings.json as a tab (`zed: open settings file` in the palette, or the ☰ menu) |
| `Ctrl` `=` / `Ctrl` `-` | Make the interface bigger / smaller |
| `Ctrl` `0` | Back to the default size |
| — | Pick a theme (`theme selector: toggle` in the palette, or the ☰ menu) |
| — | Install a language server (`conquest: install language server` in the palette) |
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

**Install a language server** has no chord either, and for the same reason: it
is done once per language. The two ways in are the command palette and the
status bar, which says `clangd is not installed` when a project needs a server
the userland has not got — click that. In the prompt, `Enter` installs (and,
once it has, closes), `Esc` closes without stopping an install that is already
running, and `↑` `↓` `Tab` `Shift` `Tab` move through the list of languages.
Nothing is ever installed without being asked for, and the question says what
the download will cost. Like cloning, it exists only in the `full` edition —
apt lives inside the Linux userland — so the edition without one leaves the
command out entirely rather than showing it greyed.

**The interface size is Zed's `ui_font_size`, and it is the unit the whole
chrome is measured in** — rows, bars, gaps and icons grow with it, not just
the text, which is what Zed does and why the chords are worth having. On a
phone the same setting is **Settings → Interface size**, which is the route
that matters when there is no keyboard.

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

## Diffs

Tapping a changed file in the git panel opens its **diff** — what Zed does with
a click on a change, and the more useful answer to "what did I do here". It is
a tab, not a dock: a diff is a document, and it belongs beside the file it is
about. **View diff** at the top of the panel opens every change at once.

The view is unified — old and new in one column, added lines on green, removed
on red, both line numbers down the left — rather than side by side, which on a
phone means two twenty-column panes. It follows the repository: stage a file or
type in the editor beside it and the diff catches up.

`Push` sends the commits you have made; on a branch nobody has pushed it reads
**Publish** and creates it on the remote, which is Zed's wording and the
accurate one. There is no credential helper inside the userland, so an HTTPS
remote will fail with git's own words about authentication — SSH with a key in
the userland's `~/.ssh` is the way that works today.

## The branch picker

The branch name — in the git panel's header, or in the title bar — opens the
**branch picker**, Zed's own: every local and remote branch with its last
commit, filtered as you type, `Enter` checks out. A remote branch checks out
by growing a local tracking branch named after it, exactly as Zed does. A name
no branch has becomes a **Create Branch** entry — `Enter` branches off HEAD,
`Ctrl` `Enter` off the repository's default branch. `Ctrl` `Shift`
`Backspace` deletes the selected branch (`Alt` on top force-deletes; a branch
that is not fully merged asks first), `Ctrl` `Shift` `I` cycles the
all/local/remote filter and `Ctrl` `K` opens it as a menu.

## The commit graph

**Graph** in the git panel's History tab — or `git: open graph` in the palette,
or `☰` → **Git graph** — opens the history as Zed's graph: the lanes down the
left that show where a branch forked and where it came back, then the
description, the date, the author and the short hash. Below 640dp the last
three fold onto a second line, because five columns on a phone is one column of
ellipses.

It loads a hundred commits at a time and asks for more as you reach the end.
Tapping a row shows that commit's message and the files it touched; tapping a
file opens it.

## Wrapping long lines

Off by default, as in Zed. **Settings** → *Wrap long lines*, `☰` → **Wrap long
lines**, or the command palette's `editor: toggle soft wrap` — Zed's own action,
which it binds to `Ctrl` `K` `Ctrl` `Z`, a two-key sequence this keymap cannot
express yet. Whichever route, it writes `soft_wrap` to settings.json, so it
survives a restart.

Wrapped or not, the caret keys mean what they always meant: `Home` and `End` go
to the ends of the *line*, not of the screen row.

## Panels and docks

Every panel lives in a dock — left or right — and each one's side is a setting,
as in Zed: **Settings** → *Panels*, or `project_panel.dock` and friends in
settings.json. Its button in the status bar moves with it, so the button is
always on the side the panel will appear. The third answer is `"hidden"`:
the panel is switched off — its button leaves the status bar and its
commands grey out — until the setting says otherwise.

| Shortcut | Action |
|---|---|
| `Ctrl` `B` | Show/hide the left dock |
| `Ctrl` `Alt` `B` | Show/hide the right dock |

**One panel at a time per dock, and the two docks are independent.** Opening
git while search is up on the same side replaces it; opening it while the tree
is up on the *other* side leaves the tree alone. When both are open and the
screen cannot hold them at their full widths, they shrink to share what there
is rather than closing each other — and only when even two minimum widths and
an editor will not fit does the most recently opened one take the space, with
the other returning as soon as there is room.

Drag a dock's inner edge to resize it, the same way the terminal dock resizes.
On a phone a dock takes the whole work area, and opening a file from one hands
the area back.

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
| `Ctrl` `Shift` `Enter` | Amend — the first press enters amend mode, the second commits |
| `Ctrl` `Space` | Stage everything |
| `Ctrl` `Shift` `Space` | Unstage everything |
| `Ctrl` `1` / `Ctrl` `2` | The Changes / History tab |
| `Esc`, or `✕` | Close the panel |

### The `Ctrl` `G` chords

While the panel has the keyboard, `Ctrl` `G` is a **leader**, exactly as in
Zed's `GitPanel` keymap context: press it, and the next keystroke completes a
two-step chord. A small `Ctrl G …` chip above the commit box says the chord
is waiting. A key that matches nothing cancels it — and does nothing else,
as in Zed — and so do `Esc` and saying nothing for a few seconds, which Zed
(with a status bar that echoes pending keys) does not need. In the editor,
`Ctrl` `G` is still go-to-line, and in a terminal it is still BEL.

| Chord | Action |
|---|---|
| `Ctrl` `G`, `Ctrl` `G` | Fetch |
| `Ctrl` `G`, `↑` | Push |
| `Ctrl` `G`, `↓` | Pull |
| `Ctrl` `G`, `Shift` `↑` | Force push (`--force-with-lease`) |
| `Ctrl` `G`, `Shift` `↓` | Pull with rebase |
| `Ctrl` `G`, `D` | Open the whole project's diff |

Every one of these is also a command in the palette — `git: fetch`,
`git: push`, `git: pull`, `git: force push`, `git: pull rebase`,
`git: stage all`, `git: unstage all`, `git: diff` — which is the route with
no keyboard at all, and the route from a focused terminal. Run from there,
the command opens the git panel and runs in it, so the spinner and whatever
git says back have somewhere to be seen.

The first commit in a fresh userland fails: git guesses an identity from the
hostname (`root@localhost.(none)`), refuses to use it, and says so. The panel
answers that with a name and email field rather than an error — what you type
goes into the userland's global git config, so it is asked once per userland
and not once per clone, and the commit you pressed runs straight after.

On a wide screen it docks beside the editor; on a phone it takes the work area.
The dock shows one panel at a time — git, project search or the preview — which
is a dock's rule in Zed too, and is what stops three of them sharing a phone
screen and leaving the editor a character wide.

## Agent panel

`Ctrl` `Alt` `A` opens a conversation with an ACP agent working on the open
project. Pressing it again puts the keyboard back in the composer.

**Threads.** Each conversation is a thread, as in Zed: **+ New** in the
panel's bar starts another for the open project, and **Threads** lists every
thread grouped by project, searchable, with the other projects shown so you
can see where threads would live. Tap a thread to return to it — its whole
transcript is kept — and **Close** to end it. Threads live with the agent
process: they survive the panel closing, not the app.

A thread is named after the first thing you say in it, and takes the agent's
own name for the conversation instead as soon as the agent sends one.

**Reasoning.** An agent that thinks out loud gets a **Thinking** line above
its answer. It opens itself while the thought is arriving and closes when the
answer starts; tap it to keep it open, or to read it again later.

**What the agent itself remembers.** Some agents keep their conversations on
their own side. When yours does, the Threads view has a *Kept by the agent*
section under your own threads: tap one to reopen it — with its transcript
where the agent can replay it, without where it can only continue — and
**Forget** to delete it for good. Agents that keep nothing simply have no such
section. **Agent** in the bar lists the agents settings.json configures:
picking one starts a *new* thread with it rather than closing the ones you
have, and signs out of an agent that supports it.

**The composer speaks the protocol.** Type `/` at the start for the agent's
own slash commands (it advertises them; the strip completes them), and `@`
anywhere for a file mention — the file travels with the prompt as context,
embedded when the agent takes embedded context and as a link otherwise.

**Attaching a picture.** The `+` at the start of the controls row opens the
system photo picker; what you choose rides the message as an image. It is
shown above the box with its size and an `✕` to take it back off again, and a
picture on its own is a message — you do not have to type anything with it.
Large images are shrunk before sending, because the whole prompt travels down
one pipe to the agent.

**The `+` appears only for an agent that reads images.** Whether it can is
the agent's own answer, given when it starts, so an agent that never claimed
it is not offered a button that would produce something it cannot see.
In the row under the message box sit the agent's session controls, straight
off the wire: its mode, and every config option it advertises (model, effort,
toggles). A pick-one option is a chip that drops its choices; a yes/no one is
a switch. What the turn has cost, when the agent reports it, sits at the
start of the same row.

| Shortcut | Action |
|---|---|
| `Ctrl` `Alt` `A` | Show/hide the agent panel |
| `Enter` | Send the message |
| `Shift` `Enter` | Start a new line instead |
| `Ctrl` `;` | Attach an image (when the agent reads them) |
| `Tab` | Take the first suggestion while the `/` or `@` strip is up |
| `Esc` | Put the suggestion strip away; with none up, stop the agent mid-turn |

Above the composer sits a strip with whatever needs you: the agent's plan
(tap to unfold), anything queued, a turn that failed and what to do about it,
and a **Waiting for you** line — with a **Show** that scrolls to it — whenever
a permission prompt or a question has scrolled out of sight.

On a phone `Enter` inserts a newline and the send button — the paper plane at
the end of the controls row — is how you send: a soft keyboard's Enter arrives
as text rather than as a keystroke, so it cannot mean two things at once.

**Typing while it works queues, it does not interrupt.** The button becomes
**Queue** while the agent is busy and there is something to send; queued
messages wait above the composer, in order, with a ✕ to take one back, and go
out one at a time as each turn ends. Stopping the agent is the separate
**Stop** button — a follow-up should never throw away the work in progress.

**The panel does not take the keyboard the way the terminal does.** Every
workspace chord keeps working while the composer has focus — it is a text box
in a dock, like the git panel's commit message — so `Ctrl` `S`, `Ctrl` `P` and
the rest still reach the editor. The reverse also holds: `Ctrl` `Alt` `A` is an
Alt chord, so while the *terminal* has focus it belongs to the shell, and the
`☰` menu or the command palette is the way to the panel from there.

**Nothing the agent writes lands without a decision.** When it asks to change a
file the turn stops, the whole diff appears in the conversation — never
truncated while you are being asked about it — and the agent's own choices sit
underneath it as a full-width list, in the order it offered them. The change
and the choice are one screen. Diffs are unified, never side by side.

**Signing in.** When an agent wants signing in to, the panel offers whatever
methods *it* advertised. Most are a button the agent handles itself; one kind
is not — a terminal sign-in opens a terminal running the agent's own command
with its login arguments, so you can answer its prompts. Finish there, then
start a new thread.

**The agent can ask you things.** Not everything an agent needs is a
yes-or-no about a file: it may want an API token, a choice between branches,
or for you to sign in on a web page and come back. Those arrive as a card at
the end of the conversation with the fields it asked for — text, numbers,
switches, pick-one and pick-many — and **Send** or **Decline** underneath.
Required fields are marked `*` and Send waits for them. A sign-in card stays
up after you say you have done it: the agent is watching for the sign-in and
takes the card away itself once it sees it.

**Commands the agent runs are on screen.** An agent that wants to run
`cargo test` asks the editor to run it rather than shelling out invisibly, so
the command line, what it printed and how it exited appear on the tool call —
tap the call to unfold it, and the output scrolls sideways for long lines. The
command runs in the Linux userland with the project as its working directory,
which it cannot leave. Only its tail is shown; a command that floods keeps its
last megabyte and says so.

**Agent-agnostic, like the protocol.** ACP is a standard, so the panel names
no agent of its own and installs nothing: every agent comes from
`agent_servers` in settings.json — the same key Zed uses — and the picker
offers exactly that list. Add, edit and remove entries from **Settings** →
*External Agents* (name, command, arguments), or write the file yourself:

```jsonc
"agent_servers": {
  "My agent": { "command": "my-agent", "args": ["--acp"], "env": {} }
}
```

The command runs inside the Linux userland, with the environment a login
shell would have — so it is anything you can start from the terminal by
typing its name (`~/.local/bin` and whatever your profile adds to PATH
included), or an absolute path in the guest, that speaks the Agent Client
Protocol on stdin and stdout. Putting it there is yours to do, in the
terminal. settings.json opens as an ordinary editor tab from `☰` → **Edit
settings.json**, the palette's `zed: open settings file`, or the link at the
bottom of the Settings screen; saving it applies it. An edit reaches the
picker as soon as you save; a conversation that is already running keeps the
command it started with until you press **New**.

The panel runs the agent inside the Linux userland, so it is absent from the
Play edition entirely.

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
it is BEL and readline's abort — and while the git panel has it, `Ctrl` `G` is
that panel's chord leader instead, exactly as in Zed (see **Git panel**).

## Outline

`Ctrl` `Shift` `O` opens the outline — every symbol in the file, nested as the
code nests, exactly Zed's `outline::Toggle`. Tapping the **breadcrumbs** above
the editor opens it too, which is Zed's own wiring for them and the touch
route. Type to filter; the caret follows the selected symbol **as you
browse**, the way go-to-line previews.

| Shortcut | Action |
|---|---|
| `↑` / `↓` | Browse — the editor follows |
| `Enter`, or a tap | Keep the caret on the symbol |
| `Esc` | Put the caret, the selection and the view back where they were |

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

### Folding

The chords are Zed's, from its Linux keymap; the gutter chevron and the `⋯`
chip do the same by touch or mouse. Folding follows indentation, as Zed's
does: a line folds away the deeper-indented block beneath it.

| Shortcut | Action |
|---|---|
| `Ctrl` `Shift` `[` | Fold the innermost block around each cursor |
| `Ctrl` `Shift` `]` | Unfold at each cursor |
| `Ctrl` `K`, then `Ctrl` `0` | Fold every block in the file |
| `Ctrl` `K`, then `Ctrl` `J` | Unfold everything |

Tapping the chevron in the gutter folds that block; tapping the `⋯` chip at
the end of a folded line opens it again. Editing into a fold unfolds it, and
a search hit inside one unfolds its way to the match.

### Problems

The chords are Zed's, from its Linux keymap. A language server has to be
installed and running for there to be anything to go to; the status bar says
which ones are not.

| Shortcut | Action |
|---|---|
| `F8` | Go to the next problem in the file |
| `Shift` `F8` | Go to the previous one |

The squiggle under a problem is Zed's, in Zed's severity colours, and it fades
while the file has edits the server has not seen yet — a diagnostic describes
the text the server last read, so it dims rather than pretending to have
moved. The gutter carries a mark for every affected row; tapping it goes
there. The status bar counts the project's errors and warnings and shows the
message under the caret.

### What the language server knows

A server has to be installed and running — the status bar says when one is
not, and clicking it offers to install it.

| Shortcut | Action |
|---|---|
| `Ctrl` `Space` | Suggest completions where the cursor is |
| `Enter` / `Tab` | Accept the selected completion |
| `↑` / `↓` | Move through the list |
| `PageUp` / `PageDown` | Jump to the first / last one |
| `Esc` | Close the list (or the hover card) and leave the text alone |
| `F12` | Go to where the symbol under the cursor is defined |
| `Ctrl` `K`, then `Ctrl` `I` | Show what the language server knows about the symbol |

The list filters as you keep typing and does not ask the server again unless it
said its answer was incomplete. It opens **above** the cursor when the soft
keyboard would otherwise cover it — the one placement rule here that is not
Zed's, because a desktop editor never has a keyboard eating the bottom third of
the screen.

By touch: the `suggest` key in the row above the keyboard opens the list and a
tap accepts a row; a **long press** on a symbol shows what the server knows
about it, with **Go to definition** on the card. With a mouse, resting the
pointer on a symbol shows the same card, and `Ctrl` `+ click` goes to the
definition.

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
- At the left end of the tab strip, `←` and `→` walk the navigation
  history — every tab switch remembers where you were, going back returns
  there (reopening the file if you closed it), and going forward replays the
  jump. Greyed when there is nowhere to go. The `+` at the strip's right end
  creates a new file.
- In the status bar: the panel buttons sit with their docks — the left dock's
  at the left end, the right dock's and the terminal's at the right.
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

The status bar carries one button per panel, each on the side its dock is on:
the file tree, the git panel, project search and the preview, plus the terminal
at the right end. A button lights while its panel is showing, and pressing it
again closes that dock. Project, file and settings commands live in the `☰`
menu in the title bar, and
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
