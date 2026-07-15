package com.echidna.control.service

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncBridgeTest {
    @Test
    fun `only exact process-bound v3 zygisk hello negotiates acknowledged handoff`() {
        val hello = "${PROFILE_SYNC_V3_ZYGISK_PREFIX}com.example.recorder:worker\n"
        assertEquals(
            ProfileSyncClientRole.ZYGISK,
            ProfileSyncWire.parseHello(hello).role,
        )
        assertEquals("com.example.recorder:worker", ProfileSyncWire.parseHello(hello).processName)
        assertTrue(ProfileSyncWire.parseHello(hello).acknowledgedHandoff)
        assertEquals(
            ProfileSyncClientRole.LEGACY,
            ProfileSyncWire.parseHello(PROFILE_SYNC_V2_ZYGISK_HELLO).role,
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
        val hello = "${PROFILE_SYNC_V3_ZYGISK_PREFIX}com.example.recorder\n"
        val helloBytes = hello.toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            hello,
            ProfileSyncWire.readHello(ByteArrayInputStream(helloBytes)),
        )

        val payload = "{\"value\":\"Olá 🎙️\"}"
        val frame = ProfileSyncWire.encodeFrame(payload)!!
        val header = ByteBuffer.wrap(frame, 0, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int
        val encoded = payload.toByteArray(StandardCharsets.UTF_8)
        assertEquals(encoded.size, header)
        assertArrayEquals(encoded, frame.copyOfRange(Int.SIZE_BYTES, frame.size))

        val policy = capturePolicyPayload(7L)
        val captureFrame = ProfileSyncWire.encodeCapturePolicyFrame(policy, 11L)!!
        val captureSize = ByteBuffer.wrap(captureFrame, 0, Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        val wrapped = String(
            captureFrame,
            Int.SIZE_BYTES,
            captureSize,
            StandardCharsets.UTF_8,
        )
        val root = org.json.JSONObject(wrapped)
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("capture_policy", root.getString("type"))
        assertEquals(11L, root.getLong("handoffToken"))
        assertEquals(7L, root.getJSONObject("policy").getLong("generation"))
        assertTrue(wrapped.endsWith("\"policy\":$policy}"))
        assertNull(ProfileSyncWire.encodeCapturePolicyFrame(policy, 0L))
        assertNull(ProfileSyncWire.encodeCapturePolicyFrame("{}", 11L))
    }

    @Test
    fun `capture owner ack requires exact strict process generation and state`() {
        val valid = CaptureOwnerAckWire.parse(
            """{"schemaVersion":1,"type":"capture_owner_ack","process":"com.example.recorder","generation":7,"handoffToken":11,"active":false}""",
        )!!
        assertEquals("com.example.recorder", valid.processName)
        assertEquals(7L, valid.generation)
        assertEquals(11L, valid.handoffToken)
        assertFalse(valid.active)
        assertNull(CaptureOwnerAckWire.parse(
            """{"schemaVersion":1,"type":"capture_owner_ack","process":"com.example.recorder","generation":0,"handoffToken":11,"active":false}""",
        ))
        assertNull(CaptureOwnerAckWire.parse(
            """{"schemaVersion":1,"type":"capture_owner_ack","process":"other","generation":7,"handoffToken":11,"active":false,"extra":1}""",
        ))
        assertNull(CaptureOwnerAckWire.parse(
            """{"schemaVersion":1,"type":"capture_owner_ack","process":"com.example.recorder","generation":7,"handoffToken":0,"active":false}""",
        ))
        assertNull(CaptureOwnerAckWire.parse(
            """{"schemaVersion":1,"type":"capture_owner_ack","process":"com.example.recorder","generation":7,"generation":8,"handoffToken":11,"active":false}""",
        ))
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

    @Test
    fun `telemetry saturation does not consume acknowledgement allowance`() {
        val telemetry = PeerTelemetryRateLimiter(windowMs = 2_000L, maxFrames = 8)
        val acknowledgements = PeerTelemetryRateLimiter(windowMs = 2_000L, maxFrames = 32)

        repeat(8) { assertTrue(telemetry.allow(1_000L)) }
        assertFalse(telemetry.allow(1_000L))
        assertTrue(acknowledgements.allow(1_000L))
    }

    private fun capturePolicyPayload(generation: Long): String =
        """{"schemaVersion":2,"generation":$generation,"profiles":{"default":{"engine":{},"modules":[]}},"defaultProfileId":"default","appBindings":{},"whitelist":{"com.example.recorder":true},"captureOwners":{"com.example.recorder":"zygisk"},"control":{"masterEnabled":true,"bypass":false,"panicUntilEpochMs":0,"sidetoneEnabled":false,"sidetoneGainDb":0.0,"engineMode":"native_first"}}"""
}
