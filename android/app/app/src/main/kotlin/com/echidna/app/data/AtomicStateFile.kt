package com.echidna.app.data

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun writeAtomicUtf8(
    file: File,
    payload: String,
    beforeCommit: ((FileOutputStream) -> Unit)? = null,
) {
    val atomicFile = AtomicFile(file)
    var output: FileOutputStream? = null
    try {
        output = atomicFile.startWrite()
        output.write(payload.toByteArray(StandardCharsets.UTF_8))
        beforeCommit?.invoke(output)
        atomicFile.finishWrite(output)
    } catch (exception: Exception) {
        output?.let(atomicFile::failWrite)
        throw exception
    }
}

internal fun readAtomicUtf8(file: File): String =
    AtomicFile(file).readFully().toString(StandardCharsets.UTF_8)

/**
 * Coalescing single-file writer. Concurrent submitters receive a monotonic version and only the
 * highest pending version can become the final file contents.
 */
internal class LatestAtomicTextFileWriter(
    private val file: File,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "echidna-state-${file.name}").apply { isDaemon = true }
    },
) {
    private val nextVersion = AtomicLong(0L)
    private val pending = AtomicReference<VersionedPayload?>(null)
    private val workerScheduled = AtomicBoolean(false)

    fun submit(payload: String): Long {
        val version = nextVersion.incrementAndGet()
        val update = VersionedPayload(version, payload)
        while (true) {
            if (version < nextVersion.get()) break
            val current = pending.get()
            if (current != null && current.version > version) break
            if (pending.compareAndSet(current, update)) break
        }
        scheduleWorker()
        return version
    }

    fun awaitIdle(timeout: Long = 5L, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (!workerScheduled.get() && pending.get() == null) return true
            Thread.sleep(5L)
        }
        return !workerScheduled.get() && pending.get() == null
    }

    private fun scheduleWorker() {
        if (!workerScheduled.compareAndSet(false, true)) return
        executor.execute(::drain)
    }

    private fun drain() {
        try {
            while (true) {
                val update = pending.getAndSet(null) ?: break
                try {
                    writeAtomicUtf8(file, update.payload)
                } catch (exception: Exception) {
                    android.util.Log.e("EchidnaStateFile", "Failed to persist ${file.name}", exception)
                }
            }
        } finally {
            workerScheduled.set(false)
            if (pending.get() != null) scheduleWorker()
        }
    }

    private data class VersionedPayload(val version: Long, val payload: String)
}
