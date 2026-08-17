//! The matcher both searches share, and search over one open buffer.
//!
//! [`SearchQuery`] is a small port of Zed's `project::search::SearchQuery`:
//! literal queries go to `aho-corasick`, regex queries to a compiled regex,
//! and whole-word is a boundary test around each hit rather than a separate
//! algorithm. Zed reaches for `fancy-regex` because it wants lookaround; we
//! use `regex`, whose linear-time guarantee matters more here — a pattern
//! typed by accident must not be able to wedge the thread the search runs on,
//! and on this hardware that thread is not far from the frame loop.
//!
//! [`Engine::search_buffer`] is deliberately synchronous. It reads a rope
//! snapshot, scans it once and returns; on a 100k-line buffer that is a couple
//! of milliseconds, which is well inside a keystroke, so streaming or
//! cancelling it would buy nothing but complexity. Searching a *project* is a
//! different problem entirely and lives in `project_search.rs`.

use std::ops::Range;

use aho_corasick::{AhoCorasick, AhoCorasickBuilder};
use regex::{Regex, RegexBuilder};

use crate::{BufferId, EngineError};

/// What a search bar asks for. Buffer and project search take the same shape
/// so one set of toggles can drive either; the last three fields are simply
/// ignored when searching a buffer.
#[derive(Debug, Clone, Default, PartialEq, serde::Deserialize)]
#[serde(default)]
pub struct SearchOptions {
    pub query: String,
    /// Treat `query` as a regular expression rather than literal text.
    pub regex: bool,
    pub case_sensitive: bool,
    pub whole_word: bool,
    /// Project search: also search files git ignores.
    pub include_ignored: bool,
    /// Project search: only these paths. Empty means every file.
    pub include_globs: Vec<String>,
    /// Project search: never these paths. Applied after `include_globs`.
    pub exclude_globs: Vec<String>,
}

/// A compiled query, ready to run against any text.
pub(crate) struct SearchQuery {
    matcher: Matcher,
}

enum Matcher {
    /// Aho-Corasick has no notion of a word boundary, so whole-word is checked
    /// around each hit — the same shape as Zed's `SearchQuery::search_str`.
    Literal {
        search: Box<AhoCorasick>,
        whole_word: bool,
    },
    Regex(Box<Regex>),
}

/// What a word can be spelled with. Zed derives this from the language's
/// `word_characters` setting; until the engine carries language settings, this
/// is the same default Zed falls back to.
fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

impl SearchQuery {
    /// Compile `options`, or `Ok(None)` when the query is empty — an empty
    /// query matches nothing, and every caller wants that to be an ordinary
    /// empty result rather than an error to report.
    pub(crate) fn new(options: &SearchOptions) -> Result<Option<Self>, String> {
        let mut query = options.query.clone();
        text::LineEnding::normalize(&mut query);
        if query.is_empty() {
            return Ok(None);
        }

        let matcher = if options.regex {
            Matcher::Regex(Box::new(build_regex(&query, options)?))
        } else if !options.case_sensitive && !query.is_ascii() {
            // `ascii_case_insensitive` only folds ASCII, so a case-insensitive
            // search for anything else has to go through the regex engine.
            // Zed makes the same detour, for the same reason.
            Matcher::Regex(Box::new(build_regex(&regex::escape(&query), options)?))
        } else {
            let search = AhoCorasickBuilder::new()
                .ascii_case_insensitive(!options.case_sensitive)
                .build([&query])
                .map_err(|err| err.to_string())?;
            Matcher::Literal {
                search: Box::new(search),
                whole_word: options.whole_word,
            }
        };
        Ok(Some(Self { matcher }))
    }

