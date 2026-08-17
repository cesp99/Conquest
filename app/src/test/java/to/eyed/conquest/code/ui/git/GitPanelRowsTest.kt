package to.eyed.conquest.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.conquest.code.core.GitChange
import to.eyed.conquest.code.core.GitFileStatus
import to.eyed.conquest.code.core.GitPanelState

/**
 * The flattening the panel's list is drawn from. It is worth its own test for
 * one reason above the rest: a file can legitimately appear twice, and two rows
 * with the same `LazyColumn` key is a crash rather than a cosmetic fault.
 */
class GitPanelRowsTest {

    private fun change(
        path: String,
        staged: GitFileStatus? = null,
        unstaged: GitFileStatus? = null,
        conflicted: Boolean = false,
        inHead: Boolean = true,
    ) = GitChange(path, staged, unstaged, conflicted, inHead)

    private fun state(vararg changes: GitChange) =
        GitPanelState(scanned = true, hasRepo = true, entries = changes.toList())

    @Test
    fun sectionsComeInZedsOrderAndEmptyOnesAreAbsent() {
        val rows = gitPanelRows(
            state(
                change("staged.rs", staged = GitFileStatus.Modified),
                change("changed.rs", unstaged = GitFileStatus.Modified),
                change("conflict.rs", conflicted = true),
            )
        )
        assertEquals(
            listOf(
                "section:Conflicts",
                "Conflicts:conflict.rs",
                "section:Staged",
                "Staged:staged.rs",
                "section:Changes",
                "Changes:changed.rs",
            ),
            rows.map { it.key },
        )

        // Nothing conflicted: no conflicts header.
        val quiet = gitPanelRows(state(change("a.rs", unstaged = GitFileStatus.Modified)))
        assertEquals(listOf("section:Changes", "Changes:a.rs"), quiet.map { it.key })
    }

    @Test
    fun aFileInTwoSectionsGetsTwoDistinctKeys() {
        val rows = gitPanelRows(
            state(
                change("a.rs", staged = GitFileStatus.Modified, unstaged = GitFileStatus.Modified)
            )
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("Staged:a.rs" in keys)
        assertTrue("Changes:a.rs" in keys)
    }

    @Test
    fun aSectionCarriesEveryPathInItForItsStageAllAction() {
        val rows = gitPanelRows(
            state(
                change("a.rs", unstaged = GitFileStatus.Modified),
                change("b.rs", unstaged = GitFileStatus.Untracked),
                change("c.rs", staged = GitFileStatus.Added),
            )
        )
        val changes = rows.filterIsInstance<GitPanelRow.SectionRow>()
            .single { it.section == GitSection.Changes }
        assertEquals(listOf("a.rs", "b.rs"), changes.paths)

        val staged = rows.filterIsInstance<GitPanelRow.SectionRow>()
            .single { it.section == GitSection.Staged }
        assertEquals(listOf("c.rs"), staged.paths)
    }

    @Test
    fun aCleanProjectHasNoRows() {
        assertTrue(gitPanelRows(state()).isEmpty())
        assertTrue(gitPanelRows(GitPanelState()).isEmpty())
    }
}
