package com.echidna.app.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-testable parts of [RealtimeMonitor]: the start gate, the run/stop lifecycle, and the latency
 * estimate the Lab shows the user.
 *
 * The monitor's audio loop itself needs a real `AudioRecord`/`AudioTrack` and the native DSP engine,
 * so it is exercised on-device rather than here. What IS testable off-device is the part that
 * decides whether the loop runs at all — and that gate is safety-relevant, because starting the
 * monitor opens a speaker-to-mic feedback path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RealtimeMonitorTest {

    @Test
    fun `an unavailable processor refuses to start and leaves the monitor idle`() {
        val monitor = RealtimeMonitor()

        val started = monitor.start(FakeProcessor(available = false), { _, _ -> }) { }

        assertFalse("an engine-less monitor must not claim to have started", started)
        assertFalse(
            "refusing to start must not leave the running flag latched, which would block a retry",
            monitor.isRunning,
        )
    }

    @Test
    fun `a refused start can be retried once the engine becomes available`() {
        val monitor = RealtimeMonitor(sampleRate = 1, blockFrames = 256)
        assertFalse(monitor.start(FakeProcessor(available = false), { _, _ -> }) { })

        try {
            assertTrue(
                "a refused start must not consume the monitor",
                monitor.start(FakeProcessor(available = true), { _, _ -> }) { },
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `stop clears the running flag and is safe to call when never started`() {
        val monitor = RealtimeMonitor(sampleRate = 1, blockFrames = 256)

        // Never started: stop must not throw or block on a thread that does not exist.
        monitor.stop()
        assertFalse(monitor.isRunning)

        monitor.start(FakeProcessor(available = true), { _, _ -> }) { }
        monitor.stop()

        assertFalse("stop must leave the monitor restartable", monitor.isRunning)
    }

    @Test
    fun `an unsupported capture format is reported through onError instead of failing silently`() {
        // A format the device cannot open makes getMinBufferSize report an error; the monitor must
        // say so rather than sit there "running" with a dead audio path.
        val monitor = RealtimeMonitor(sampleRate = 1, blockFrames = 256)
        val errors = CopyOnWriteArrayList<String>()

        assertTrue(monitor.start(FakeProcessor(available = true), { _, _ -> }, errors::add))

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (errors.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5L)
        }
        monitor.stop()

        assertEquals(
            listOf("Realtime audio not available on this device"),
            errors.toList(),
        )
        assertFalse("the monitor must not report running after the audio path failed", monitor.isRunning)
    }

    @Test
    fun `the reported latency estimate matches capture plus one block plus output`() {
        // Three block periods at the configured rate; the Lab shows this to justify its warning.
        assertEquals(16, RealtimeMonitor(sampleRate = 48_000, blockFrames = 256).estimatedLatencyMs())
        assertEquals(32, RealtimeMonitor(sampleRate = 48_000, blockFrames = 512).estimatedLatencyMs())
        assertEquals(
            "halving the sample rate doubles the buffer time",
            32,
            RealtimeMonitor(sampleRate = 24_000, blockFrames = 256).estimatedLatencyMs(),
        )
    }

    @Test
    fun `the default output gain is below unity as the second feedback-safety layer`() {
        val monitor = RealtimeMonitor()

        assertTrue(
            "an at-or-above-unity default would make the headphone warning the only protection",
            monitor.outputGain < 1f,
        )
        assertTrue(monitor.outputGain > 0f)
    }

    private class FakeProcessor(override val available: Boolean) : DspProcessor {
        override fun process(input: FloatArray, output: FloatArray, frames: Int): Boolean {
            input.copyInto(output, 0, 0, frames)
            return true
        }

        override fun close() = Unit
    }
}
