package to.eyed.conquest.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The footer row's and empty state's little rules, checked against Zed's:
 * "View Branch Diff" hides only on the main branch (git_panel.rs:7049-7059),
 * the Uncommit meta words the flag by whether anything is unstaged
 * (git_panel.rs:6218-6231), and the pushed-commit confirmation names every
 * remote in one comma-joined sentence (git_panel.rs:3216-3228).
 */
class GitFooterTest {

    @Test
    fun branchDiffHidesOnlyOnTheMainBranch() {
        assertFalse(showsViewBranchDiff("main"))
        assertFalse(showsViewBranchDiff("master"))
        assertTrue(showsViewBranchDiff("feature/footer"))
        // Zed matches the exact lowercase names, nothing looser.
        assertTrue(showsViewBranchDiff("Main"))
        assertTrue(showsViewBranchDiff("main-2"))
        // A detached HEAD is on no branch, which is not the main one.
        assertTrue(showsViewBranchDiff(null))
    }

    @Test
    fun uncommitMetaWordsTheSoftFlagOnlyWithUnstagedChanges() {
        assertEquals("git reset HEAD^ --soft", uncommitMeta(hasUnstaged = true))
        assertEquals("git reset HEAD^", uncommitMeta(hasUnstaged = false))
    }

    @Test
    fun pushedDetailNamesEveryRemote() {
        assertEquals(
            "This commit was already pushed to origin/main.",
            uncommitPushedDetail(listOf("origin/main")),
        )
        assertEquals(
            "This commit was already pushed to origin/main, fork/main.",
            uncommitPushedDetail(listOf("origin/main", "fork/main")),
        )
    }
}
