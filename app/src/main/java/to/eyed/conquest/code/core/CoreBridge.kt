package to.eyed.conquest.code.core

/**
 * Kotlin side of the JNI boundary to the Rust engine (`core/crates/jni-bridge`).
 *
 * Naming contract: each `external` function here maps to a
 * `Java_to_eyed_conquest_code_core_CoreBridge_<name>` symbol in the Rust
 * crate. Keep the two files in sync — this is the only place the two worlds
 * meet.
 *
 * Calls across this boundary must stay coarse-grained: never loop over
 * per-character calls from Kotlin.
 */
object CoreBridge {
    init {
        System.loadLibrary("conquestcore")
    }

    external fun engineVersion(): String

    /** Returns the id of the newly created buffer. */
    external fun createBuffer(initialText: String): Long

    external fun closeBuffer(bufferId: Long): Boolean

    /**
     * Replaces the byte range [start, end) with [text]. Offsets are UTF-8
     * byte offsets and must lie on character boundaries. Returns false on
     * invalid buffer id or range.
     */
    external fun editBuffer(bufferId: Long, start: Long, end: Long, text: String): Boolean

    /** Returns null if the buffer does not exist. */
    external fun bufferText(bufferId: Long): String?
}
