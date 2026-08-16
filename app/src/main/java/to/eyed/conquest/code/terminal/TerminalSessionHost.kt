package to.eyed.conquest.code.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * One shell session, and the bridge between the vendored terminal and Compose.
 *
 * The vendored `TerminalSession` reports everything through a callback
 * interface on the main thread; this turns the parts the UI cares about into
 * Compose state and forwards screen updates to the attached view.
 *
 * **Must be constructed on the main thread.** `TerminalSession`'s constructor
 * binds a `Handler` to the calling thread's looper — found the hard way in
 * P4-1 — so building one on a background thread throws.
 */
class TerminalSessionHost(
    private val context: Context,
    /** Working directory: the project root, so a terminal opens where the code is. */
    val cwd: String,
    /** Stable label for the session chip, e.g. "shell 1". */
    val label: String,
) : TerminalSessionClient {

    /** The title the shell set with an OSC sequence, if any. */
    var shellTitle by mutableStateOf<String?>(null)
        private set

    /** Non-null once the process has exited: >= 0 exit code, < 0 negated signal. */
    var exitStatus by mutableStateOf<Int?>(null)
        private set

    var session: TerminalSession by mutableStateOf(startSession())
        private set

    /** The view currently showing this session, if it is the visible one. */
    private var view: TerminalView? = null

    private fun startSession(): TerminalSession {
        // Either a shell inside the Linux userland or the host's own — see
        // ShellEnvironment.commandFor.
        val command = ShellEnvironment.commandFor(context, cwd)
        return TerminalSession(
            command.executable,
            cwd,
            command.argv.toTypedArray(),
            command.environment.toTypedArray(),
            TRANSCRIPT_ROWS,
            this,
        )
    }

    fun attach(view: TerminalView) {
        this.view = view
        view.attachSession(session)
    }

    fun detach(view: TerminalView) {
        if (this.view === view) this.view = null
    }

    /** Run the shell again in the same directory, reusing this session slot. */
    fun restart() {
        session.finishIfRunning()
        exitStatus = null
        shellTitle = null
        session = startSession()
        view?.attachSession(session)
    }

    fun finish() {
        session.finishIfRunning()
    }

    /** Type text into the shell, as the paste action and the extra keys do. */
    fun write(text: String) {
        if (exitStatus == null) session.write(text)
    }

    // --- TerminalSessionClient -------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === session) view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (changedSession === session) shellTitle = changedSession.title
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession === session) exitStatus = finishedSession.exitStatus
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        clipboard()?.setPrimaryClip(ClipData.newPlainText("", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = clipboard()?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) write(text)
    }

    override fun onBell(session: TerminalSession) {
        // A visual or haptic bell is a preference we don't have a home for
        // yet; staying silent beats buzzing without a way to turn it off.
    }

    override fun onColorsChanged(session: TerminalSession) {
        if (session === this.session) view?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    /** Null means "the emulator's default block cursor". */
    override fun getTerminalCursorStyle(): Int? = null

    private fun clipboard(): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message.orEmpty())
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message.orEmpty())
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: TAG, message.orEmpty())
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: TAG, message.orEmpty())
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: TAG, message.orEmpty())
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message.orEmpty(), e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "terminal error", e)
    }

    private companion object {
        const val TAG = "conquest-term"

        /**
         * Scrollback. Build output is the reason a terminal in an IDE needs
         * more than a shell prompt's worth; each row costs roughly its width
         * in chars, so this is a couple of MB at most.
         */
        const val TRANSCRIPT_ROWS = 4000
    }
}
