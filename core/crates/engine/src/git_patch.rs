//! Diffs as a *patch*: what changed, line by line, for a diff view to draw.
//!
//! Distinct from [`crate::git_diff`], which answers a different question —
//! "which rows of this open buffer differ from HEAD" — for the gutter, from a
//! cache, without ever materialising the old text. A view has to show both
//! sides, so this runs `git diff` and reads the unified patch it prints.

use std::ffi::OsString;

use crate::ProjectId;
use crate::git::{git_argv, run_git};

/// One file's diff.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct FileDiff {
    /// The path as it is now — the `b/` side, or the `a/` side for a deletion.
    pub path: String,
    /// Where it came from, for a rename.
    pub original: Option<String>,
    /// True when git said the content is binary; [`hunks`] is then empty.
    pub is_binary: bool,
    pub hunks: Vec<PatchHunk>,
}

/// One `@@` block.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct PatchHunk {
    /// First line of the block on each side, 1-based as git counts.
    pub old_start: u32,
    pub new_start: u32,
    /// The `@@ … @@ <heading>` tail: the enclosing function, when git finds one.
    pub heading: String,
    pub lines: Vec<PatchLine>,
}

/// One line of a hunk.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct PatchLine {
    /// `' '` unchanged, `'+'` added, `'-'` removed.
    pub kind: char,
    pub text: String,
    /// Its number on the old side, or 0 for an added line.
    pub old_line: u32,
    /// Its number on the new side, or 0 for a removed line.
    pub new_line: u32,
}

impl crate::Engine {
    /// The working tree against HEAD — or against the index, with [`staged`].
    ///
    /// `path` narrows it to one file; `None` is every changed file, which is
    /// what a "view diff" of the whole project shows.
    ///
    /// An **untracked** file has no diff at all as far as `git diff` is
    /// concerned, which would make a view of one an empty page. It is diffed
    /// against nothing instead, so every line reads as added — which is what
    /// it is.
    ///
    /// **Blocking**: it runs git inside the guest.
    pub fn git_patch(
        &self,
        id: ProjectId,
        path: Option<&str>,
        staged: bool,
    ) -> Result<Vec<FileDiff>, String> {
        let repo = self.repo_for(id)?;
        let mut args: Vec<OsString> = vec![OsString::from("diff")];
        if staged {
            args.push(OsString::from("--staged"));
        }
        args.push(OsString::from("--no-color"));
        args.push(OsString::from("--no-ext-diff"));
        // Renames are worth showing as renames rather than as one file deleted
        // and another added in full.
        args.push(OsString::from("--find-renames"));
        args.push(OsString::from("-U3"));
        if let Some(path) = path {
            args.push(OsString::from("--"));
            args.push(OsString::from(crate::git::checked_path(path)?));
        }
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git diff",
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            return Err(run.message());
        }
        let diffs = parse_patch(&run.output);
        if !diffs.is_empty() {
            return Ok(diffs);
        }

        // Nothing — which for a single path may mean "untracked", not
        // "unchanged". `--no-index` against the empty tree prints the whole
        // file as additions, and exits 1 *because* there is a difference.
        let Some(path) = path.filter(|_| !staged) else {
            return Ok(Vec::new());
        };
        let untracked: Vec<OsString> = vec![
            OsString::from("diff"),
            OsString::from("--no-color"),
            OsString::from("--no-ext-diff"),
            OsString::from("--no-index"),
            OsString::from("-U3"),
            OsString::from("--"),
            OsString::from("/dev/null"),
            OsString::from(crate::git::checked_path(path)?),
        ];
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git diff",
            git_argv(&repo.project_root, &untracked),
        )?;
        // 0 means identical to /dev/null (an empty file), 1 means it differs;
        // anything else is a failure worth reporting.
        if run.status > 1 {
            return Ok(Vec::new());
        }
        Ok(parse_patch(&run.output)
            .into_iter()
            .map(|mut diff| {
                // `--no-index` writes the path it was given; the caller asked
                // about the project-relative one.
                diff.path = path.to_owned();
                diff.original = None;
                diff
            })
            .collect())
    }
}

