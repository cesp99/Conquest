package to.eyed.conquest.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The graph's little pure pieces: the two date formats, which decoration gets
 * the HEAD chip, the initials disc, and the commit tab's title. Each is a
 * Zed behaviour with a citation, so each is pinned rather than eyeballed.
 */
class GitGraphLabelsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** `05 Mar 2026 14:07` — day zero-padded, month short (git_graph.rs:605-624). */
    @Test
    fun theRowDateIsZedsTableFormat() {
        // 2026-03-05 14:07:00 UTC.
        assertEquals("05 Mar 2026 14:07", graphRowDate(1772719620L, utc))
    }

    /** A timestamp the parser could not read says so (git_graph.rs:620-624). */
    @Test
    fun anUnreadableTimestampSaysUnknown() {
        assertEquals("Unknown", graphRowDate(0L, utc))
        assertEquals("Unknown", sidebarDate(0L, utc))
    }

    /** `Mar 5, 2026` — absolute, not relative (git_graph.rs:2743-2754). */
    @Test
    fun theSidebarDateIsTheAbsoluteShortForm() {
        assertEquals("Mar 5, 2026", sidebarDate(1772719620L, utc))
    }

    /** Zed's `is_head_ref` (git_graph.rs:1698-1701), plus the detached spelling. */
    @Test
    fun theHeadChipIsTheCurrentBranchsDecoration() {
        assertTrue(isHeadDecoration("HEAD -> main", "main"))
        assertTrue(isHeadDecoration("main", "main"))
        assertTrue(isHeadDecoration("HEAD", null))
        assertFalse(isHeadDecoration("origin/main", "main"))
        assertFalse(isHeadDecoration("HEAD -> feature", "main"))
        assertFalse(isHeadDecoration("main", null))
        assertFalse(isHeadDecoration("tag: v1.0", "main"))
    }

    @Test
    fun initialsAreTheFirstTwoWords() {
        assertEquals("CE", authorInitials("Carlo Esposito"))
        assertEquals("C", authorInitials("Carlo"))
        assertEquals("AB", authorInitials("alice bob carol"))
        assertEquals("?", authorInitials(""))
        assertEquals("?", authorInitials("   "))
    }

    /**
     * `"{7-char sha} — {subject truncated to 20 chars}"`, the truncation
     * adding an ellipsis (commit_view.rs:1073-1077).
     */
    @Test
    fun theCommitTabTitleIsShaDashSubject() {
        assertEquals(
            "abc1234 — Fix the thing",
            commitTabTitle("abc1234def5678", "Fix the thing"),
        )
        assertEquals(
            "abc1234 — Fix the thing, and t…",
            commitTabTitle("abc1234def5678", "Fix the thing, and the other thing"),
        )
        // A short sha is used whole, as Zed's `get(0..7)` fallback does.
        assertEquals("abc — s", commitTabTitle("abc", "s"))
    }
}
