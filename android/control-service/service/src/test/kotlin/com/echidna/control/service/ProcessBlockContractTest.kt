package com.echidna.control.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessBlockContractTest {
    @Test
    fun `zero dimensions are rejected before native processing`() {
        var nativeCalls = 0
        val processor = { _: FloatArray, _: FloatArray?, _: Int, _: Int, _: Int ->
            nativeCalls += 1
            PROCESS_RESULT_OK
        }

        assertEquals(
            PROCESS_RESULT_INVALID_ARGUMENT,
            processBlockValidated(FloatArray(2), FloatArray(2), 0, 48_000, 2, processor),
        )
        assertEquals(
            PROCESS_RESULT_INVALID_ARGUMENT,
            processBlockValidated(FloatArray(2), FloatArray(2), 1, 0, 2, processor),
        )
        assertEquals(
            PROCESS_RESULT_INVALID_ARGUMENT,
            processBlockValidated(FloatArray(2), FloatArray(2), 1, 48_000, 0, processor),
        )
        assertEquals(0, nativeCalls)
    }

    @Test
    fun `undersized input and output are rejected before native processing`() {
        var nativeCalls = 0
        val processor = { _: FloatArray, _: FloatArray?, _: Int, _: Int, _: Int ->
            nativeCalls += 1
            PROCESS_RESULT_OK
        }

        assertEquals(
            PROCESS_RESULT_INVALID_ARGUMENT,
            processBlockValidated(FloatArray(3), FloatArray(4), 2, 48_000, 2, processor),
        )
        assertEquals(
            PROCESS_RESULT_INVALID_ARGUMENT,
            processBlockValidated(FloatArray(4), FloatArray(3), 2, 48_000, 2, processor),
        )
        assertEquals(0, nativeCalls)
    }

    @Test
    fun `overflowing sample count is rejected before native processing`() {
        var nativeCalls = 0
        val result = processBlockValidated(
            FloatArray(1),
            FloatArray(1),
            Int.MAX_VALUE,
            48_000,
            2,
        ) { _, _, _, _, _ ->
            nativeCalls += 1
            PROCESS_RESULT_OK
        }

        assertEquals(PROCESS_RESULT_INVALID_ARGUMENT, result)
        assertEquals(0, nativeCalls)
    }

    @Test
    fun `native failure leaves caller output unchanged`() {
        val output = floatArrayOf(9f, 9f, 9f, 9f)
        val result = processBlockValidated(
            floatArrayOf(1f, 2f, 3f, 4f),
            output,
            2,
            48_000,
            2,
        ) { _, transactionalOutput, _, _, _ ->
            transactionalOutput!!.fill(42f)
            -7
        }

        assertEquals(-7, result)
        assertArrayEquals(floatArrayOf(9f, 9f, 9f, 9f), output, 0f)
    }

    @Test
    fun `native success commits the complete requested output`() {
        val output = floatArrayOf(9f, 9f, 9f, 9f, 99f)
        val result = processBlockValidated(
            floatArrayOf(1f, 2f, 3f, 4f),
            output,
            2,
            48_000,
            2,
        ) { _, transactionalOutput, _, _, _ ->
            transactionalOutput!!.indices.forEach { transactionalOutput[it] = (it + 1).toFloat() }
            PROCESS_RESULT_OK
        }

        assertEquals(PROCESS_RESULT_OK, result)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 99f), output, 0f)
    }
}
