package to.eyed.conquest.code.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.eyed.conquest.code.terminal.GuestProcess
import to.eyed.conquest.code.terminal.Userland

/**
 * A language server, and the Debian packages that put it on the guest's PATH.
 *
 * [server] is the binary the engine spawns — `core/crates/engine/src/lsp.rs`'s
 * `server_for`, which is the table this one exists to make installable — and
 * [grammars] are the engine's own grammar names, as `BufferSession.language`
 * reports them.
 *
 * **Every package is named.** `--no-install-recommends` is the house rule
 * (agent-docs/research/lsp-approach.md), and it is the reason [packages] is a
 * list rather than a string: `python3-pylsp` alone starts, initializes, reports
 * `sync Incremental` and then publishes *nothing at all*, because `import
 * pyflakes` fails inside it. A server that runs and says nothing is worse than
 * one that is missing, so its linter is named here beside it.
 */
data class LanguageServerPackage(
    /** What to call the language in a sentence: "Python". */
    val language: String,
    /** The binary the engine will try to spawn (lsp.rs:107-147). */
    val server: String,
    /** Engine grammar names this server answers for. */
    val grammars: List<String>,
    /** Debian packages, in the order apt should be given them. */
    val packages: List<String>,
    /** Why the extra packages are here, for the prompt's second line. */
    val note: String? = null,
) {
    /** "python3-pylsp and python3-pyflakes" — the packages, said out loud. */
    /** "is" for one package, "are" for several — [packageList]'s verb. */
    val packagesAre: String
        get() = if (packages.size == 1) "is" else "are"

    val packageList: String
        get() = when (packages.size) {
            0 -> ""
            1 -> packages[0]
            2 -> "${packages[0]} and ${packages[1]}"
            else -> packages.dropLast(1).joinToString(", ") + " and " + packages.last()
        }
}

/**
 * What apt says an install would cost, read out of the dry run
 * ([LanguageServers.estimateArgv]).
 *
 * Nothing here is guessed. A number apt did not print is null and the prompt
 * says so, because a made-up download size is exactly the kind of lie that
 * turns into "it said 12 MB and used 300".
 */
data class AptPlan(
    /** Bytes to download, or null when apt did not say. */
    val downloadBytes: Long?,
    /** Bytes the install will occupy, or null. */
    val diskBytes: Long?,
    /** How many packages apt would newly install. */
    val newPackages: Int,
    /** Packages apt could not find — usually because its lists are empty. */
    val missing: List<String>,
    /**
     * Whether apt printed its `N upgraded, N newly installed …` summary at
     * all.
     *
     * Without this, "apt said nothing" and "apt said there is nothing to do"
     * are the same plan — zero packages, no size — and a guest where `apt-get`
     * itself failed to start would be read as "already installed", which is
     * the one answer that leaves the user nowhere to go.
     */
    val hasSummary: Boolean,
)

/**
 * The grammar → apt package table, and the sentences said about it.
 *
 * P5-2's whole job, and deliberately small: four servers, each one verified to
 * exist in Debian stable/main on the device (agent-docs/research/
 * lsp-approach.md's table, checked with `apt-cache policy` inside the guest).
 * A grammar that is not here has no packaged server, which is a normal state
 * rather than a hole — we highlight far more languages than Debian packages a
 * server for.
 *
 * **Nothing in this file installs anything.** Zed asks first — it offers the
 * extension for a file type and waits ("Do you want to install the recommended
 * '{}' extension for '{}' files?", extensions_ui/src/extension_suggest.rs:176)
 * — and so does [LanguageServerInstaller]. A missing server is a prompt, never
 * a download that starts itself.
 */
object LanguageServers {

