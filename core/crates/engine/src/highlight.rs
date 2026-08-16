//! Interim tree-sitter syntax highlighting (roadmap phase 2).
//!
//! Zed's real syntax machinery (`language::SyntaxMap`) is coupled to the
//! gpui runtime; until the headless-GPUI decision gate (phase 3), the
//! engine runs one incremental `tree_sitter::Parser` per buffer using the
//! vendored `grammars` crate (compiled-in grammars + Zed's `highlights.scm`
//! queries) and evaluates captures over the visible line window on demand.
//!
//! Style identity is an index into [`STYLE_NAMES`], which follows Zed's
//! syntax-theme key set. The Kotlin side maps the same indices to theme
//! colors — keep the two lists in sync (`ui/editor/SyntaxPalette.kt`).

use std::collections::HashMap;
use std::ops::Range;
use std::sync::OnceLock;

use rope::{Point, Rope};
use streaming_iterator::StreamingIterator as _;
use tree_sitter::{InputEdit, Parser, Query, QueryCursor, Tree};

/// Zed's syntax style keys (subset ordering is ours; indices are the
/// engine<->UI contract). Longest-dotted-prefix matching maps capture
/// names ("keyword.operator") onto these.
pub const STYLE_NAMES: &[&str] = &[
    "attribute",
    "boolean",
    "comment",
    "comment.doc",
    "constant",
    "constructor",
    "embedded",
    "emphasis",
    "emphasis.strong",
    "enum",
    "function",
    "keyword",
    "label",
    "link_text",
    "link_uri",
    "number",
    "operator",
    "preproc",
    "property",
    "punctuation",
    "punctuation.bracket",
    "punctuation.delimiter",
    "punctuation.list_marker",
    "punctuation.special",
    "string",
    "string.escape",
    "string.regex",
    "string.special",
    "string.special.symbol",
    "tag",
    "text.literal",
    "title",
    "type",
    "variable",
    "variable.special",
];

/// One highlighted range on one row. Columns are UTF-16 offsets within the
/// row's line, ready for Compose's AnnotatedString ranges.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HighlightSpan {
    pub row: u32,
    pub start_col_utf16: u32,
    pub end_col_utf16: u32,
    pub style: u16,
}

struct LanguageEntry {
    language: tree_sitter::Language,
    /// Compiled highlights query and per-capture-index style (None for
    /// captures we don't map, e.g. locals or `_`-prefixed).
    highlights: Option<(Query, Vec<Option<u16>>)>,
}

fn registry() -> &'static HashMap<&'static str, LanguageEntry> {
    static REGISTRY: OnceLock<HashMap<&'static str, LanguageEntry>> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        let mut map = HashMap::new();
        for (name, language) in grammars::native_grammars() {
            let queries = grammars::load_queries(name);
            let highlights = queries.highlights.and_then(|source| {
                match Query::new(&language, source.as_ref()) {
                    Ok(query) => {
                        let styles = query
                            .capture_names()
                            .iter()
                            .map(|capture| style_for_capture(capture))
                            .collect();
                        Some((query, styles))
                    }
                    Err(err) => {
                        log::warn!("failed to compile highlights query for {name}: {err}");
                        None
                    }
                }
            });
            map.insert(
                name,
                LanguageEntry {
                    language,
                    highlights,
                },
            );
        }
        map
    })
}

/// Language configs whose grammar name differs from their directory name in
/// `grammars/src/`, so an extension lookup would otherwise miss them.
/// JavaScript is the only one that matters to us — Zed parses it with the
/// `tsx` grammar.
const EXTRA_CONFIGS: &[&str] = &["javascript"];

/// Extension (lowercased, no dot) → grammar name, built from the vendored
/// `config.toml` files so the mapping stays Zed's rather than ours. First
/// writer wins, which keeps the order of `native_grammars()` authoritative
/// where two languages claim the same suffix.
fn extension_map() -> &'static HashMap<String, &'static str> {
    static MAP: OnceLock<HashMap<String, &'static str>> = OnceLock::new();
    MAP.get_or_init(|| {
        let mut map = HashMap::new();
        let names = registry()
            .keys()
            .copied()
            .chain(EXTRA_CONFIGS.iter().copied());
        for name in names {
            if grammars::get_file(&format!("{name}/config.toml")).is_none() {
                continue;
            }
            let config = grammars::load_config(name);
            // A config's `grammar` may point at another language's grammar
            // (JavaScript → tsx); only keep it if that grammar is loaded.
            let Some(grammar) = config
                .grammar
                .as_ref()
                .and_then(|grammar| registry().get_key_value(grammar.as_ref()))
                .map(|(name, _)| *name)
            else {
                continue;
            };
            for suffix in &config.matcher.path_suffixes {
                map.entry(suffix.to_lowercase()).or_insert(grammar);
            }
        }
        map
    })
}

