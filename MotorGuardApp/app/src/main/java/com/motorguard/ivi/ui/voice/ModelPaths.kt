// ModelPaths — owner D
// Where the STT/TTS models actually live.
package com.motorguard.ivi.ui.voice

import android.util.Log
import java.io.File

/**
 * Resolves a model file or directory, preferring the system image over app
 * storage.
 *
 * Why both: the models total ~170 MB and belong in the image, where they survive
 * a factory reset and cost nothing in /data. But during development it is much
 * faster to push a new model with adb than to rebuild and reflash, so app
 * storage wins when a file is present there.
 *
 * Search order, first hit wins:
 *   1. /data/user/<u>/com.motorguard.ivi/files/   -- pushed, for iteration
 *   2. /system_ext/etc/motorguard/                -- shipped in the image
 *   3. /system/etc/motorguard/                    -- fallback partition layout
 *
 * Putting the writable location first means a pushed file overrides the shipped
 * one, which is the behaviour you want while tuning: push, test, and delete the
 * pushed copy to fall back.
 */
object ModelPaths {

    private const val TAG = "MotorGuardVoice"

    private val SYSTEM_DIRS = listOf(
        "/system_ext/etc/motorguard",
        "/system/etc/motorguard",
    )

    /** @return the first readable file with this name, or null. */
    fun file(filesDir: File, name: String): File? = resolve(filesDir, name) { it.isFile }

    /** @return the first readable directory with this name, or null. */
    fun dir(filesDir: File, name: String): File? = resolve(filesDir, name) { it.isDirectory }

    private fun resolve(filesDir: File, name: String, ok: (File) -> Boolean): File? {
        val candidates = ArrayList<File>(SYSTEM_DIRS.size + 1)
        candidates.add(File(filesDir, name))
        SYSTEM_DIRS.forEach { candidates.add(File(it, name)) }

        for (c in candidates) {
            if (runCatching { ok(c) && c.canRead() }.getOrDefault(false)) {
                Log.i(TAG, "using $name from ${c.parent}")
                return c
            }
        }
        Log.e(TAG, "$name not found in ${candidates.joinToString { it.parent ?: "?" }}")
        return null
    }
}