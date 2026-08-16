package to.eyed.conquest.code.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Delete a directory tree without ever following a symbolic link out of it.
 *
 * This exists because `File.deleteRecursively()` does follow them: it decides
 * what to descend into with `File.isDirectory()`, which resolves links. A
 * Linux rootfs is *full* of symlinks — Debian's `/bin` is one — and a user can
 * trivially make one that points at their projects:
 *
 * ```
 * ln -s /data/data/…/files/projects /root/p
 * ```
 *
 * Removing the userland would then delete their work. The links inside a
 * distribution are harmless because they point back into it, but "harmless in
 * the cases we thought of" is not a property worth betting a user's source
 * code on.
 *
 * `Files.walkFileTree` does not follow links unless asked, so a link is
 * visited as an entry and unlinked, never descended.
 */
object SafeDelete {

    /**
     * Delete [root] and everything beneath it. Returns false if anything
     * survived — callers that care (an installer clearing a half-finished
     * rootfs) should treat that as failure rather than press on.
     */
    fun deleteTree(root: File): Boolean {
        if (!root.exists() && !isSymlink(root)) return true
        return try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    /** Reached for an unreadable directory; delete what we can. */
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            !root.exists()
        } catch (e: IOException) {
            false
        }
    }

    private fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())
}
