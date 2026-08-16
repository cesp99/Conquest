//! Reparsing off the keystroke path.
//!
//! Tree-sitter's incremental reparse is fast in the sense that matters to a
//! desktop app and slow in the sense that matters to a phone: on a
//! 5000-line file a one-character edit costs a few milliseconds, and the IME
//! sends several edits per keystroke. Doing that inside `Engine::edit` —
//! synchronously, holding the buffer lock, on the thread the UI is waiting
//! on — is what made typing in a large file stutter.
//!
//! So edits only *shift* the tree's positions (microseconds) and mark it
//! stale; this worker does the actual parse on a thread of its own and swaps
//! the result in. Until it lands the view keeps drawing with the shifted
//! tree, which is very nearly right — the same trade Zed makes, where syntax
//! is allowed to lag a frame behind the text.
//!
//! One thread, not a pool: reparses for a buffer must not overlap, and a
//! single worker keeps its parser warm across jobs.

use std::collections::{HashMap, HashSet};
use std::sync::mpsc::{Receiver, Sender, TryRecvError, channel};
use std::sync::{Arc, Mutex};
use std::thread;

use tree_sitter::Parser;

use crate::{BufferId, BufferState};

pub(crate) type Buffers = Arc<Mutex<HashMap<BufferId, BufferState>>>;

/// Queue a buffer for reparsing; sending the same id twice before the worker
/// gets to it is harmless.
pub(crate) struct HighlightWorker {
    requests: Sender<BufferId>,
}

impl HighlightWorker {
    pub fn new(buffers: Buffers) -> HighlightWorker {
        let (requests, incoming) = channel();
        thread::Builder::new()
            .name("conquest-highlight".to_owned())
            .spawn(move || run(buffers, incoming))
            .expect("failed to spawn the highlight worker");
        HighlightWorker { requests }
    }

    pub fn request(&self, id: BufferId) {
        // A closed channel means the worker died, which only happens at
        // teardown; dropping the request is then correct.
        let _ = self.requests.send(id);
    }
}

fn run(buffers: Buffers, incoming: Receiver<BufferId>) {
    let mut parser = Parser::new();
    let mut pending: HashSet<BufferId> = HashSet::new();

    loop {
        // Block for the first id, then drain whatever else is queued so a
        // burst of keystrokes collapses into one parse per buffer.
        let Ok(first) = incoming.recv() else { return };
        pending.insert(first);
        loop {
            match incoming.try_recv() {
                Ok(id) => {
                    pending.insert(id);
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => return,
            }
        }

        for id in pending.drain().collect::<Vec<_>>() {
            reparse(&buffers, &mut parser, id);
        }
    }
}

/// Parse one buffer, holding the lock only to take a snapshot and to install
/// the result.
fn reparse(buffers: &Buffers, parser: &mut Parser, id: BufferId) {
    // Bounded so a buffer being typed into quickly can't spin here forever;
    // whatever is left over is picked up by the next request, which the next
    // edit will send anyway.
    for _ in 0..4 {
        let Some((language, old_tree, rope, version)) = ({
            let buffers = buffers.lock().unwrap();
            buffers.get(&id).and_then(|state| {
                let highlight = state.highlight.as_ref()?;
                if !highlight.is_dirty() {
                    return None;
                }
                let (language, old_tree) = highlight.parse_inputs();
                Some((
                    language,
                    old_tree,
                    state.buffer.as_rope().clone(),
                    state.version,
                ))
            })
        }) else {
            return;
        };

        let Some(tree) =
            crate::highlight::HighlightState::parse(parser, language, &rope, old_tree.as_ref())
        else {
            return;
        };

        let mut buffers = buffers.lock().unwrap();
        let Some(state) = buffers.get_mut(&id) else {
            return;
        };
        if state.version != version {
            // The buffer moved while we parsed. The tree we just built is for
            // older text, so throw it away and go round again rather than
            // installing spans that don't line up.
            continue;
        }
        if let Some(highlight) = &mut state.highlight {
            highlight.install(tree);
        }
        return;
    }
}