/// The grammar to highlight `path` with, from its file name. Matches the
/// longest suffix first, so `tsconfig.json` beats `json`.
pub fn language_for_path(path: &str) -> Option<&'static str> {
    let name = path.rsplit(['/', '\\']).next()?.to_lowercase();
    let map = extension_map();
    // Zed's `path_suffixes` hold both plain extensions ("rs") and whole file
    // names ("tsconfig.json"), so try progressively shorter suffixes.
    let mut candidate = name.as_str();
    loop {
        if let Some(language) = map.get(candidate) {
            return Some(language);
        }
        let dot = candidate.find('.')?;
        candidate = &candidate[dot + 1..];
    }
}

/// Longest-dotted-prefix lookup of a capture name in [`STYLE_NAMES`].
fn style_for_capture(capture: &str) -> Option<u16> {
    if capture.starts_with('_') {
        return None;
    }
    let mut candidate = capture;
    loop {
        if let Some(index) = STYLE_NAMES.iter().position(|name| *name == candidate) {
            return Some(index as u16);
        }
        candidate = &candidate[..candidate.rfind('.')?];
    }
}

pub struct HighlightState {
    /// Grammar name, so the UI can say what it is parsing as.
    name: &'static str,
    language: &'static LanguageEntry,
    parser: Parser,
    tree: Option<Tree>,
    /// Edits have been applied to `tree`'s positions but not reparsed, so the
    /// spans it yields are approximate until the worker catches up.
    dirty: bool,
    /// Bumped when a reparse lands, so the UI knows to re-read spans even
    /// though the buffer's content version hasn't moved.
    version: u64,
    /// The next parse must start from scratch. Set by history operations,
    /// where the text changed without a matching `tree.edit()` — handing
    /// tree-sitter that tree as the "old" one would make it reuse subtrees
    /// that no longer correspond to the text.
    needs_full_parse: bool,
}

