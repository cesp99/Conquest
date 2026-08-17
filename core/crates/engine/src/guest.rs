//! Running a program inside the Debian userland.
//!
//! Everything the engine executes outside its own process goes through here.
//! Android will not run anything that arrived after install, so the only
//! binaries we can reach are the ones `apt` put in the rootfs, and the only
//! way to reach them is proot (agent-docs/research/proot-spike.md, "Open
//! items", item 4). The flag block below is fiddly enough — and its failures
//! quiet enough — that a second copy of it would mean a second behaviour, so
//! `git status` (git.rs) and, from P5-1, the language servers share this one.
//!
//! Three things shape this module.
//!
//! **Identity binds.** proot is told `-b <dir>:<dir>`, mapping a host path
//! onto the *same* guest path, rather than the terminal's `-b
//! <projects>:/projects`. The terminal remaps because a human wants a short
//! prompt; the engine must not, because every path that crosses this boundary
//! — an argument going in, a path coming back in a diagnostic — would
//! otherwise need translating in both directions, and one forgotten
//! translation is a whole class of bug. With an identity bind there is nothing
//! to translate.
//!
//! **One-shot and resident are different problems.** [`capture`] runs a
//! program to completion under a deadline and hands back its stdout: it is
//! what a query wants, and nothing is delivered until the program exits.
//! [`spawn`] leaves the program running with all three pipes open and gives
//! the caller the handle: it is what a language server is, and a deadline
//! would kill a healthy one. They share the flags, the binds and the guest
//! environment, and nothing else.
//!
//! **This is not the terminal's proot command line, on purpose.** Kotlin's
//! `DebianUserland.inside` builds its own and differs in three ways, each of
//! which is right there and wrong here: it remaps the projects directory to
//! `/projects` (see above), it sets `TERM`, `COLORTERM` and `PS1` because a
//! human is reading the output, and it rewrites `/etc/resolv.conf` every
//! session because `apt` needs a resolver matching the current network, which
//! a status query and an LSP pipe never touch. What the two must keep in step
//! is the part that describes the *rootfs* rather than the caller — `-0`,
//! `--link2symlink`, `--kill-on-exit`, `-k` — because both are looking at the
//! same unpacked Debian.

use std::ffi::{OsStr, OsString};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStderr, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

/// How often a supervising loop checks on a child.
const POLL_INTERVAL: Duration = Duration::from_millis(20);

/// How long proot gets to take its tracees down after SIGQUIT, before we
/// resort to SIGKILL. The same grace the Kotlin side gives it.
const QUIT_GRACE: Duration = Duration::from_secs(3);

/// Where the guest lives. The engine never guesses any of this: Kotlin knows
/// the flavour, the install state and the paths, and hands them over through
/// [`Engine::set_userland`](crate::Engine::set_userland).
#[derive(Debug)]
pub(crate) struct Userland {
    /// The proot executable, in `nativeLibraryDir`.
    proot: PathBuf,
    /// The unpacked Debian rootfs.
    rootfs: PathBuf,
    /// `PROOT_TMP_DIR`; proot's compiled-in default points into Termux's
    /// private storage, which we cannot write.
    tmp_dir: PathBuf,
    /// The projects directory, bound onto itself so that every project inside
    /// it is visible at its real path.
    projects_dir: PathBuf,
}

impl Userland {
    /// Whether the guest is actually on disk.
    ///
    /// Being configured is not the same as being present — the user can remove
    /// the rootfs while the engine is still holding its paths — and callers
    /// here answer "nothing to do" rather than raising an error, so the cheap
    /// check has to happen before the doomed spawn rather than after it.
    pub(crate) fn is_installed(&self) -> bool {
        if !self.proot.is_file() {
            log::debug!("no proot at {}", self.proot.display());
            return false;
        }
        if !self.rootfs.is_dir() {
            log::debug!("no rootfs at {}", self.rootfs.display());
            return false;
        }
        true
    }
}

impl crate::Engine {
    /// Tell the engine where proot and the Debian rootfs are.
    ///
    /// Called once from the platform layer, in the `full` flavour, once the
    /// userland reports itself installed. The `play` flavour never calls it,
    /// and every guest run simply stays quiet.
    pub fn set_userland(&self, proot: &Path, rootfs: &Path, tmp_dir: &Path, projects_dir: &Path) {
        let userland = Userland {
            proot: proot.to_path_buf(),
            rootfs: rootfs.to_path_buf(),
            tmp_dir: tmp_dir.to_path_buf(),
            projects_dir: projects_dir.to_path_buf(),
        };
        log::info!("userland configured: {userland:?}");
        *self.userland.lock().unwrap() = Some(Arc::new(userland));
    }

