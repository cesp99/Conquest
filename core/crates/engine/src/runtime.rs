//! The engine's gpui runtime, on a thread of its own.
//!
//! One `gpui::Application` runs on a dedicated thread for the life of the
//! process. That thread is gpui's "main" thread: entities live there, and
//! `Worktree` and friends expect to be touched from it. The Android main
//! thread must never block on it — JNI calls hand work over and return.
//!
//! Two directions of traffic:
//!
//! - **In**: [`Runtime::spawn`] queues a closure that receives `&mut App` on
//!   the runtime thread. Senders are plain channels, so any thread may call it.
//! - **Out**: results are mirrored into ordinary `Mutex`-guarded state that
//!   JNI reads without touching gpui at all (see `project.rs`). Reads are
//!   therefore lock-free of the runtime and safe from the UI thread.

use std::rc::Rc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::thread;

use futures::StreamExt as _;
use futures::channel::mpsc;
use gpui::{App, Application};

use crate::platform::HeadlessPlatform;

/// Work to run on the runtime thread with access to the gpui context.
type Job = Box<dyn FnOnce(&mut App) + Send>;

/// A gpui `App` running on its own thread, plus the channel into it.
pub struct Runtime {
    jobs: mpsc::UnboundedSender<Job>,
}

/// gpui's foreground work can nest fairly deeply (entity updates inside task
/// polls inside effect flushes), and Zed warns that message handling wants
/// ~0.5 MiB in unoptimized builds. Ask for headroom rather than discover the
/// limit as a stack overflow on a device.
const ENGINE_STACK_SIZE: usize = 8 * 1024 * 1024;

impl Runtime {
    /// Start the runtime thread and wait for the app to come up. `init` runs
    /// on the runtime thread before any job, for global setup
    /// (`settings::init` and the like).
    pub fn new(init: impl FnOnce(&mut App) + Send + 'static) -> Runtime {
        let (jobs, mut incoming) = mpsc::unbounded::<Job>();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel::<()>();

        thread::Builder::new()
            .name("conquest-engine".to_owned())
            .stack_size(ENGINE_STACK_SIZE)
            .spawn(move || {
                let app = Application::with_platform(Rc::new(HeadlessPlatform::new()));
                app.run(move |cx| {
                    init(cx);
                    // Only signal readiness once init has run, so callers
                    // can't observe a half-configured App.
                    let _ = ready_tx.send(());
                    cx.spawn(async move |cx| {
                        while let Some(job) = incoming.next().await {
                            cx.update(|cx| job(cx));
                        }
                    })
                    .detach();
                });
            })
            .expect("failed to spawn the engine runtime thread");

        ready_rx
            .recv()
            .expect("the engine runtime thread died during startup");

        Runtime { jobs }
    }

    /// Queue `job` to run on the runtime thread. Returns immediately.
    pub fn spawn(&self, job: impl FnOnce(&mut App) + Send + 'static) {
        // A closed channel means the runtime thread is gone, which only
        // happens at process teardown; dropping the job is then correct.
        let _ = self.jobs.unbounded_send(Box::new(job));
    }
}

/// Ids handed to `Worktree::local`. Zed derives them from its project model;
/// we just need them unique per worktree.
pub fn next_worktree_handle() -> usize {
    static NEXT: AtomicUsize = AtomicUsize::new(1);
    NEXT.fetch_add(1, Ordering::Relaxed)
}