    /**
     * A Debian binary-package name, as policy defines it: lower case, and only
     * `+ - .` besides letters and digits. Checked rather than assumed because
     * [installArgv] hands the names to `/bin/sh -c` — the two commands apt
     * needs cannot be one argv — and a name with a space or a `;` in it would
     * be a command, not a package.
     */
    private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]+")

    /** Every server we can install, in the order the picker lists them. */
    val ALL: List<LanguageServerPackage> = listOf(
        LanguageServerPackage(
            language = "Rust",
            server = "rust-analyzer",
            grammars = listOf("rust"),
            // Debian stable/main, 1.85.0+dfsg3-1 — **and `cargo`**, which it
            // does not depend on and cannot work without: rust-analyzer
            // builds its crate graph by running `cargo metadata`, so without
            // cargo it starts, initializes, reports a clean file and answers
            // no completion at all. Proved on the emulator: with
            // rust-analyzer alone, `which cargo` says nothing and `x.` in a
            // Rust file offers nothing while the server sits there refreshing
            // semantic tokens. This is the pyflakes lesson again — a server
            // that runs and says nothing is the failure mode this table
            // exists to prevent.
            packages = listOf("rust-analyzer", "cargo"),
            note = "rust-analyzer reads the crate graph with cargo, so both are installed.",
        ),
        LanguageServerPackage(
            language = "C and C++",
            server = "clangd",
            // One server, two grammars — lsp.rs:126-127 sends both to clangd.
            grammars = listOf("c", "cpp"),
            // Debian stable/main, 1:19.0-63. The metapackage pulls the
            // versioned clangd-19; naming the versioned one instead would rot
            // at the next Debian release.
            packages = listOf("clangd"),
        ),
        LanguageServerPackage(
            language = "Python",
            server = "pylsp",
            grammars = listOf("python"),
            // Debian stable/main, 1.12.0-3 — and python3-pyflakes, which is
            // only a *recommendation* of it. With --no-install-recommends and
            // pylsp alone the server starts, initializes and publishes zero
            // diagnostics forever; this was proven on the device before the
            // table was written.
            packages = listOf("python3-pylsp", "python3-pyflakes"),
            note = "pylsp reports nothing without pyflakes, so both are installed.",
        ),
        LanguageServerPackage(
            language = "Go",
            server = "gopls",
            grammars = listOf("go"),
            // Debian stable/main, 2:0.16.1+ds-1.
            packages = listOf("gopls"),
        ),
    )

    /**
     * Grammars the engine will ask a server for and Debian stable does not
     * package, with what to say instead of offering an install that must fail.
     *
     * `typescript-language-server` is in npm and nowhere in the Debian archive
     * — `packages.debian.org/stable/node-typescript-language-server` is a 404,
     * and `sources.debian.org`'s search for it comes back empty. The engine
     * still tries to spawn it (lsp.rs:145-146) and reports `unavailable` with
     * "command not found", which is honest; what would not be honest is a
     * button that runs `apt-get install` on a package that does not exist.
     */
    val UNPACKAGED: Map<String, String> = mapOf(
        "typescript" to "TypeScript",
        "tsx" to "TypeScript",
    )

    /** The recipe for [grammar], or null when there is none to install. */
    fun forGrammar(grammar: String?): LanguageServerPackage? {
        if (grammar == null) return null
        return ALL.firstOrNull { grammar in it.grammars }
    }

    /**
     * The recipe for a server by name — the status bar's route in, because
     * `lspServers` reports the binary ("clangd"), not the grammar.
     */
    fun forServer(server: String?): LanguageServerPackage? {
        if (server == null) return null
        return ALL.firstOrNull { it.server == server }
    }

    /**
     * What to say about a grammar we cannot install a server for, or null when
     * there is nothing to say — the ordinary case, a language with no server.
     */
    fun unpackagedMessage(grammar: String?): String? {
        val language = UNPACKAGED[grammar ?: return null] ?: return null
        return "Debian stable has no $language language server to install. " +
            "The editor will keep highlighting and folding $language; only " +
            "diagnostics, completions and go-to-definition need a server."
    }

    /**
     * What an install would cost, without installing anything.
     *
     * **Not `apt-get install -s`, and this was checked in apt's own source
     * rather than assumed.** The simulator returns from `InstallPackages`
     * *before* the block that prints the sizes — apt 3.0.3 (trixie, our
     * stable) `apt-private/private-install.cc:364-376` returns at the end of
     * "Run the simulator ..", and the `Need to get %sB of archives` /
     * `After this operation, %sB` lines are at 393-411, below it. apt 2.6.1
     * (bookworm) has the same ordering at 232-233 and 264-269. So `-s` can say
     * *what* would be installed and never *what it costs*, and a prompt built
     * on it would have quoted a price it never had.
     *
     * `--assume-no` goes down the real path — statistics, then the "Do you
     * want to continue?" prompt — and answers N itself without reading stdin
     * (`apt-private/private-output.cc:975-1030`), so nothing is fetched,
     * nothing is unpacked, and apt exits 1 having printed the number we came
     * for. It is the dry run apt actually has.
     *
     * A plain argv rather than a shell line: one command, so nothing needs
     * quoting, and the package names never reach a shell at all.
     */
    fun estimateArgv(target: LanguageServerPackage): List<String> =
        listOf("apt-get", "install", "--assume-no", "--no-install-recommends", "--") +
            target.packages

    /**
     * The install itself: refresh the lists, then install exactly the named
     * packages and nothing they merely recommend.
     *
     * `apt-get update` first for the same reason the clone does it: a rootfs
     * whose lists were fetched at image time cannot resolve today's versions,
     * and apt fails with "404 Not Found" rather than saying so.
     */
    fun installArgv(target: LanguageServerPackage): List<String> {
        val names = target.packages
        check(names.isNotEmpty()) { "${target.server} has no packages" }
        check(names.all { PACKAGE_NAME.matches(it) }) {
            "not a Debian package name: ${names.firstOrNull { !PACKAGE_NAME.matches(it) }}"
        }
        return listOf(
            "/bin/sh", "-c",
            "apt-get update && apt-get install -y --no-install-recommends -- " +
                names.joinToString(" "),
        )
    }

    /**
     * The environment both commands run in.
     *
     * `DEBIAN_FRONTEND=noninteractive` is what stops dpkg opening a dialog on
     * a terminal that is not there — the same trap `GIT_TERMINAL_PROMPT=0`
     * closes for git. `LC_ALL=C` is not cosmetic: [parsePlan] reads apt's own
     * words, and a translated "Need to get" would silently become "size
     * unknown".
     */
    val ENVIRONMENT: List<String> = listOf(
        "DEBIAN_FRONTEND=noninteractive",
        "LC_ALL=C",
        "LANG=C",
    )

    // --- reading apt ---------------------------------------------------------

    /**
     * `Need to get 4,096 kB of archives.`, and its partly-cached form
     * `Need to get 0 B/12.4 MB of archives.` — the second number is the total,
     * which is why the first group is optional and discarded.
     *
     * apt 3.0 rewrote this line as `  Download size: 12.4 MB` when
     * `APT::Output-Version` is 30 or above (private-install.cc:396-401), which
     * `apt` sets and `apt-get` does not — so both spellings are read, because
     * which one arrives is a property of the guest's apt rather than of us.
     */
    private val NEED_TO_GET =
        Regex("""(?:Need to get|Download size:) (?:[\d.,]+ ?[kMGT]?B ?/ ?)?([\d.,]+) ?([kMGT]?B)""")

    /**
     * `After this operation, 44.0 MB of additional disk space will be used.`,
     * and apt 3.0's `  Space needed: 44.0 MB / 3,600 MB available`
     * (private-install.cc:405-467).
     */
    private val AFTER_OPERATION = Regex(
        """(?:After this operation, ([\d.,]+) ?([kMGT]?B) of additional disk space""" +
            """|Space needed: ([\d.,]+) ?([kMGT]?B))"""
    )

    /** `0 upgraded, 2 newly installed, 0 to remove and 0 not upgraded.` */
    private val NEWLY_INSTALLED = Regex("""(\d+) newly installed""")

    /** `E: Unable to locate package python3-pylsp` */
    private val UNABLE_TO_LOCATE = Regex("""Unable to locate package (\S+)""")

    /**
     * Read [output] — everything the dry run printed — into a plan.
     *
     * Every field is allowed to be missing, because every one of them really
     * is on some path: a package already installed prints no size at all, and
     * a rootfs that has never run `apt-get update` prints nothing but
     * "Unable to locate package".
     */
    fun parsePlan(output: String): AptPlan = AptPlan(
        downloadBytes = NEED_TO_GET.find(output)
            ?.let { bytesOf(it.groupValues[1], it.groupValues[2]) },
        diskBytes = AFTER_OPERATION.find(output)?.let { match ->
            // One alternative or the other matched; the empty pair is the one
            // that did not.
            bytesOf(match.groupValues[1], match.groupValues[2])
                ?: bytesOf(match.groupValues[3], match.groupValues[4])
        },
        newPackages = NEWLY_INSTALLED.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        missing = UNABLE_TO_LOCATE.findAll(output).map { it.groupValues[1] }.distinct().toList(),
        hasSummary = NEWLY_INSTALLED.containsMatchIn(output),
    )

    /**
     * apt's units are SI — it prints 1,000 bytes as `1,000 B` and 1,000,000 as
     * `1,000 kB` — so kB is a thousand here, not 1024. Sizes are reported the
     * way apt reported them or not at all.
     */
    private fun bytesOf(number: String, unit: String): Long? {
        val value = number.replace(",", "").toDoubleOrNull() ?: return null
        val scale = when (unit) {
            "B" -> 1.0
            "kB" -> 1_000.0
            "MB" -> 1_000_000.0
            "GB" -> 1_000_000_000.0
            "TB" -> 1_000_000_000_000.0
            else -> return null
        }
        return (value * scale).toLong()
    }

    /** apt's own spelling of a size: `857 kB`, `12.4 MB`, `1.2 GB`. */
    fun formatBytes(bytes: Long?): String? {
        if (bytes == null || bytes < 0) return null
        if (bytes < 1_000) return "$bytes B"
        val units = listOf("kB", "MB", "GB", "TB")
        var value = bytes / 1_000.0
        var unit = 0
        while (value >= 1_000 && unit < units.lastIndex) {
            value /= 1_000
            unit++
        }
        // One decimal below a hundred, which is apt's own rule — its
        // `SizeToStr` (apt-pkg/contrib/strutl.cc) prints `%.1f` while the
        // value is under 100 and `%.0f` above it, so "12.4 MB" keeps its
        // tenth and "115 MB" does not. The number beside apt's own transcript
        // has to be spelled the way apt spells it.
        val text = if (value < 100) {
            // Locale.US, or a device set to Italian prints "12,4 MB" beside
            // apt's own "12.4 MB" in the transcript underneath.
            String.format(java.util.Locale.US, "%.1f", value)
        } else {
            value.toInt().toString()
        }
        return "$text ${units[unit]}"
    }

    // --- the sentences -------------------------------------------------------

    /**
     * The question itself: "Python needs a language server — install
     * python3-pylsp and python3-pyflakes (~12.4 MB)?"
     *
     * The size is in the question rather than in a detail line for the reason
     * the clone dialog puts it there: on a phone, on a metered connection, it
     * is half of what the answer depends on. When apt could not say, the
     * question does not pretend it could.
     */
    fun question(target: LanguageServerPackage, plan: AptPlan?): String {
        val size = formatBytes(plan?.downloadBytes)
        val cost = if (size != null) " (~$size)" else ""
        return "${target.language} needs a language server — " +
            "install ${target.packageList}$cost?"
    }

    /**
     * The line under the question: what apt will do, and what it could not
     * say.
     *
     * [userland] is what to call the guest — `Userland.backend.displayName`,
     * passed in rather than read here so this stays a pure function the host
     * tests can run without a flavour.
     */
    fun detail(
        target: LanguageServerPackage,
        plan: AptPlan?,
        userland: String,
    ): String {
        val parts = mutableListOf<String>()
        target.note?.let { parts += it }
        val disk = formatBytes(plan?.diskBytes)
        if (disk != null) parts += "About $disk of the userland's storage will be used."
        if (plan != null && plan.missing.isNotEmpty()) {
            parts += "apt has not downloaded its package lists yet, so it cannot " +
                "say what this will cost until it has."
        }
        parts += "${target.server} runs inside $userland, started by the editor " +
            "when a ${target.grammars.first()} file is open."
        return parts.joinToString(" ")
    }

    /**
     * A sentence the user can act on, from whatever apt printed.
     *
     * Same shape and same intent as `GitClone.explain`: apt has already said
     * it better than we can, so its words are kept verbatim underneath and
     * this goes in front of them.
     */
    fun explainInstall(output: String, target: LanguageServerPackage): String {
        val text = output.lowercase()
        return when {
            "could not resolve" in text ||
                "temporary failure resolving" in text ||
                "network is unreachable" in text ||
                "connection timed out" in text ||
                "connection failed" in text ->
                "Could not reach the Debian archive"

            "unable to locate package" in text ->
                "Debian could not find ${target.packageList}"

            "no space left on device" in text || "not enough free space" in text ->
                "There is not enough room left on the device"

            "could not get lock" in text || "unable to lock" in text ->
                "Another apt is already running in the userland"

            "is not signed" in text || "no_pubkey" in text || "not trusted" in text ->
                "The archive's signatures could not be checked"

            "not found" in text && "http" in text ->
                "The archive has moved on; apt-get update in the terminal will resync it"

            else -> "Could not install ${target.packageList}"
        }
    }
}

