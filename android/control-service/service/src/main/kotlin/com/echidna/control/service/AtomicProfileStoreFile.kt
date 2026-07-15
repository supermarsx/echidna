package com.echidna.control.service

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

internal fun writeProfileStoreAtomic(
    file: File,
    payload: String,
    beforeCommit: (() -> Unit)? = null,
) {
    val atomicFile = AtomicFile(file)
    val output = atomicFile.startWrite()
    try {
        output.write(payload.toByteArray(StandardCharsets.UTF_8))
        output.fd.sync()
        beforeCommit?.invoke()
        atomicFile.finishWrite(output)
    } catch (throwable: Throwable) {
        atomicFile.failWrite(output)
        if (throwable is IOException) throw throwable
        throw IOException("Unable to atomically persist profile state", throwable)
    }
}

internal fun readProfileStoreAtomic(file: File): String =
    AtomicFile(file).openRead().use { input ->
        input.readBytes().toString(StandardCharsets.UTF_8)
    }