    /// Forget the userland — after the user removes the rootfs, say. Anything
    /// that needs the guest then degrades exactly as in a build that never had
    /// one.
    pub fn clear_userland(&self) {
        *self.userland.lock().unwrap() = None;
    }

    /// The configured userland, if there is one.
    ///
    /// Handed out as an `Arc` so a worker thread can hold it without holding
    /// the lock, and so a `clear_userland` mid-run only affects the *next*
    /// caller rather than pulling the rootfs out from under this one.
    pub(crate) fn userland(&self) -> Option<Arc<Userland>> {
        self.userland.lock().unwrap().clone()
    }
}

/// One program to run inside the guest: the caller's half of the command line.
///
/// Split from the proot half because that half is the same every time and this
/// half never is.
pub(crate) struct GuestCommand {
    /// Names the run in this module's log lines, where the full argv would be
    /// noise ("git status", "rust-analyzer").
    label: String,
    /// Everything from the program name onwards, as the guest will see it.
    argv: Vec<OsString>,
    /// Host directories this run needs visible, beyond the projects directory
    /// every run gets.
    binds: Vec<PathBuf>,
    /// Added on top of the guest environment every run gets.
    env: Vec<(OsString, OsString)>,
}

impl GuestCommand {
    pub(crate) fn new(label: impl Into<String>, argv: Vec<OsString>) -> Self {
        Self {
            label: label.into(),
            argv,
            binds: Vec::new(),
            env: Vec::new(),
        }
    }

    /// Make a host directory reachable inside the guest, at its own path.
    ///
    /// Free to call for a directory already inside the projects directory:
    /// [`bind_dirs`] drops it.
    pub(crate) fn bind(mut self, dir: &Path) -> Self {
        self.binds.push(dir.to_path_buf());
        self
    }

    pub(crate) fn env(mut self, key: impl AsRef<OsStr>, value: impl AsRef<OsStr>) -> Self {
        self.env
            .push((key.as_ref().to_owned(), value.as_ref().to_owned()));
        self
    }
}

