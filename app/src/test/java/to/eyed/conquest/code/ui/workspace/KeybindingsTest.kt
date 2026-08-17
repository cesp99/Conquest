package to.eyed.conquest.code.ui.workspace

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The one rule about this table that a compiler cannot check.
 *
 * `agent-docs/CONVENTIONS.md`: *"Editor-local chords (selection, undo,
 * clipboard, motion) stay in EditorPane's handleEditorKey — and the workspace
 * table must never match those, or it will swallow them."* The workspace's
 * preview pass runs before both the editor and the project panel, so a chord
 * here that a surface below uses for the clipboard does not lose an argument,
 * it wins one — silently, with the paste simply not happening.
 *
 * Only the constants are read, so no Android key event is constructed and this
 * runs on the host.
 */
class KeybindingsTest {

    /** Ctrl+A/C/V/X/Z, with or without Shift, belong to whatever has focus. */
    private val clipboardAndSelection = setOf(
        KeyEvent.KEYCODE_A,
        KeyEvent.KEYCODE_C,
        KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_X,
        KeyEvent.KEYCODE_Z,
    )

    @Test
    fun `no workspace surface takes a chord the editor or the panel needs`() {
        val surfaces = mapOf(
            "Preview" to PreviewChord,
            "go to line" to GoToLineChord,
            "project search" to ProjectSearchChord,
            "command palette" to CommandPaletteChord,
        )
        for ((name, chord) in surfaces) {
            assertFalse(
                "$name is on ${chord.label}, which is an editor-local chord",
                chord.keyCode in clipboardAndSelection,
            )
        }
    }
}
