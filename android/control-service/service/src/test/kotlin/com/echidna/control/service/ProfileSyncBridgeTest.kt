package com.echidna.control.service

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncBridgeTest {
    @Test
    fun `only exact newline-terminated role tokens negotiate v2`() {
        assertEquals(
            ProfileSyncClientRole.ZYGISK,
            ProfileSyncWire.classifyHello(PROFILE_SYNC_V2_ZYGISK_HELLO),
        )
        assertEquals(
            ProfileSyncClientRole.LSPOSED,
            ProfileSyncWire.classifyHello(PROFILE_SYNC_V2_LSPOSED_HELLO),
        )
        assertEquals(ProfileSyncClientRole.LEGACY, ProfileSyncWire.classifyHello(null))
        assertEquals(
            ProfileSyncClientRole.LEGACY,
            ProfileSyncWire.classifyHello(PROFILE_SYNC_V2_ZYGISK_HELLO + "extra"),
        )
    }

    @Test
    fun `hello reader and frame encoder preserve exact bytes`() {
        val helloBytes = PROFILE_SYNC_V2_ZYGISK_HELLO.toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            PROFILE_SYNC_V2_ZYGISK_HELLO,
            ProfileSyncWire.readHello(ByteArrayInputStream(helloBytes)),
        )

        val payload = "{\"value\":\"Olá 🎙️\"}"
        val frame = ProfileSyncWire.encodeFrame(payload)!!
        val header = ByteBuffer.wrap(frame, 0, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int
        val encoded = payload.toByteArray(StandardCharsets.UTF_8)
        assertEquals(encoded.size, header)
        assertArrayEquals(encoded, frame.copyOfRange(Int.SIZE_BYTES, frame.size))
    }

    @Test
    fun `framing rejects malformed UTF-16 and oversize payloads`() {
        assertNull(ProfileSyncWire.encodeFrame("\uD800"))
        assertNull(ProfileSyncWire.encodeFrame("x".repeat(MAX_POLICY_ENVELOPE_BYTES + 1)))
        assertTrue(ProfileSyncWire.encodeFrame("x".repeat(MAX_POLICY_ENVELOPE_BYTES)) != null)
    }

    @Test
    fun `slow-writer mailbox coalesces to newest whole frame`() {
        val mailbox = LatestFrameMailbox()
        assertTrue(mailbox.offer(byteArrayOf(1, 2, 3)))
        assertTrue(mailbox.offer(byteArrayOf(4, 5, 6, 7)))

        assertArrayEquals(byteArrayOf(4, 5, 6, 7), mailbox.take())
        mailbox.close()
        assertNull(mailbox.take())
    }
}