/// Read `git diff`'s unified output.
///
/// Written against git's format rather than a general patch grammar: the input
/// is always our own `git diff --no-color -U3`, so the only surprises are the
/// ones git itself produces — binary files, renames with no content change,
/// mode-only changes, and `\ No newline at end of file`.
pub(crate) fn parse_patch(output: &str) -> Vec<FileDiff> {
    let mut files: Vec<FileDiff> = Vec::new();
    let mut old_line = 0u32;
    let mut new_line = 0u32;

    // `lines()` rather than `split('\n')`: the latter yields one empty
    // segment for the trailing newline, which the hunk arm then reads as an
    // empty context line and counts.
    for line in output.lines() {
        if let Some(rest) = line.strip_prefix("diff --git ") {
            let (a, b) = split_paths(rest);
            files.push(FileDiff {
                path: b.unwrap_or_else(|| a.clone().unwrap_or_default()),
                original: None,
                is_binary: false,
                hunks: Vec::new(),
            });
            continue;
        }
        let Some(file) = files.last_mut() else { continue };

        if let Some(from) = line.strip_prefix("rename from ") {
            file.original = Some(from.to_owned());
            continue;
        }
        if let Some(to) = line.strip_prefix("rename to ") {
            file.path = to.to_owned();
            continue;
        }
        if line.starts_with("Binary files ") || line.starts_with("GIT binary patch") {
            file.is_binary = true;
            continue;
        }
        if let Some(header) = line.strip_prefix("@@ ") {
            let Some((ranges, heading)) = header.split_once("@@") else {
                continue;
            };
            let Some((old, new)) = parse_ranges(ranges) else {
                continue;
            };
            old_line = old;
            new_line = new;
            file.hunks.push(PatchHunk {
                old_start: old,
                new_start: new,
                heading: heading.trim().to_owned(),
                lines: Vec::new(),
            });
            continue;
        }
        let Some(hunk) = file.hunks.last_mut() else { continue };
        // Inside a hunk. `\ No newline at end of file` is a note about the
        // line above, not a line of its own.
        if line.starts_with('\\') {
            continue;
        }
        let (kind, text) = match line.chars().next() {
            Some('+') => ('+', &line[1..]),
            Some('-') => ('-', &line[1..]),
            Some(' ') => (' ', &line[1..]),
            // A truly empty line inside a hunk is git writing a context line
            // that is itself empty, with the leading space stripped by
            // something along the way. Treated as context rather than dropped.
            None => (' ', ""),
            // Anything else ends the hunk: the next file's header, or the
            // trailing "-- " of a mail-formatted patch.
            _ => continue,
        };
        hunk.lines.push(PatchLine {
            kind,
            text: text.to_owned(),
            old_line: if kind == '+' { 0 } else { old_line },
            new_line: if kind == '-' { 0 } else { new_line },
        });
        if kind != '+' {
            old_line += 1;
        }
        if kind != '-' {
            new_line += 1;
        }
    }
    files
}

/// `a/src/main.rs b/src/main.rs` → both sides, without their prefixes.
fn split_paths(rest: &str) -> (Option<String>, Option<String>) {
    // Paths with spaces in them make this ambiguous in general; git's own
    // answer is the `a/`…`b/` prefixes, so the split is at " b/".
    if let Some((a, b)) = rest.split_once(" b/") {
        let a = a.strip_prefix("a/").unwrap_or(a).to_owned();
        return (Some(a), Some(b.to_owned()));
    }
    (None, None)
}

