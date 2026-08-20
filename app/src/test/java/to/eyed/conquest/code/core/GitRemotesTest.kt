package to.eyed.conquest.code.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge's remote JSON and these parsers have to agree, and nothing at
 * build time makes them: the contract is a string. These are the shapes
 * `remote_result` and `gitRemotes` actually serialize — nulls present, not
 * absent.
 */
class GitRemotesTest {

    @Test
    fun readsARemoteListing() {
        val list = GitRemoteList.parse(
            """
            {"remotes":[
               {"name":"origin","url":"git@github.com:cesp99/conquest.git"},
               {"name":"fork","url":"https://example.com/someone/fork.git"}],
             "error":null}
            """
        )
        assertNull(list.error)
        assertEquals(2, list.remotes.size)
        assertEquals("origin", list.remotes[0].name)
        // Both spellings of a github.com remote count — SSH's colon and
        // HTTPS's slash.
        assertTrue(list.remotes[0].isGithub)
        assertFalse(list.remotes[1].isGithub)
    }

    @Test
    fun aFailedListingIsAnErrorAndNoRemotes() {
        val list = GitRemoteList.parse("""{"error":"Not a git repository"}""")
        assertTrue(list.remotes.isEmpty())
        assertEquals("Not a git repository", list.error)
    }

    @Test
    fun readsARemoteCommandResult() {
        val ok = RemoteOpResult.parse(
            """{"remote":"origin","stdout":"Already up to date.\n","stderr":"","error":null}"""
        )
        assertTrue(ok.ok)
        assertEquals("origin", ok.remote)
        assertEquals("Already up to date.\n", ok.stdout)

        // fetch --all runs against no single remote; the streams still come
        // back beside the failure for the log view.
        val failed = RemoteOpResult.parse(
            """{"remote":null,"stdout":"","stderr":"fatal: no path\n","error":"no path"}"""
        )
        assertFalse(failed.ok)
        assertNull(failed.remote)
        assertEquals("no path", failed.error)
        assertEquals("fatal: no path\n", failed.stderr)

        // The guest itself failing has no streams at all.
        val guest = RemoteOpResult.parse("""{"error":"Could not run git in the Linux userland"}""")
        assertFalse(guest.ok)
        assertEquals("", guest.stdout)
    }

    /** The branch record grew `upstream_gone` — the Republish fact. */
    @Test
    fun readsAGoneUpstream() {
        val gone = GitBranch.parse(
            JSONObject(
                """{"name":"feature","ahead":0,"behind":0,"unborn":false,
                    "upstream":"origin/feature","upstream_gone":true}"""
            )
        )
        assertTrue(gone.hasUpstream)
        assertTrue(gone.upstreamGone)

        // And its absence parses as false, not as a crash — older JSON.
        val tracked = GitBranch.parse(
            JSONObject("""{"name":"main","ahead":1,"behind":0,"unborn":false,"upstream":"origin/main"}""")
        )
        assertFalse(tracked.upstreamGone)
    }
}