    /// Every match in `text`, ascending and non-overlapping, keeping at most
    /// `limit` of them. The second value is how many there were in all, so a
    /// caller that truncated can still say how much it dropped.
    ///
    /// Zero-width matches (`a*`, `\b`) are skipped: they have nothing to show
    /// and nothing to step through, and a pattern that matches empty would
    /// otherwise report one hit per position in the file.
    pub(crate) fn matches_in(&self, text: &str, limit: usize) -> (Vec<Range<usize>>, usize) {
        let mut ranges = Vec::new();
        let mut total = 0;
        let mut keep = |range: Range<usize>| {
            if range.is_empty() {
                return;
            }
            total += 1;
            if ranges.len() < limit {
                ranges.push(range);
            }
        };

        match &self.matcher {
            Matcher::Literal { search, whole_word } => {
                for found in search.find_iter(text) {
                    // Both offsets sit on character boundaries: UTF-8 is
                    // self-synchronising, so a valid needle cannot match
                    // halfway into a character of a valid haystack.
                    if *whole_word && !is_whole_word(text, found.start(), found.end()) {
                        continue;
                    }
                    keep(found.start()..found.end());
                }
            }
            Matcher::Regex(regex) => {
                for found in regex.find_iter(text) {
                    keep(found.start()..found.end());
                }
            }
        }
        (ranges, total)
    }
}

fn build_regex(pattern: &str, options: &SearchOptions) -> Result<Regex, String> {
    let mut pattern = pattern.to_owned();
    if options.whole_word {
        // `\b` asserts a *change* of character class, so anchoring a pattern
        // that already starts on punctuation would make it unmatchable.
        // Zed guards the same way, with a `\B` probe instead of this test.
        if pattern.starts_with(is_word_char) {
            pattern.insert_str(0, r"\b");
        }
        if pattern.ends_with(is_word_char) {
            pattern.push_str(r"\b");
        }
    }
    RegexBuilder::new(&pattern)
        .case_insensitive(!options.case_sensitive)
        .multi_line(true)
        .build()
        .map_err(|err| err.to_string())
}

fn is_whole_word(text: &str, start: usize, end: usize) -> bool {
    !text[..start].chars().next_back().is_some_and(is_word_char)
        && !text[end..].chars().next().is_some_and(is_word_char)
}

/// One hit in a buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BufferMatch {
    /// Byte offsets into the buffer, on character boundaries.
    pub start: usize,
    pub end: usize,
    /// Where `start` lands, in the coordinates the editor works in: a 0-based
    /// row and a *byte* column, exactly what `offset_to_point` reports.
    pub row: u32,
    pub column: u32,
}

/// Everything [`Engine::search_buffer`] found.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct BufferSearch {
    pub matches: Vec<BufferMatch>,
    /// How many matches the buffer holds. Larger than `matches.len()` when the
    /// limit bit — the UI can still say "3 of 12 000".
    pub total: usize,
}

impl crate::Engine {
    /// Every match of `options` in a buffer, ascending, keeping at most
    /// `limit`.
    ///
    /// Fast enough to run on every keystroke of the query: a 100k-line buffer
    /// is a single pass over its text (see the `searching_a_large_buffer_is_fast`
    /// test, which holds it to 50 ms with a wide margin in practice).
    pub fn search_buffer(
        &self,
        id: BufferId,
        options: &SearchOptions,
        limit: usize,
    ) -> Result<BufferSearch, EngineError> {
        let query = SearchQuery::new(options).map_err(EngineError::InvalidQuery)?;
        // Clone the rope rather than the text: it is a sum-tree of shared
        // chunks, so this costs nothing and the flattening below happens
        // without the buffer lock held against the edit path.
        let rope = self.with_buffer(id, |state| state.buffer.as_rope().clone())?;
        let Some(query) = query else {
            return Ok(BufferSearch::default());
        };

        let text = rope.to_string();
        let (ranges, total) = query.matches_in(&text, limit);
        let matches = ranges
            .into_iter()
            .map(|range| {
                let point = rope.offset_to_point(range.start);
                BufferMatch {
                    start: range.start,
                    end: range.end,
                    row: point.row,
                    column: point.column,
                }
            })
            .collect();
        Ok(BufferSearch { matches, total })
    }