/** What the prompt draws. Mirrors `CloneState`, for the same reasons. */
sealed interface ServerInstallState {
    /** Nothing running: the prompt shows the list, or nothing at all. */
    data object Idle : ServerInstallState

    /** Asking apt what it would cost. Short, but not instant on a phone. */
    data class Checking(val target: LanguageServerPackage) : ServerInstallState

    /** The question. [plan] is null when apt could not be asked at all. */
    data class Offered(
        val target: LanguageServerPackage,
        val plan: AptPlan?,
    ) : ServerInstallState

    /**
     * apt has every package already and the server still is not running.
     * Offering the install again would be a loop with nothing on screen to
     * explain it — the lesson `GitClone` learned when apt succeeded and the
     * clone still failed.
     */
    data class AlreadyInstalled(val target: LanguageServerPackage) : ServerInstallState

    /** [step] is apt's last line, throttled to ~10 Hz. */
    data class Installing(
        val target: LanguageServerPackage,
        val step: String,
    ) : ServerInstallState

    /** [detail] is apt's own words, kept verbatim. */
    data class Failed(
        val target: LanguageServerPackage?,
        val summary: String,
        val detail: String?,
    ) : ServerInstallState

    data class Finished(val target: LanguageServerPackage) : ServerInstallState
}

/**
 * Installing a language server from apt, asked first and cancellable
 * throughout.
 *
 * The state lives here rather than in the composition for the reason
 * [to.eyed.conquest.code.terminal.GitClone]'s does: the prompt is a dialog,
 * and dismissing it must not abandon a running `apt-get` half way through
 * unpacking. Reopening the prompt shows the install still going.
 *
 * Nothing starts on its own. [offer] is called by something the user did — a
 * tap on the status bar's unavailable server, or the command palette — and it
 * only ever *asks*; [install] is the answer.
 */
