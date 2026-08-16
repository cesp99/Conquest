package to.eyed.conquest.code.core

import android.content.Context
import java.io.File

/**
 * Where projects live on device, for now.
 *
 * App-private storage (`filesDir/projects`) is the fast path: no permissions,
 * no SAF round-trips, and the engine can watch it. Importing from elsewhere on
 * the device, and cloning into it, are the storage work of P3-4 — until then
 * this is the only project root, seeded once so a fresh install has something
 * real to open.
 */
object ProjectsRoot {
    private const val SAMPLE_NAME = "welcome"

    fun directory(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    /**
     * The project to open at startup: the sample, created on first launch.
     * Returns its absolute path.
     */
    fun defaultProject(context: Context): String {
        val project = File(directory(context), SAMPLE_NAME)
        if (!project.exists()) {
            writeSampleProject(project)
        }
        return project.absolutePath
    }

    private fun writeSampleProject(project: File) {
        File(project, "src").mkdirs()
        File(project, "Cargo.toml").writeText(
            """
            [package]
            name = "welcome"
            version = "0.1.0"
            edition = "2024"
            """.trimIndent() + "\n"
        )
        File(project, ".gitignore").writeText("target\n")
        File(project, "README.md").writeText(
            """
            # Welcome to Conquest Code

            An IDE for Android, built on Zed's Rust engine.

            This project lives in the app's private storage. The tree on the
            left is a real Zed worktree scanned inside the engine: gitignore
            aware, incremental, and lazy — directories are read only when you
            open them.
            """.trimIndent() + "\n"
        )
        File(project, "src/main.rs").writeText(sampleSource())
    }

    /** Welcome text plus generated lines — a scroll workout for the renderer. */
    private fun sampleSource(): String = buildString {
        append(
            """
            // Welcome to Conquest Code.
            //
            // This buffer lives inside the Rust engine (core/crates/engine):
            // Zed's rope/CRDT text stack, reached over JNI. The editor draws
            // only the visible line window, and the colors come from Zed's
            // tree-sitter highlight queries running inside the engine.
            //
            // The lines that follow are generated so you can put the
            // virtualized renderer through its paces. Fling away.

            const GREETING: &str = "Hello from the Rust core!";

            """.trimIndent() + "\n"
        )
        for (i in 1..5_000) {
            append("fn generated_$i() -> i32 { $i * ${i % 7} }  // scroll test, line ${i + 12}\n")
        }
    }
}
