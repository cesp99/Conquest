package to.eyed.conquest.code.core

/**
 * Node in the guest, and the agents that run on it.
 *
 * **Node is not bundled.** The plan that said to build `libnode_exec.so` was
 * written before the Debian userland existed and is dead (DECISIONS.md,
 * 2026-08-18): Debian stable packages what we need in `main` — checked on the
 * device, `nodejs 20.19.2+dfsg-1+deb13u2` and `npm 9.2.0~ds1-3` — so Node
 * installs exactly the way clangd and pylsp do, through [Apt]. The `play`
 * flavour has no userland and therefore no agent panel at all: absent, not
 * failing, like every other guest feature.
 *
 * **Nothing here installs an agent.** The runtime is ours to offer because it
 * is one apt package pair with a price apt itself quotes; an agent is the
 * user's own choice of software, often tied to their own account, so the panel
 * *names the command* and leaves running it to them, in the terminal they
 * already have. [AgentDefinition.installCommand] is that sentence, not an
 * action.
 */
object AgentRuntime {

    /**
     * `nodejs` and `npm`, the pair every npx-style agent needs.
     *
     * Both are named explicitly, and the reason is the rule the language-server
     * table exists for: `nodejs` alone gives you a runtime and no way to
     * install anything onto it, so an agent published on npm would be
     * uninstallable on a box that looks like it has Node. Same lesson as
     * pyflakes and cargo.
     */
    val NODE = AgentRuntimePackage(
        packages = listOf("nodejs", "npm"),
    )

    /**
     * Whether an agent that would not start looks like a missing runtime
     * rather than a missing agent.
     *
     * The engine reports the agent's own last line of stderr, and a guest
     * that cannot find the program says so in one of two ways depending on
     * whether the shell or the loader got there first. Used to pick which of
     * the two sentences the panel shows — never to install anything on its
     * own.
     */
    fun looksLikeMissingProgram(error: String?): Boolean {
        val text = error?.lowercase() ?: return false
        return "command not found" in text ||
            "not found" in text ||
            "no such file or directory" in text
    }
}

/** The runtime, as something [AptInstaller] can install. */
data class AgentRuntimePackage(
    override val packages: List<String>,
) : AptTarget {

    override fun question(plan: AptPlan?): String {
        val size = Apt.formatBytes(plan?.downloadBytes)
        val cost = if (size != null) " (~$size)" else ""
        return "The agent panel needs Node — install $packageList$cost?"
    }

    override fun detail(plan: AptPlan?, userland: String): String {
        val parts = mutableListOf(
            "Agents are published on npm and run on Node, so both are installed.",
        )
        val disk = Apt.formatBytes(plan?.diskBytes)
        if (disk != null) parts += "About $disk of the userland's storage will be used."
        if (plan != null && plan.missing.isNotEmpty()) {
            parts += "apt has not downloaded its package lists yet, so it cannot " +
                "say what this will cost until it has."
        }
        parts += "Node runs inside $userland, and the agent you choose runs on it."
        return parts.joinToString(" ")
    }

    override fun installedMessage(): String =
        "$packageList installed. Install an agent with npm in the terminal, then " +
            "open the agent panel again."

    override fun alreadyInstalledMessage(): String =
        "$packageList $packagesAre already installed. If the agent still will not " +
            "start, running its command in the terminal will say why."
}

/**
 * Installing Node from apt. A separate [AptInstaller] from the language
 * servers' so the two cannot overwrite each other's state — they still share
 * apt's own lock inside the guest, which apt reports and [Apt.explain] turns
 * into a sentence.
 */
val AgentRuntimeInstaller = AptInstaller("conquest-agent-runtime")

/**
 * An agent the panel knows how to launch.
 *
 * [argv] is the guest command line, program included — the agent has to be on
 * the guest's PATH, which `npm install -g` is what puts it there. This is the
 * same shape the engine's `AgentSpec` takes, and it is deliberately data: a
 * custom agent is a settings entry, not a code change.
 */
data class AgentDefinition(
    /** Stable id, used as the settings key and in the session list. */
    val id: String,
    /** What to call it on screen. */
    val name: String,
    /** The guest argv. */
    val argv: List<String>,
    /** The npm package that provides [argv]'s program, when there is one. */
    val npmPackage: String? = null,
) {
    /**
     * What the user would type to install it — shown, never run.
     *
     * `-g` so it lands on the guest's PATH where the engine's `spawn` will
     * find it, which is the same reason Zed resolves npx agents from a global
     * install (project/src/agent_server_store.rs:1389-1408).
     */
    val installCommand: String?
        get() = npmPackage?.let { "npm install -g $it" }

    /** The engine's `AgentSpec`, as JSON. */
    fun toSpecJson(): String {
        val argvJson = argv.joinToString(",") { org.json.JSONObject.quote(it) }
        return """{"name":${org.json.JSONObject.quote(name)},"argv":[$argvJson],"env":{}}"""
    }
}

/**
 * The agents the panel offers out of the box.
 *
 * Both are ACP agents Zed itself drives, named here only so their command
 * lines are not something the user has to look up. Neither is installed by
 * this app: [AgentDefinition.installCommand] is a sentence to copy into the
 * terminal.
 */
object Agents {
    val CLAUDE_CODE = AgentDefinition(
        id = "claude-code",
        name = "Claude Code",
        // The adapter's own bin name; it speaks ACP on stdio with no flags.
        argv = listOf("claude-code-acp"),
        npmPackage = "@zed-industries/claude-code-acp",
    )

    val GEMINI_CLI = AgentDefinition(
        id = "gemini",
        name = "Gemini CLI",
        // Gemini speaks ACP behind a flag; Zed passes the same one and strips
        // it again only for its terminal-auth path (agent_servers/src/acp.rs:1070).
        argv = listOf("gemini", "--experimental-acp"),
        npmPackage = "@google/gemini-cli",
    )

    val ALL: List<AgentDefinition> = listOf(CLAUDE_CODE, GEMINI_CLI)

    fun byId(id: String?): AgentDefinition? = ALL.firstOrNull { it.id == id }
}