object LanguageServerInstaller {

    private const val TAG = "conquest-lsp-install"

    /** Keep the tail of apt's own words for the error message. */
    private const val TRANSCRIPT_LINES = 12

    var state by mutableStateOf<ServerInstallState>(ServerInstallState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Bumped by every start and every cancel, so a job finishing after it was
     * cancelled — the kill leaves apt with a non-zero status, which looks like
     * a failure — cannot report over whatever the user is doing now.
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var running: Process? = null

    /**
     * False in builds with no userland: the UI must not offer this at all.
     *
     * The `play` flavour has no guest and therefore no apt — `execCommand`
     * returns null there — so installing a server is *absent*, exactly as
     * cloning is (`GitClone.isSupported`, and `WorkspaceCommand.CloneRepository`'s
     * `isOffered`), rather than shown greyed out.
     */
    val isSupported: Boolean get() = Userland.backend.isSupported

    val isBusy: Boolean
        get() = state is ServerInstallState.Checking || state is ServerInstallState.Installing

    /** Back to nothing. Ignored while apt is running. */
    fun dismiss() {
        if (!isBusy) state = ServerInstallState.Idle
    }

    /**
     * Ask apt what installing [target] would cost, and then ask the user.
     *
     * Never installs. What this runs is [LanguageServers.estimateArgv], which
     * answers apt's own confirmation prompt with "no" — nothing is fetched and
     * nothing is unpacked.
     */
    fun offer(context: Context, target: LanguageServerPackage) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val mine = ++generation
        state = ServerInstallState.Checking(target)
        job = scope.launch {
            val result = runCatching { estimate(app, target) }.getOrElse { error ->
                Log.w(TAG, "apt estimate failed", error)
                // A simulation we could not run is not a reason to refuse:
                // ask the question without the price rather than dead-ending.
                ServerInstallState.Offered(target, null)
            }
            running = null
            if (generation != mine) return@launch
            state = result
        }
    }