/// `-12,7 +12,9` → the two starting lines.
fn parse_ranges(ranges: &str) -> Option<(u32, u32)> {
    let mut old = None;
    let mut new = None;
    for part in ranges.split_whitespace() {
        let (sign, rest) = part.split_at(1);
        let start = rest
            .split(',')
            .next()
            .and_then(|number| number.parse::<u32>().ok())?;
        match sign {
            "-" => old = Some(start),
            "+" => new = Some(start),
            _ => {}
        }
    }
    Some((old?, new?))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real `git diff` output, assembled line by line.
    ///
    /// Not a `\`-continued string literal: Rust's continuation eats the
    /// *leading whitespace* of the next line, which silently strips the space
    /// that marks a context line — so the fixture stopped being a patch and
    /// the parser looked wrong.
    fn patch() -> String {
        [
            "diff --git a/src/main.rs b/src/main.rs",
            "index 1234567..89abcde 100644",
            "--- a/src/main.rs",
            "+++ b/src/main.rs",
            "@@ -1,4 +1,5 @@ fn main()",
            " fn main() {",
            "-    println!(\"old\");",
            "+    println!(\"new\");",
            "+    println!(\"and another\");",
            " }",
            " ",
            "diff --git a/notes.md b/notes.md",
            "--- a/notes.md",
            "+++ b/notes.md",
            "@@ -10,2 +11,2 @@",
            "-gone",
            "+here",
        ]
        .join("\n")
    }

    #[test]
    fn a_patch_becomes_files_and_hunks() {
        let files = parse_patch(&patch());
        assert_eq!(files.len(), 2);
        assert_eq!(files[0].path, "src/main.rs");
        assert_eq!(files[0].hunks.len(), 1);
        let hunk = &files[0].hunks[0];
        assert_eq!(hunk.old_start, 1);
        assert_eq!(hunk.new_start, 1);
        assert_eq!(hunk.heading, "fn main()");
        assert_eq!(hunk.lines.len(), 6);
        assert_eq!(files[1].path, "notes.md");
        assert_eq!(files[1].hunks[0].old_start, 10);
        assert_eq!(files[1].hunks[0].new_start, 11);
    }

    /// The numbers down each side are what a diff view puts in its gutter, and
    /// they are the thing a naive parser gets wrong: an added line has no old
    /// number, a removed line has no new one, and context advances both.
    #[test]
    fn every_line_carries_its_number_on_the_side_it_exists_on() {
        let lines = &parse_patch(&patch())[0].hunks[0].lines;
        assert_eq!((lines[0].kind, lines[0].old_line, lines[0].new_line), (' ', 1, 1));
        assert_eq!((lines[1].kind, lines[1].old_line, lines[1].new_line), ('-', 2, 0));
        assert_eq!((lines[2].kind, lines[2].old_line, lines[2].new_line), ('+', 0, 2));
        assert_eq!((lines[3].kind, lines[3].old_line, lines[3].new_line), ('+', 0, 3));
        // Context after the change: the old side has advanced by one line and
        // the new side by two.
        assert_eq!((lines[4].kind, lines[4].old_line, lines[4].new_line), (' ', 3, 4));
    }

    #[test]
    fn a_rename_keeps_both_names() {
        let files = parse_patch(
            "diff --git a/old.txt b/new.txt\n\
similarity index 92%\n\
rename from old.txt\n\
rename to new.txt\n\
@@ -1 +1 @@\n\
-a\n\
+b\n",
        );
        assert_eq!(files[0].path, "new.txt");
        assert_eq!(files[0].original.as_deref(), Some("old.txt"));
    }

    #[test]
    fn a_binary_file_says_so_instead_of_pretending() {
        let files = parse_patch(
            "diff --git a/logo.png b/logo.png\n\
Binary files a/logo.png and b/logo.png differ\n",
        );
        assert!(files[0].is_binary);
        assert!(files[0].hunks.is_empty());
    }

    /// `\ No newline at end of file` is a note about the line above it.
    #[test]
    fn the_no_newline_marker_is_not_a_line() {
        let files = parse_patch(
            "diff --git a/a b/a\n@@ -1 +1 @@\n-one\n\\ No newline at end of file\n+one\n",
        );
        assert_eq!(files[0].hunks[0].lines.len(), 2);
    }

    #[test]
    fn nothing_in_nothing_out() {
        assert!(parse_patch("").is_empty());
        assert!(parse_patch("not a patch at all\n").is_empty());
    }
}
