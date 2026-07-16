package com.echidna.app.system

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Locates and stages the flashable Echidna Magisk module archive that the guided installer hands
 * to the privileged control service (`magisk --install-module <path>`).
 *
 * Two honest sources are supported:
 *   1. A bundled asset ([ASSET_NAME]) that a release build stages into the APK via the existing
 *      `tools/build_magisk_module.sh` tooling. When present it is extracted to the app's cache so
 *      the root-side installer has a real filesystem path.
 *   2. A user-picked `.zip` from the system document picker, for builds that do not ship a bundled
 *      package (the native per-ABI build is heavy and not always available). The chosen content is
 *      copied to the same cache path.
 *
 * The staged file lives in the app's private cache. The control service runs the install command
 * as root, so it can read that path; it is also marked world-readable defensively.
 */
class EngineModuleArchive(private val context: Context) {

    /**
     * Extracts the bundled module archive to a real path, or returns null when no archive is
     * bundled in this build. Never fabricates a package.
     */
    fun bundledArchivePath(): String? {
        val bundled = runCatching {
            context.assets.list("")?.contains(ASSET_NAME) == true
        }.getOrDefault(false)
        if (!bundled) return null
        val destination = stagedFile()
        return runCatching {
            context.assets.open(ASSET_NAME).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            markReadable(destination)
            destination.absolutePath
        }.getOrElse { error ->
            Log.w(TAG, "Failed to stage bundled module archive", error)
            null
        }
    }

    /**
     * Copies a user-selected archive [uri] into the private cache and returns its path, or null on
     * failure (e.g. the content could not be read).
     */
    fun stageArchive(uri: Uri): String? {
        val destination = stagedFile()
        return runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                destination.outputStream().use { output -> source.copyTo(output) }
            }
            markReadable(destination)
            destination.absolutePath
        }.getOrElse { error ->
            Log.w(TAG, "Failed to stage selected module archive", error)
            null
        }
    }

    private fun stagedFile(): File = File(context.cacheDir, STAGED_NAME)

    private fun markReadable(file: File) {
        runCatching { file.setReadable(true, false) }
    }

    companion object {
        private const val TAG = "EngineModuleArchive"

        /** Asset name a release build stages via tools/build_magisk_module.sh. */
        const val ASSET_NAME = "echidna-magisk.zip"

        /** Cache file name the staged archive is written to. */
        const val STAGED_NAME = "echidna-magisk.zip"
    }
}