    /** Say yes to what [offer] asked. */
    fun install(context: Context, target: LanguageServerPackage) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val mine = ++generation
        state = ServerInstallState.Installing(target, "Starting")
        job = scope.launch {
            val result = runCatching { apt(app, target, mine) }.getOrElse { error ->
                Log.e(TAG, "install failed", error)
                ServerInstallState.Failed(
                    target,
                    "Could not install ${target.packageList}",
                    error.message,
                )
            }
            running = null
            if (generation != mine) return@launch
            state = result
        }
    }

    /**
     * Stop apt and forget it.
     *
     * The signalling runs in its own coroutine, a sibling of the install's, so
     * cancelling cannot cancel the cleanup — and so the main thread never
     * blocks on SIGQUIT's grace period. Nothing is deleted: a half-unpacked
     * package is dpkg's to sort out on the next run, and deleting a rootfs
     * directory behind its back would be worse than leaving it.
     */
    fun cancel() {
        val active = job?.takeIf { it.isActive } ?: run {
            dismiss()
            return
        }
        // Captured now, on the main thread: by the time terminate() runs the
        // user may have started something else, and this cancellation must not
        // kill that instead.
        val doomed = running
        running = null
        job = null
        generation++
        state = ServerInstallState.Idle
        scope.launch {
            doomed?.let { GuestProcess.terminate(it) }
            active.cancel()
        }
    }

    // --- the work ------------------------------------------------------------

    private fun estimate(context: Context, target: LanguageServerPackage): ServerInstallState {
        val command = Userland.backend.execCommand(
            context,
            ProjectsRoot.directory(context).absolutePath,
            LanguageServers.estimateArgv(target),
            LanguageServers.ENVIRONMENT,
        ) ?: return ServerInstallState.Failed(target, noUserland(), null)

        val transcript = StringBuilder()
        // The exit status is deliberately ignored. A dry run that found work
        // to do exits 1 ("Abort."), one that found none exits 0, and one that
        // could not find a package exits 100 — all three have something worth
        // reading in them, and none of them is a failure to report.
        GuestProcess.run(command, onStart = { running = it }) { record ->
            transcript.appendLine(record)
        }
        val plan = LanguageServers.parsePlan(transcript.toString())
        // apt never got as far as its own summary — no apt in the rootfs, a
        // broken sources.list, proot refusing to start. Ask the question
        // without a price rather than claiming to know something.
        // A plan that names packages apt could not find is worth showing even
        // though apt never reached its summary — that list is *why* there is
        // no price, and the sentence built for it was otherwise unreachable.
        if (!plan.hasSummary) {
            return ServerInstallState.Offered(target, plan.takeIf { it.missing.isNotEmpty() })
        }
        // Everything present and nothing to install: the packages are there
        // and the server still would not start, which the user needs told
        // rather than offered a no-op download.
        if (plan.newPackages == 0 && plan.missing.isEmpty() && plan.downloadBytes == null) {
            return ServerInstallState.AlreadyInstalled(target)
        }
        return ServerInstallState.Offered(target, plan)
    }

    private fun apt(
        context: Context,
        target: LanguageServerPackage,
        /**
         * The install this reader belongs to. A cancelled apt keeps producing
         * records through proot's SIGQUIT grace period, and a later install
         * has already taken the state by then — guarding on "still
         * Installing" is not enough, because the later one *is* Installing.
         */
        mine: Int,
    ): ServerInstallState {
        val command = Userland.backend.execCommand(
            context,
            ProjectsRoot.directory(context).absolutePath,
            LanguageServers.installArgv(target),
            LanguageServers.ENVIRONMENT,
        ) ?: return ServerInstallState.Failed(target, noUserland(), null)

        val transcript = ArrayDeque<String>()
        var lastStep = 0L
        val exit = GuestProcess.run(command, onStart = { running = it }) { record ->
            if (transcript.size >= TRANSCRIPT_LINES) transcript.removeFirst()
            transcript.addLast(record)
            // apt is chatty — every "Get:12 http://…" is a record — and a
            // phone need not repaint for each. The same 10 Hz ceiling the
            // clone's progress uses.
            val now = System.nanoTime()
            // Never resurrect a state a cancel has already moved past.
            if (now - lastStep >= GuestProcess.PROGRESS_INTERVAL_NS &&
                generation == mine &&
                state is ServerInstallState.Installing
            ) {
                lastStep = now
                state = ServerInstallState.Installing(target, record.take(120))
            }
        }
        if (exit == 0) return ServerInstallState.Finished(target)
        val output = transcript.joinToString("\n")
        return ServerInstallState.Failed(
            target,
            LanguageServers.explainInstall(output, target),
            output.ifBlank { null },
        )
    }

    /**
     * Why there is nowhere to run apt. Reached in the `full` flavour before
     * Debian is installed; the `play` flavour never gets here, because the UI
     * leaves the action out when [isSupported] is false.
     */
    private fun noUserland(): String =
        if (Userland.backend.isSupported) {
            "${Userland.backend.displayName} is not installed yet — open the terminal to " +
                "install it, then try again"
        } else {
            "This edition has no Linux userland to install a language server into"
        }
}

/**
 * Which language a state is about, or null for [ServerInstallState.Idle] and
 * for a failure with nothing to retry.
 *
 * An extension rather than an interface member so the data classes keep their
 * own plain `target`: the prompt asks this one so that a state left over from
 * one language cannot answer for another.
 */
val ServerInstallState.targetOrNull: LanguageServerPackage?
    get() = when (this) {
        is ServerInstallState.Checking -> target
        is ServerInstallState.Offered -> target
        is ServerInstallState.Installing -> target
        is ServerInstallState.AlreadyInstalled -> target
        is ServerInstallState.Finished -> target
        is ServerInstallState.Failed -> target
        ServerInstallState.Idle -> null
    }
