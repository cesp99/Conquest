package to.eyed.conquest.code.ui.git

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Commit author avatars, fetched from GitHub's email→avatar CDN — the lookup
 * Zed's GitHub provider does per author (github.rs:75-82), done once per
 * author here and remembered.
 *
 * The caller gates on the remote: a repository whose origin is not github.com
 * never asks (Zed's `host_supports_avatars`, github.rs:178-182), and the
 * sidebar draws its initials disc instead.
 *
 * Two caches, deliberately layered:
 *  - **disk** — the app's cache directory, keyed by the normalized author
 *    identity ([avatarCacheKey]), so one author is one fetch across commits
 *    *and* sessions, and the OS may evict it whenever it likes;
 *  - **memory** — a small LRU of decoded bitmaps on top, so scrolling between
 *    commits by the same few people never touches the filesystem again.
 *
 * A failed fetch is remembered for the session only — offline is not forever,
 * but it is for the next few minutes — and never written to disk, so a
 * rate-limited answer cannot become a permanently blank avatar.
 *
 * A module-level object, like [to.eyed.conquest.code.terminal.GitClone]: the
 * cache outlives any one pane.
 */
object CommitAvatars {
    private val memory = LruCache<String, Bitmap>(32)
    private val missing = HashSet<String>()

    /**
     * The avatar for [email], or null when there is none to be had — no
     * email, a bot, offline, or GitHub does not know the address.
     *
     * **Blocking** — it may use the network. Call it from
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun load(context: Context, email: String): Bitmap? {
        val url = githubAvatarUrl(email) ?: return null
        val key = avatarCacheKey(email)
        synchronized(this) {
            memory.get(key)?.let { return it }
            if (key in missing) return null
        }
        val file = File(File(context.cacheDir, "git-avatars"), key)
        val bitmap = decode(file) ?: fetch(url, file)
        synchronized(this) {
            if (bitmap != null) memory.put(key, bitmap) else missing.add(key)
        }
        return bitmap
    }

    private fun decode(file: File): Bitmap? =
        if (file.isFile) BitmapFactory.decodeFile(file.path) else null

    /** Download to [file], then decode — the file *is* the disk cache. */
    private fun fetch(url: String, file: File): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val bytes = connection.inputStream.use { it.readBytes() }
                file.parentFile?.mkdirs()
                // Write-then-rename, so a fetch killed halfway never leaves a
                // truncated file that decodes to garbage on every next launch.
                val partial = File(file.path + ".part")
                partial.writeBytes(bytes)
                if (!partial.renameTo(file)) partial.delete()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