/// The proot invocation for a command: flags, binds, guest environment, argv.
///
/// Assembled in one place so the tests can pin it literally. A dropped flag
/// here does not fail loudly — it fails as a guest that cannot see a
/// directory, or a git that decides the repository has dubious ownership —
/// which is why the argv is a test rather than a comment.
fn proot_command(userland: &Userland, command: &GuestCommand) -> Command {
    let mut proot = Command::new(&userland.proot);
    proot
        // The guest must believe it is root. Besides matching how the rootfs
        // was unpacked, proot's fake_id0 also reports files as owned by root,
        // which is what keeps git's "dubious ownership" check quiet.
        .arg("-0")
        // Don't leave a guest process behind if we have to kill proot;
        // Android's phantom-process killer counts them against us.
        .arg("--kill-on-exit")
        // The rootfs was unpacked with this on, and dpkg keeps using it, so
        // the guest's own files are only presented correctly with it on here
        // too. Nothing here creates a link, so it costs a translation and
        // nothing else.
        .arg("--link2symlink")
        // Debian's binaries are happy on any kernel, but the guest asking
        // uname is one less thing to differ from the terminal's environment.
        .args(["-k", "6.2.1"])
        .arg("-r")
        .arg(&userland.rootfs)
        // The same three the terminal binds; without /proc, sub-processes
        // misbehave in ways that are tedious to diagnose.
        .args(["-b", "/dev", "-b", "/proc", "-b", "/sys"]);

    for dir in bind_dirs(userland, &command.binds) {
        proot.arg("-b").arg(identity_bind(&dir));
    }

    proot
        // `/` always exists inside the guest, and with identity binds a
        // program that cares about its directory says so itself (git's `-C`).
        .args(["-w", "/"])
        .args(&command.argv);

    proot
        .env("PROOT_TMP_DIR", &userland.tmp_dir)
        // The child inherits *our* environment, in which PATH points at
        // /system/bin — a directory that does not exist inside the fake root,
        // which is why the spike saw "command not found" for everything. Give
        // the guest a guest PATH.
        .env(
            "PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        .env("HOME", "/root")
        .env("LANG", "C.UTF-8")
        // Machine-readable output is not localised, but *errors* are, and we
        // log them.
        .env("LC_ALL", "C");
    for (key, value) in &command.env {
        proot.env(key, value);
    }
    proot
}

/// Run a program in the guest to completion and return its stdout, or `None`
/// if it could not be started, timed out, or exited non-zero.
///
/// Nothing arrives until it exits: this is for queries whose answer is their
/// output. A process the caller wants to talk to is [`spawn`]'s job.
pub(crate) fn capture(
    userland: &Userland,
    command: &GuestCommand,
    timeout: Duration,
) -> Option<Vec<u8>> {
    let label = &command.label;
    let mut proot = proot_command(userland, command);
    proot
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = match proot.spawn() {
        Ok(child) => child,
        Err(err) => {
            log::debug!("{label} could not start: {err}");
            return None;
        }
    };

    // DEADLOCK, and why this is not a `try_wait` loop over a piped child.
    //
    // A pipe holds 64 KiB. `git status` on a repository with a few thousand
    // changed files writes more than that, then blocks in `write(2)` until
    // somebody reads. A supervisor that polls `try_wait` and only reads after
    // the child exits waits for a child that is waiting for the supervisor:
    // neither moves, and the run "times out" on a program that was working
    // perfectly. So both pipes are drained *concurrently*, by a thread each,
    // for the entire lifetime of the child. The main thread here does nothing
    // but watch the clock, which is the one job it can do without blocking on
    // a pipe.
    //
    // (`Command::output()` gets this right too — it polls both pipes — but it
    // consumes the child, leaving nothing to `kill()` when the timeout fires.)
    let mut stdout = child.stdout.take()?;
    let mut stderr = child.stderr.take()?;
    let out_reader = thread::spawn(move || {
        let mut buffer = Vec::new();
        let _ = stdout.read_to_end(&mut buffer);
        buffer
    });
    let err_reader = thread::spawn(move || {
        let mut buffer = Vec::new();
        let _ = stderr.read_to_end(&mut buffer);
        buffer
    });

    let deadline = Instant::now() + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(err) => {
                log::debug!("{label} could not be waited on: {err}");
                break None;
            }
        }
        if Instant::now() >= deadline {
            log::debug!("{label} timed out after {timeout:?}; killing it");
            terminate(&mut child);
            break None;
        }
        thread::sleep(POLL_INTERVAL);
    };

    // Joining is safe now: the readers finish as soon as the pipes close,
    // which killing the child guarantees.
    let out = out_reader.join().unwrap_or_default();
    let err = err_reader.join().unwrap_or_default();

    let status = status?;
    if !status.success() {
        // For git, the overwhelmingly common cause is "git is not installed in
        // the guest", which is a perfectly ordinary state for a fresh Debian.
        log::debug!(
            "{label} exited with {status}: {}",
            String::from_utf8_lossy(&err).trim()
        );
        return None;
    }
    Some(out)
}

/// Start a resident program in the guest and hand back its handle, with all
/// three pipes open.
///
/// No deadline: the caller decides when this is over, because for a language
/// server "still running after twenty seconds" is health, not a hang. Nothing
/// is read or written here either — the pipes belong to the caller, who is the
/// only one who knows the protocol on them.
///
/// **P5-1 still has to add**, and this deliberately does not:
///
/// * *Framing.* `Content-Length` headers off a live stdout, parsed as bytes
///   arrive. Not [`capture`]'s accumulate-until-EOF, which for a server that
///   never exits delivers the first reply never.
/// * *Restart.* A server that dies takes its client's state with it; somebody
///   has to notice (the handle below only knows when asked), decide whether to
///   restart, and give up rather than loop.
/// * *A registry, and the budget it enforces.* Android caps background child
///   processes — 32 where it is enforced — and that cap is already shared with
///   the terminal's shells and the git status runs, with every proot adding
///   tracees of its own. One handle per server per project is a decision that
///   needs a number attached to it, which is P5-4's.
#[allow(dead_code, reason = "the seam exists before its first caller, in P5-1")]
pub(crate) fn spawn(userland: &Userland, command: &GuestCommand) -> Option<GuestProcess> {
    let mut proot = proot_command(userland, command);
    proot
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = match proot.spawn() {
        Ok(child) => child,
        Err(err) => {
            log::debug!("{} could not start: {err}", command.label);
            return None;
        }
    };
    Some(GuestProcess {
        label: command.label.clone(),
        stdin: child.stdin.take(),
        stdout: child.stdout.take(),
        stderr: child.stderr.take(),
        child,
    })
}

