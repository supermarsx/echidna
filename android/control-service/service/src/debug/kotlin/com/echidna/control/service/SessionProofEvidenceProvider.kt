package com.echidna.control.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** DUMP-protected, debug-variant-only telemetry checkpoint for disposable device tests. */
class SessionProofEvidenceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_SNAPSHOT -> {
                val appContext = requireNotNull(context).applicationContext
                val json = TelemetryExporter(
                    appContext.filesDir,
                    AuthenticatedTelemetryRegistry.store,
                ).snapshotJson()
                Bundle().apply { putString(KEY_JSON, json) }
            }

            METHOD_HANDOFF -> {
                val processName = requireNotNull(arg).takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("process name required")
                val coordinator = CaptureOwnerHandoffRegistry.get()
                Bundle().apply {
                    putLong(KEY_GENERATION, PublishedPolicyRegistry.generation())
                    putString(KEY_PHASE, coordinator.phase(processName).name)
                    putString(KEY_OWNER, coordinator.effectiveOwner(processName).name)
                }
            }

            else -> throw IllegalArgumentException("unsupported method")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val METHOD_SNAPSHOT = "snapshot"
        const val METHOD_HANDOFF = "handoff"
        const val KEY_JSON = "json"
        const val KEY_GENERATION = "generation"
        const val KEY_PHASE = "phase"
        const val KEY_OWNER = "owner"
    }
}