impl HighlightState {
    pub fn name(&self) -> &'static str {
        self.name
    }

    pub fn version(&self) -> u64 {
        self.version
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    /// The inputs a background reparse needs, taken while the buffer lock is
    /// held so the parse itself can run without it.
    pub fn parse_inputs(&self) -> (&'static str, Option<Tree>) {
        if self.needs_full_parse {
            (self.name, None)
        } else {
            (self.name, self.tree.clone())
        }
    }

    /// Adopt a tree parsed off-thread.
    pub fn install(&mut self, tree: Tree) {
        self.tree = Some(tree);
        self.dirty = false;
        self.needs_full_parse = false;
        self.version += 1;
    }

    /// Mark the tree stale without reparsing — for history operations, where
    /// the edit shape isn't readily available. The old tree is kept so the
    /// view keeps its highlighting until the reparse lands, rather than
    /// flashing to unhighlighted text.
    pub fn invalidate(&mut self) {
        self.dirty = true;
        self.needs_full_parse = true;
    }

    /// Returns None for unknown language names.
    pub fn new(language_name: &str, text: &Rope) -> Option<HighlightState> {
        let (name, entry) = registry().get_key_value(language_name)?;
        let mut parser = Parser::new();
        parser.set_language(&entry.language).ok()?;
        let mut state = HighlightState {
            name,
            language: entry,
            parser,
            tree: None,
            dirty: false,
            version: 0,
            needs_full_parse: false,
        };
        state.reparse(text);
        Some(state)
    }

    /// Apply a completed text edit. `start`/`old_end` describe the replaced
    /// range in the pre-edit buffer, `new_end` the replacement's end in the
    /// post-edit buffer; the points are the matching (row, column-byte)
    /// coordinates. The tree is edited and incrementally reparsed.
    #[allow(clippy::too_many_arguments)]
    /// Shift the tree's positions to match a completed edit and mark it
    /// stale. **Does not reparse** — that costs milliseconds on a large file
    /// and must not sit on the keystroke path; the engine's highlight worker
    /// picks it up. Until it does, the shifted tree still yields spans in
    /// very nearly the right places.
    pub fn edited(
        &mut self,
        text: &Rope,
        start: usize,
        old_end: usize,
        new_end: usize,
        start_point: Point,
        old_end_point: Point,
        new_end_point: Point,
    ) {
        if let Some(tree) = &mut self.tree {
            tree.edit(&InputEdit {
                start_byte: start,
                old_end_byte: old_end,
                new_end_byte: new_end,
                start_position: ts_point(start_point),
                old_end_position: ts_point(old_end_point),
                new_end_position: ts_point(new_end_point),
            });
        }
        let _ = text;
        self.dirty = true;
    }

    /// Drop incremental state and parse from scratch (used after undo/redo,
    /// where the edit shape isn't readily available).
    /// Parse `text`, reusing `old_tree` when given. Free function so the
    /// highlight worker can call it with its own parser, off the lock.
    pub fn parse(
        parser: &mut Parser,
        language: &str,
        text: &Rope,
        old_tree: Option<&Tree>,
    ) -> Option<Tree> {
        let entry = registry().get(language)?;
        parser.set_language(&entry.language).ok()?;
        let mut chunks = text.chunks();
        parser.parse_with_options(
            &mut |offset, _| {
                chunks.seek(offset);
                chunks.next().unwrap_or("").as_bytes()
            },
            old_tree,
            None,
        )
    }

    fn reparse(&mut self, text: &Rope) {
        let tree = Self::parse(&mut self.parser, self.name, text, self.tree.as_ref());
        if let Some(tree) = tree {
            self.tree = Some(tree);
        }
    }

    /// Highlight spans intersecting the byte range, split per row, with
    /// columns converted to UTF-16 offsets. Spans are emitted in capture
    /// order; the UI applies them in order (later wins on overlap).
    pub fn highlights(&self, text: &Rope, range: Range<usize>) -> Vec<HighlightSpan> {
        let Some(tree) = &self.tree else {
            return Vec::new();
        };
        let Some((query, styles)) = &self.language.highlights else {
            return Vec::new();
        };

        let first_row = text.offset_to_point(range.start).row;
        let last_row = text.offset_to_point(range.end).row;
        let mut columns = ColumnConverter::new(text, first_row, last_row);

        let mut cursor = QueryCursor::new();
        cursor.set_byte_range(range.clone());
        let mut spans = Vec::new();
        let mut captures = cursor.captures(query, tree.root_node(), RopeTextProvider(text));
        while let Some((match_, capture_index)) = captures.next() {
            let capture = match_.captures[*capture_index];
            let Some(style) = styles[capture.index as usize] else {
                continue;
            };
            let node_range = capture.node.range();
            let start = node_range.start_point;
            let end = node_range.end_point;
            let span_first = (start.row as u32).max(first_row);
            let span_last = (end.row as u32).min(last_row);
            for row in span_first..=span_last {
                let start_col = if row == start.row as u32 {
                    start.column
                } else {
                    0
                };
                let end_col = if row == end.row as u32 {
                    end.column
                } else {
                    text.line_len(row) as usize
                };
                if end_col <= start_col {
                    continue;
                }
                spans.push(HighlightSpan {
                    row,
                    start_col_utf16: columns.utf16_col(row, start_col),
                    end_col_utf16: columns.utf16_col(row, end_col),
                    style,
                });
            }
        }
        spans
    }
}

fn ts_point(point: Point) -> tree_sitter::Point {
    tree_sitter::Point {
        row: point.row as usize,
        column: point.column as usize,
    }
}

struct RopeTextProvider<'a>(&'a Rope);

struct RopeByteChunks<'a>(rope::Chunks<'a>);

impl<'a> tree_sitter::TextProvider<&'a [u8]> for RopeTextProvider<'a> {
    type I = RopeByteChunks<'a>;

    fn text(&mut self, node: tree_sitter::Node) -> Self::I {
        RopeByteChunks(self.0.chunks_in_range(node.byte_range()))
    }
}

impl<'a> Iterator for RopeByteChunks<'a> {
    type Item = &'a [u8];

    fn next(&mut self) -> Option<Self::Item> {
        self.0.next().map(str::as_bytes)
    }
}

/// Byte-column → UTF-16-column conversion with one lazily-built table per
/// row in the window.
struct ColumnConverter<'a> {
    text: &'a Rope,
    first_row: u32,
    lines: Vec<Option<String>>,
}

impl<'a> ColumnConverter<'a> {
    fn new(text: &'a Rope, first_row: u32, last_row: u32) -> Self {
        ColumnConverter {
            text,
            first_row,
            lines: vec![None; (last_row - first_row + 1) as usize],
        }
    }

    fn utf16_col(&mut self, row: u32, byte_col: usize) -> u32 {
        let slot = (row - self.first_row) as usize;
        let line = self.lines[slot].get_or_insert_with(|| {
            let start = Point::new(row, 0);
            let end = Point::new(row, self.text.line_len(row));
            self.text
                .chunks_in_range(self.text.point_to_offset(start)..self.text.point_to_offset(end))
                .collect()
        });
        let byte_col = byte_col.min(line.len());
        line[..byte_col].encode_utf16().count() as u32
    }
}
