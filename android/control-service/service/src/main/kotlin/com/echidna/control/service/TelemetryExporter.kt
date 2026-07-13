package com.echidna.control.service

import java.io.File

private const val OPT_IN_FILENAME = "telemetry_opt_in"

internal class TelemetryExporter(private val filesDir: File) {
    private val reader = TelemetryReader()
    private val optInFile = File(filesDir, OPT_IN_FILENAME)

    fun isOptedIn(): Boolean {
        return optInFile.exists() && optInFile.readText().trim() == "1"
    }

    fun setOptIn(enabled: Boolean) {
        if (enabled) {
            optInFile.writeText("1")
        } else {
            if (optInFile.exists()) {
                optInFile.delete()
            }
        }
    }

    fun snapshotJson(): String {
        val snapshot = reader.snapshot()
        return snapshot?.toJson(includeSamples = true) ?: "{}"
    }

    fun exportAnonymized(includeTrends: Boolean): String {
        if (!isOptedIn()) {
            return "{}"
        }
        val snapshot = reader.snapshot() ?: return "{}"
        return if (includeTrends) snapshot.toJson(includeSamples = false) else snapshot.anonymizedJson()
    }

    fun latestSnapshot(): TelemetrySnapshot? = reader.snapshot()
}