/// A guest process the caller owns.
///
/// Dropping it shuts the process down the careful way (see [`terminate`]), so
/// losing the handle cannot leave a proot and its tracees behind — which on
/// Android is not a leak but a quota.
pub(crate) struct GuestProcess {
    label: String,
    child: Child,
    stdin: Option<ChildStdin>,
    stdout: Option<ChildStdout>,
    stderr: Option<ChildStderr>,
}

#[allow(dead_code, reason = "the seam exists before its first caller, in P5-1")]
impl GuestProcess {
    /// The write half. Taken rather than borrowed because the caller will want
    /// it behind its own lock, away from whatever owns the read half.
    pub(crate) fn take_stdin(&mut self) -> Option<ChildStdin> {
        self.stdin.take()
    }

    /// The read half, for the caller's own framing thread.
    pub(crate) fn take_stdout(&mut self) -> Option<ChildStdout> {
        self.stdout.take()
    }

    /// Servers log here, sometimes voluminously. Whoever takes it must keep
    /// reading it: an unread stderr fills its pipe and blocks the server for
    /// good.
    pub(crate) fn take_stderr(&mut self) -> Option<ChildStderr> {
        self.stderr.take()
    }

    /// Has it exited? Never blocks; `None` means still running (or that the
    /// wait itself failed, which is reported once and then indistinguishable).
    pub(crate) fn exit_status(&mut self) -> Option<std::process::ExitStatus> {
        match self.child.try_wait() {
            Ok(status) => status,
            Err(err) => {
                log::debug!("{} could not be waited on: {err}", self.label);
                None
            }
        }
    }
}

impl Drop for GuestProcess {
    fn drop(&mut self) {
        terminate(&mut self.child);
    }
}

/// The directories proot must be able to see, deduplicated.
///
/// The projects directory covers the normal case in one bind. A caller's own
/// directory is added only when it sits outside it — an imported project whose
/// enclosing repository lives elsewhere — because a bind of a path already
/// inside another bind is just noise.
fn bind_dirs(userland: &Userland, extra: &[PathBuf]) -> Vec<PathBuf> {
    let mut dirs = vec![userland.projects_dir.clone()];
    for dir in extra {
        if !dir.starts_with(&userland.projects_dir) {
            dirs.push(dir.clone());
        }
    }
    dirs
}

/// `-b <path>:<path>`: the host path mounted at the identical guest path.
fn identity_bind(path: &Path) -> String {
    let path = path.to_string_lossy();
    format!("{path}:{path}")
}