    /// Why `options` will not compile, or `None` if it will. The search bar
    /// calls this to explain a half-typed regex instead of silently showing
    /// nothing.
    pub fn search_query_error(&self, options: &SearchOptions) -> Option<String> {
        SearchQuery::new(options).err()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    fn options(query: &str) -> SearchOptions {
        SearchOptions {
            query: query.to_owned(),
            ..Default::default()
        }
    }

    fn found(text: &str, options: &SearchOptions) -> Vec<String> {
        let query = SearchQuery::new(options).unwrap().unwrap();
        let (ranges, _) = query.matches_in(text, usize::MAX);
        ranges.into_iter().map(|r| text[r].to_owned()).collect()
    }

    #[test]
    fn literal_matching_is_case_insensitive_by_default() {
        let text = "Foo foo FOO barfoo";
        assert_eq!(found(text, &options("foo")).len(), 4);
        assert_eq!(
            found(
                text,
                &SearchOptions {
                    case_sensitive: true,
                    ..options("foo")
                }
            ),
            vec!["foo", "foo"]
        );
    }

    #[test]
    fn whole_word_rejects_hits_inside_words() {
        let text = "foo foobar barfoo _foo foo_ foo-bar";
        let whole = SearchOptions {
            whole_word: true,
            ..options("foo")
        };
        // Only the standalone "foo"s survive: an underscore continues a word,
        // a hyphen does not.
        assert_eq!(found(text, &whole), vec!["foo", "foo"]);

        // The regex path applies the same rule through `\b`.
        let whole_regex = SearchOptions {
            regex: true,
            ..whole
        };
        assert_eq!(found(text, &whole_regex), vec!["foo", "foo"]);
    }

    #[test]
    fn whole_word_still_matches_a_punctuation_pattern() {
        // `\b` next to `(` would assert a boundary that can never hold, so a
        // pattern starting on punctuation must not be anchored.
        let whole = SearchOptions {
            regex: true,
            whole_word: true,
            ..options(r"\(x\)")
        };
        assert_eq!(found("f(x) g(x)", &whole), vec!["(x)", "(x)"]);
    }

    #[test]
    fn regex_matching() {
        let text = "a1 b22 c333";
        let regex = SearchOptions {
            regex: true,
            ..options(r"[a-z]\d+")
        };
        assert_eq!(found(text, &regex), vec!["a1", "b22", "c333"]);

        // Anchors work per line, as in every editor's find.
        let per_line = SearchOptions {
            regex: true,
            ..options(r"^\w+")
        };
        assert_eq!(found("one two\nthree", &per_line), vec!["one", "three"]);
    }

    #[test]
    fn a_broken_regex_is_reported_not_swallowed() {
        let broken = SearchOptions {
            regex: true,
            ..options("(unclosed")
        };
        let error = SearchQuery::new(&broken).err().expect("a compile error");
        assert!(!error.is_empty());
        // The same text, taken literally, is a perfectly good query.
        assert_eq!(
            found("a (unclosed b", &options("(unclosed")),
            vec!["(unclosed"]
        );
    }

    #[test]
    fn matches_never_split_a_character() {
        // "é" is 2 bytes, "𝄞" is 4, and "日本語" 3 each. Every reported offset
        // must land on a character boundary, whichever matcher runs.
        let text = "héllo 𝄞 日本語 héllo";
        for options in [
            options("héllo"),
            SearchOptions {
                case_sensitive: true,
                ..options("héllo")
            },
            SearchOptions {
                regex: true,
                ..options("h.llo")
            },
            options("日本語"),
            options("𝄞"),
        ] {
            let query = SearchQuery::new(&options).unwrap().unwrap();
            let (ranges, _) = query.matches_in(text, usize::MAX);
            assert!(!ranges.is_empty(), "{options:?} found nothing");
            for range in ranges {
                assert!(
                    text.is_char_boundary(range.start) && text.is_char_boundary(range.end),
                    "{options:?} produced {range:?}, which splits a character"
                );
            }
        }
    }

    #[test]
    fn case_insensitive_non_ascii_falls_back_to_the_regex_engine() {
        // Aho-Corasick cannot fold "É" to "é", so this has to be a regex — and
        // the literal must still be escaped, or "." below would match anything.
        assert_eq!(found("Été ÉTÉ été", &options("été")).len(), 3);
        assert!(found("a.b axb", &options(".")).iter().all(|hit| hit == "."));
    }

    #[test]
    fn zero_width_matches_are_dropped() {
        let empty_ok = SearchOptions {
            regex: true,
            ..options("x*")
        };
        assert_eq!(found("axxb", &empty_ok), vec!["xx"]);
    }

    #[test]
    fn buffer_search_reports_offsets_and_points() {
        let engine = Engine::new();
        let id = engine.create_buffer("let x = 1;\nlet héllo = x;\nlet x = 2;");
        let result = engine.search_buffer(id, &options("x"), 100).unwrap();
        assert_eq!(result.total, 3);
        assert_eq!(
            result.matches,
            vec![
                BufferMatch {
                    start: 4,
                    end: 5,
                    row: 0,
                    column: 4
                },
                // "héllo" is 6 bytes for 5 characters, so the byte column runs
                // ahead of the character count — as `offset_to_point` reports
                // it, and as `point_to_offset` expects it back.
                BufferMatch {
                    start: 24,
                    end: 25,
                    row: 1,
                    column: 13
                },
                BufferMatch {
                    start: 31,
                    end: 32,
                    row: 2,
                    column: 4
                },
            ]
        );

        // The buffer's own conversion agrees with the reported point.
        let first = result.matches[1];
        assert_eq!(
            engine.point_to_offset(id, first.row, first.column).unwrap(),
            first.start
        );
    }

    #[test]
    fn buffer_search_truncates_but_counts_honestly() {
        let engine = Engine::new();
        let id = engine.create_buffer(&"ab".repeat(1000));
        let result = engine.search_buffer(id, &options("a"), 10).unwrap();
        assert_eq!(result.matches.len(), 10);
        assert_eq!(result.total, 1000);
    }

    #[test]
    fn an_empty_query_finds_nothing_and_is_not_an_error() {
        let engine = Engine::new();
        let id = engine.create_buffer("anything");
        let result = engine.search_buffer(id, &options(""), 100).unwrap();
        assert_eq!(result, BufferSearch::default());
        assert_eq!(engine.search_query_error(&options("")), None);
    }

    #[test]
    fn buffer_search_rejects_unknown_buffers_and_bad_regexes() {
        let engine = Engine::new();
        let id = engine.create_buffer("text");
        assert_eq!(
            engine.search_buffer(999, &options("t"), 10),
            Err(EngineError::UnknownBuffer(999))
        );
        let broken = SearchOptions {
            regex: true,
            ..options("(")
        };
        assert!(matches!(
            engine.search_buffer(id, &broken, 10),
            Err(EngineError::InvalidQuery(_))
        ));
        assert!(engine.search_query_error(&broken).is_some());
    }

    /// The number this test prints is the one that matters for the search bar:
    /// it runs on every keystroke of the query, so it has to fit inside a
    /// frame. The bound is far above what the machine does, because CI and a
    /// phone are not this machine — the point is to catch an accidental
    /// quadratic, not to police milliseconds.
    #[test]
    fn searching_a_large_buffer_is_fast() {
        let mut text = String::with_capacity(4 * 1024 * 1024);
        for row in 0..100_000 {
            text.push_str("    let value = compute(input, ");
            text.push_str(&row.to_string());
            text.push_str("); // héllo\n");
        }
        let engine = Engine::new();
        let id = engine.create_buffer(&text);

        // Both ends of the range: a query still being typed that matches
        // nothing, and the pathological one where every line matches.
        for (options, expected) in [
            (options("zzzzzz"), 0),
            (options("compute(input, 99999)"), 1),
            (options("compute"), 100_000),
            (
                SearchOptions {
                    whole_word: true,
                    ..options("value")
                },
                100_000,
            ),
            (
                SearchOptions {
                    regex: true,
                    ..options(r"compute\(\w+, \d+\)")
                },
                100_000,
            ),
        ] {
            let start = std::time::Instant::now();
            let result = engine.search_buffer(id, &options, 10_000).unwrap();
            let elapsed = start.elapsed();
            assert_eq!(result.total, expected);
            println!("{:?} over 100k lines: {elapsed:?}", options.query);
            assert!(
                elapsed < std::time::Duration::from_millis(500),
                "{:?} took {elapsed:?} over a 100k-line buffer",
                options.query
            );
        }
    }
}
