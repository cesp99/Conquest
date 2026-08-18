package to.eyed.conquest.code.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-3's half of the agent panel: the Node runtime as an apt install, and the
 * agent definitions the panel launches.
 *
 * Nothing here runs apt or npm — these are the sentences and the argv, which
 * is exactly the part that has to be right before anything is run at all.
 */
class AgentRuntimeTest {

    // --- the runtime ---------------------------------------------------------

    /**
     * Both packages, named explicitly. `nodejs` alone is a runtime with no way
     * to install anything onto it, which is the pyflakes/cargo lesson again.
     */
    @Test
    fun installsNodeAndNpmTogether() {
        assertEquals(listOf("nodejs", "npm"), AgentRuntime.NODE.packages)
        assertEquals("nodejs and npm", AgentRuntime.NODE.packageList)
        assertEquals("are", AgentRuntime.NODE.packagesAre)
    }

    /** The price apt quoted goes in the question, as the clone dialog does it. */
    @Test
    fun asksWithAptsOwnPrice() {
        val plan = AptPlan(
            downloadBytes = 12_400_000,
            diskBytes = 44_000_000,
            newPackages = 2,
            missing = emptyList(),
            hasSummary = true,
        )
        assertEquals(
            "The agent panel needs Node — install nodejs and npm (~12.4 MB)?",
            AgentRuntime.NODE.question(plan),
        )
        assertTrue(AgentRuntime.NODE.detail(plan, "Debian").contains("44.0 MB"))
        assertTrue(AgentRuntime.NODE.detail(plan, "Debian").contains("Debian"))
    }

    /** No price rather than an invented one, when apt could not say. */
    @Test
    fun asksWithoutAPriceRatherThanInventingOne() {
        assertEquals(
            "The agent panel needs Node — install nodejs and npm?",
            AgentRuntime.NODE.question(null),
        )
        val blind = AptPlan(null, null, 0, listOf("nodejs"), hasSummary = false)
        assertTrue(
            AgentRuntime.NODE.detail(blind, "Debian")
                .contains("has not downloaded its package lists"),
        )
    }

    /**
     * Installing Node is not installing an agent, and the finished sentence has
     * to say so — otherwise the panel looks broken when it still has no agent
     * to talk to.
     */
    @Test
    fun sayingItIsInstalledPointsAtTheNextStep() {
        assertTrue(AgentRuntime.NODE.installedMessage().contains("npm in the terminal"))
        assertTrue(AgentRuntime.NODE.alreadyInstalledMessage().contains("already installed"))
    }

    /** The two ways a guest says "there is no such program". */
    @Test
    fun recognisesAMissingProgramFromWhatTheGuestSaid() {
        assertTrue(AgentRuntime.looksLikeMissingProgram("sh: 1: claude-code-acp: not found"))
        assertTrue(AgentRuntime.looksLikeMissingProgram("execvp: No such file or directory"))
        assertTrue(AgentRuntime.looksLikeMissingProgram("bash: node: command not found"))
        assertFalse(AgentRuntime.looksLikeMissingProgram("Error: not authenticated"))
        assertFalse(AgentRuntime.looksLikeMissingProgram(null))
    }

    // --- the agents ----------------------------------------------------------

    /**
     * The command lines, which are the thing a user cannot look up from inside
     * the app. Both are the ones Zed drives.
     */
    @Test
    fun knowsHowToLaunchTheAgentsItOffers() {
        assertEquals(listOf("claude-code-acp"), Agents.CLAUDE_CODE.argv)
        // Gemini speaks ACP only behind its flag (Zed strips exactly this one
        // for its terminal-auth path, agent_servers/src/acp.rs:1070).
        assertEquals(listOf("gemini", "--experimental-acp"), Agents.GEMINI_CLI.argv)
        assertEquals(Agents.CLAUDE_CODE, Agents.byId("claude-code"))
        assertNull(Agents.byId("nope"))
    }

    /**
     * The install command is *shown*, never run — so what matters is that it is
     * the command that actually works, global so the guest's PATH picks the
     * program up where the engine's spawn looks for it.
     */
    @Test
    fun namesTheInstallCommandWithoutRunningIt() {
        assertEquals(
            "npm install -g @zed-industries/claude-code-acp",
            Agents.CLAUDE_CODE.installCommand,
        )
        assertEquals("npm install -g @google/gemini-cli", Agents.GEMINI_CLI.installCommand)
        // An agent configured by hand need not come from npm at all.
        assertNull(
            AgentDefinition(id = "custom", name = "Mine", argv = listOf("my-agent"))
                .installCommand,
        )
    }

    /**
     * The spec crosses the JNI boundary as JSON and is parsed by serde on the
     * other side, so it has to be JSON — including when the name carries a
     * quote, which hand-built JSON is exactly where that breaks.
     */
    @Test
    fun theSpecIsRealJsonTheEngineCanParse() {
        val json = JSONObject(Agents.GEMINI_CLI.toSpecJson())
        assertEquals("Gemini CLI", json.getString("name"))
        assertEquals(2, json.getJSONArray("argv").length())
        assertEquals("gemini", json.getJSONArray("argv").getString(0))
        assertEquals("--experimental-acp", json.getJSONArray("argv").getString(1))
        assertNotNull(json.getJSONObject("env"))

        val awkward = AgentDefinition(
            id = "awkward",
            name = "He said \"hi\"",
            argv = listOf("a b", "c\"d"),
        )
        val parsed = JSONObject(awkward.toSpecJson())
        assertEquals("He said \"hi\"", parsed.getString("name"))
        assertEquals("a b", parsed.getJSONArray("argv").getString(0))
        assertEquals("c\"d", parsed.getJSONArray("argv").getString(1))
    }

    // --- the shared apt machinery, from the other caller ---------------------

    /**
     * The extraction P6-3 did: Node and a language server go through the same
     * [Apt], so the argv and the environment are the ones P5-2 proved on the
     * device rather than a second copy of them.
     */
    @Test
    fun nodeGoesThroughTheSameAptAsALanguageServer() {
        assertEquals(
            listOf(
                "apt-get", "install", "--assume-no", "--no-install-recommends", "--",
                "nodejs", "npm",
            ),
            Apt.estimateArgv(AgentRuntime.NODE.packages),
        )
        val install = Apt.installArgv(AgentRuntime.NODE.packages)
        assertEquals(listOf("/bin/sh", "-c"), install.take(2))
        assertTrue(install[2].startsWith("apt-get update && apt-get install -y"))
        assertTrue(install[2].endsWith("-- nodejs npm"))
        assertTrue(Apt.ENVIRONMENT.contains("DEBIAN_FRONTEND=noninteractive"))
    }

    /**
     * The shell guard, from this caller too: the install line reaches
     * `/bin/sh -c`, so a "package name" that is really a command must be
     * refused rather than run.
     */
    @Test
    fun refusesAPackageNameThatIsReallyACommand() {
        val bad = listOf("nodejs; rm -rf /")
        val thrown = runCatching { Apt.installArgv(bad) }.exceptionOrNull()
        assertNotNull("a package name with a semicolon must be refused", thrown)
    }
}