/// Stop proot without orphaning what it is tracing.
///
/// `Child::kill` is SIGKILL, and proot never sees it: it dies where it stands
/// and its tracees — a program that has stopped answering, and whatever it
/// forked — keep running, counting against Android's cap on background child
/// processes with nothing left holding a handle to them. proot does act on
/// SIGQUIT, and takes its tracees down with it, so ask that way first and give
/// it a moment. This is the lesson `GitClone.terminate` already learned on the
/// Kotlin side.
///
/// SIGKILL stays as the last resort, for a proot that ignores even this.
fn terminate(child: &mut Child) {
    #[cfg(unix)]
    {
        // Safety: `child` is alive here — nothing has reaped it, since the only
        // waits on it are this function's own — so the pid cannot have been
        // recycled onto some other process.
        unsafe { libc::kill(child.id() as libc::pid_t, libc::SIGQUIT) };
        let deadline = Instant::now() + QUIT_GRACE;
        while Instant::now() < deadline {
            match child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) => thread::sleep(POLL_INTERVAL),
                Err(_) => break,
            }
        }
    }
    let _ = child.kill();
    let _ = child.wait();
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn userland() -> Userland {
        Userland {
            proot: PathBuf::from("/lib/libproot_exec.so"),
            rootfs: PathBuf::from("/files/debian"),
            tmp_dir: PathBuf::from("/cache"),
            projects_dir: PathBuf::from("/files/projects"),
        }
    }

    fn argv_of(command: &Command) -> Vec<String> {
        command
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect()
    }

    fn env_of(command: &Command) -> BTreeMap<String, String> {
        command
            .get_envs()
            .map(|(key, value)| {
                (
                    key.to_string_lossy().into_owned(),
                    value.unwrap_or_default().to_string_lossy().into_owned(),
                )
            })
            .collect()
    }

    /// The flags, spelled out.
    ///
    /// Every one of them was arrived at by watching something fail on a phone,
    /// and every one of them fails *quietly* when it goes missing — a guest
    /// that cannot see a directory, a uname a package dislikes, a tracee left
    /// running after proot is gone. So the argv is pinned literally here:
    /// dropping a flag should cost a red test, not a device session.
    #[test]
    fn the_proot_command_line_is_exactly_this() {
        let command = GuestCommand::new("test", vec![OsString::from("true")]);
        let proot = proot_command(&userland(), &command);

        assert_eq!(proot.get_program(), OsStr::new("/lib/libproot_exec.so"));
        assert_eq!(
            argv_of(&proot),
            vec![
                "-0",
                "--kill-on-exit",
                "--link2symlink",
                "-k",
                "6.2.1",
                "-r",
                "/files/debian",
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/files/projects:/files/projects",
                "-w",
                "/",
                "true",
            ]
        );
    }

    /// The guest environment, spelled out for the same reason. PATH is the one
    /// that stings: inherited from Android it names /system/bin, which does
    /// not exist inside the fake root, and *every* command is then "not
    /// found".
    #[test]
    fn the_guest_environment_is_exactly_this() {
        let command = GuestCommand::new("test", vec![OsString::from("true")]);
        let env = env_of(&proot_command(&userland(), &command));

        assert_eq!(
            env,
            BTreeMap::from([
                ("PROOT_TMP_DIR".to_owned(), "/cache".to_owned()),
                (
                    "PATH".to_owned(),
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".to_owned()
                ),
                ("HOME".to_owned(), "/root".to_owned()),
                ("LANG".to_owned(), "C.UTF-8".to_owned()),
                ("LC_ALL".to_owned(), "C".to_owned()),
            ])
        );
    }

    #[test]
    fn a_caller_adds_to_the_environment_rather_than_replacing_it() {
        let command = GuestCommand::new("test", vec![OsString::from("true")])
            .env("GIT_OPTIONAL_LOCKS", "0")
            .env("LC_ALL", "en_GB.UTF-8");
        let env = env_of(&proot_command(&userland(), &command));

        assert_eq!(env.get("HOME"), Some(&"/root".to_owned()));
        assert_eq!(env.get("GIT_OPTIONAL_LOCKS"), Some(&"0".to_owned()));
        // Last writer wins, so a caller that really needs a different locale
        // can have one.
        assert_eq!(env.get("LC_ALL"), Some(&"en_GB.UTF-8".to_owned()));
    }

    #[test]
    fn binds_are_identities_and_deduplicated() {
        let userland = userland();
        // The host path is mounted at the identical guest path: nothing to
        // translate in either direction.
        assert_eq!(
            identity_bind(Path::new("/files/projects")),
            "/files/projects:/files/projects"
        );
        // A directory inside the projects directory needs no bind of its own.
        assert_eq!(
            bind_dirs(&userland, &[PathBuf::from("/files/projects/thing")]),
            vec![PathBuf::from("/files/projects")]
        );
        // One outside does.
        assert_eq!(
            bind_dirs(&userland, &[PathBuf::from("/elsewhere/repo")]),
            vec![
                PathBuf::from("/files/projects"),
                PathBuf::from("/elsewhere/repo"),
            ]
        );
    }

    #[test]
    fn a_caller_bind_reaches_the_command_line() {
        let command = GuestCommand::new("test", vec![OsString::from("true")])
            .bind(Path::new("/elsewhere/repo"));
        let argv = argv_of(&proot_command(&userland(), &command));
        assert!(argv.contains(&"/elsewhere/repo:/elsewhere/repo".to_owned()));
    }

    /// Stand in for proot, so the plumbing either side of it can be tested on
    /// a host that has no rootfs.
    ///
    /// It drops every flag up to and including `-w <dir>` and execs the rest,
    /// which is exactly the contract [`proot_command`] relies on: the guest
    /// argv is the tail of the command line. It cannot pretend to be a fake
    /// root, and does not try — what is under test here is the deadline, the
    /// draining and the pipes, none of which care what the program is.
    #[cfg(unix)]
    fn fake_proot(dir: &Path) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;

        let path = dir.join("fake-proot");
        std::fs::write(
            &path,
            "#!/bin/sh\nwhile [ \"$1\" != \"-w\" ]; do shift; done\nshift 2\nexec \"$@\"\n",
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        path
    }

    #[cfg(unix)]
    fn fake_userland(dir: &Path) -> Userland {
        Userland {
            proot: fake_proot(dir),
            rootfs: dir.to_path_buf(),
            tmp_dir: dir.to_path_buf(),
            projects_dir: dir.to_path_buf(),
        }
    }

    #[cfg(unix)]
    fn sh(script: &str) -> Vec<OsString> {
        vec![
            OsString::from("/bin/sh"),
            OsString::from("-c"),
            OsString::from(script),
        ]
    }

    #[test]
    #[cfg(unix)]
    fn a_capture_returns_stdout_and_swallows_stderr() {
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let command = GuestCommand::new("test", sh("printf hello; printf oops >&2"));
        let out = capture(&guest, &command, Duration::from_secs(10));
        assert_eq!(out, Some(b"hello".to_vec()));
    }

    #[test]
    #[cfg(unix)]
    fn a_capture_of_more_than_a_pipeful_does_not_deadlock() {
        // 64 KiB is the pipe buffer; a program writing past it blocks unless
        // somebody is draining. Both pipes are loaded here, so neither can be
        // the one that saves the other.
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let script = "i=0; while [ $i -lt 4000 ]; do \
                      echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; \
                      echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb >&2; i=$((i+1)); done";
        let command = GuestCommand::new("test", sh(script));
        let out = capture(&guest, &command, Duration::from_secs(30))
            .expect("a program that outlives one pipeful still completes");
        assert_eq!(out.len(), 4000 * 31);
    }

    #[test]
    #[cfg(unix)]
    fn a_failing_capture_is_none_rather_than_partial_output() {
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let command = GuestCommand::new("test", sh("printf half; exit 3"));
        assert_eq!(capture(&guest, &command, Duration::from_secs(10)), None);
    }

    #[test]
    #[cfg(unix)]
    fn a_capture_past_its_deadline_is_killed() {
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let command = GuestCommand::new("test", sh("sleep 30"));
        let started = Instant::now();
        assert_eq!(capture(&guest, &command, Duration::from_millis(200)), None);
        // The deadline is what ended it, not the sleep.
        assert!(started.elapsed() < Duration::from_secs(20));
    }

    #[test]
    #[cfg(unix)]
    fn a_spawned_process_talks_both_ways_and_dies_with_its_handle() {
        use std::io::{BufRead, BufReader, Write};

        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let script = "while read line; do echo \"got $line\"; done";
        let command = GuestCommand::new("test", sh(script));
        let mut process = spawn(&guest, &command).expect("the fake proot starts");

        // Bidirectional, and alive between the two: nothing here waits for the
        // process to exit before the caller sees a byte.
        let mut stdin = process.take_stdin().expect("stdin is piped");
        let mut stdout = BufReader::new(process.take_stdout().expect("stdout is piped"));
        writeln!(stdin, "ping").unwrap();
        stdin.flush().unwrap();
        let mut line = String::new();
        stdout.read_line(&mut line).unwrap();
        assert_eq!(line, "got ping\n");
        assert!(process.take_stderr().is_some());
        assert_eq!(process.exit_status(), None, "still running");

        // Dropping the handle is the shutdown: nothing survives it, because on
        // Android a survivor spends the process budget with nobody left to
        // stop it.
        let pid = process.child.id();
        drop(stdin);
        drop(process);
        #[cfg(unix)]
        // Signal 0 only asks whether the pid is still ours to signal; the
        // process has been reaped by `terminate`, so it cannot be.
        assert_eq!(
            unsafe { libc::kill(pid as libc::pid_t, 0) },
            -1,
            "the process outlived its handle"
        );
    }

    #[test]
    fn there_is_no_userland_until_the_platform_layer_says_so() {
        let engine = crate::Engine::new();
        assert!(engine.userland().is_none());

        let dir = tempfile::tempdir().unwrap();
        engine.set_userland(
            &dir.path().join("proot"),
            &dir.path().join("debian"),
            dir.path(),
            dir.path(),
        );
        assert!(engine.userland().is_some());

        engine.clear_userland();
        assert!(engine.userland().is_none());
    }
}
