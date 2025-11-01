package com.echidna.control.service

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.ErrnoException
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.max

private const val SYNC_TAG = "EchidnaProfileSync"
private const val DEFAULT_SHARED_MEMORY_CAPACITY = 64 * 1024
private const val SOCKET_PATH = "/data/local/tmp/echidna_profiles.sock"

/**
 * Pushes profile updates to the native Zygisk module via shared memory or sockets.
 */
class ProfileSyncBridge {
    private val socketChannel = UnixSocketProfileChannel(SOCKET_PATH)
    private val sharedMemoryChannel: SharedMemoryProfileChannel? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            SharedMemoryProfileChannel(
                "echidna_profiles",
                DEFAULT_SHARED_MEMORY_CAPACITY,
                socketChannel,
            )
        } else {
            null
        }

    fun pushProfiles(json: String) {
        var delivered = false
        val memoryChannel = sharedMemoryChannel
        if (memoryChannel != null) {
            delivered = memoryChannel.write(json)
        }
        if (!delivered) {
            delivered = socketChannel.write(json)
        }
        if (!delivered) {
            Log.w(SYNC_TAG, "No profile sync channel accepted the payload")
        }
    }
}

private class SharedMemoryProfileChannel(
    private val name: String,
    private val defaultCapacity: Int,
    private val signalChannel: UnixSocketProfileChannel,
) {
    @Volatile
    private var sharedMemory: SharedMemory? = null

    fun write(json: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return false
        }
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val required = bytes.size + Int.SIZE_BYTES
        return try {
            ensureCapacity(required)
            val memory = sharedMemory ?: return false
            val buffer = memory.mapReadWrite()
            try {
                buffer.order(ByteOrder.BIG_ENDIAN)
                buffer.putInt(bytes.size)
                buffer.put(bytes)
                while (buffer.hasRemaining()) {
                    buffer.put(0)
                }
            } finally {
                SharedMemory.unmap(buffer)
            }
            signalChannel.write(json, memory)
        } catch (err: ErrnoException) {
            Log.w(SYNC_TAG, "SharedMemory write failed", err)
            false
        } catch (io: IOException) {
            Log.w(SYNC_TAG, "SharedMemory write failed", io)
            false
        }
    }

    @Throws(ErrnoException::class)
    private fun ensureCapacity(required: Int) {
        val memory = sharedMemory
        if (memory != null && memory.size() >= required) {
            return
        }
        memory?.close()
        sharedMemory = SharedMemory.create(name, max(required, defaultCapacity))
    }
}

private class UnixSocketProfileChannel(private val socketPath: String) {
    fun write(json: String, sharedMemory: SharedMemory? = null): Boolean {
        return try {
            LocalSocketHelper(socketPath).use { socket ->
                socket.write(json, sharedMemory)
            }
            true
        } catch (e: IOException) {
            Log.v(SYNC_TAG, "Socket push skipped: ${e.message}")
            false
        }
    }
}

private class LocalSocketHelper(path: String) : AutoCloseable {
    private val socket = android.net.LocalSocket()

    init {
        val address = android.net.LocalSocketAddress(
            path,
            android.net.LocalSocketAddress.Namespace.FILESYSTEM,
        )
        socket.connect(address)
    }

    fun write(payload: String, sharedMemory: SharedMemory? = null) {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        header.putInt(bytes.size)
        val duplicated = sharedMemory?.let { memory ->
            ParcelFileDescriptor.dup(memory.fileDescriptor)
        }
        try {
            duplicated?.let { pfd ->
                socket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
            }
            val output = socket.outputStream
            output.write(header.array())
            output.write(bytes)
            output.flush()
        } finally {
            duplicated?.close()
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (ignored: IOException) {
            // Nothing to do.
        }
    }
}
