package com.subtitleedit.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** WAV 字节在测试内手工拼装，覆盖头解析、声道混合与范围读取的边界。 */
class Pcm16WavReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var fileCounter = 0

    // ==================== 头解析 ====================

    @Test
    fun header_exposesFormatAndSampleCount() {
        val reader = open(wav(sampleRate = 16000, channels = 1, samples = shortsOf(1, 2, 3, 4)))
        reader.use {
            assertEquals(16000, it.sampleRate)
            assertEquals(1, it.channels)
            assertEquals(4L, it.totalSamples)
        }
    }

    @Test
    fun header_stereoTotalSamplesCountsFrames() {
        val reader = open(wav(channels = 2, samples = shortsOf(1, 2, 3, 4, 5, 6)))
        reader.use { assertEquals(3L, it.totalSamples) }
    }

    @Test
    fun header_skipsUnknownChunks() {
        val reader = open(
            wav(
                samples = shortsOf(100, 200),
                extraChunks = listOf("LIST" to "INFOxx".toByteArray())
            )
        )
        reader.use { assertEquals(2L, it.totalSamples) }
    }

    @Test
    fun header_skipsOddSizedChunkWithPadding() {
        // 奇数长度的块后面有 1 字节填充，解析时必须跳过，否则 data 块会错位。
        val reader = open(
            wav(
                samples = shortsOf(100, 200),
                extraChunks = listOf("LIST" to byteArrayOf(1, 2, 3))
            )
        )
        reader.use {
            assertEquals(2L, it.totalSamples)
            assertArrayEquals(floatArrayOf(100 / 32768f, 200 / 32768f), it.readRange(0, 2), 1e-7f)
        }
    }

    @Test
    fun header_notRiff_throws() {
        val bytes = wav(samples = shortsOf(1)).copyOf()
        bytes[0] = 'X'.code.toByte()
        assertFailsWith(bytes, "不是有效的 RIFF WAV 文件")
    }

    @Test
    fun header_notWave_throws() {
        val bytes = wav(samples = shortsOf(1)).copyOf()
        bytes[8] = 'X'.code.toByte()
        assertFailsWith(bytes, "不是有效的 WAVE 文件")
    }

    @Test
    fun header_missingDataChunk_throws() {
        assertFailsWith(wav(samples = shortsOf(1), includeData = false), "WAV 文件缺少 fmt 或 data 块")
    }

    @Test
    fun header_nonPcmFormat_throws() {
        assertFailsWith(wav(samples = shortsOf(1), formatCode = 3), "仅支持 16-bit PCM WAV")
    }

    @Test
    fun header_non16Bit_throws() {
        assertFailsWith(wav(samples = shortsOf(1), bitsPerSample = 24), "仅支持 16-bit PCM WAV")
    }

    @Test
    fun header_zeroChannels_throws() {
        assertFailsWith(wav(samples = ByteArray(0), channels = 0), "WAV 格式信息无效")
    }

    @Test
    fun header_zeroSampleRate_throws() {
        assertFailsWith(wav(samples = shortsOf(1), sampleRate = 0), "WAV 格式信息无效")
    }

    // ==================== 采样转换 ====================

    @Test
    fun readRange_convertsSignedSamplesToFloat() {
        val reader = open(wav(samples = shortsOf(0, 16384, -16384, 32767, -32768)))
        reader.use {
            assertArrayEquals(
                floatArrayOf(0f, 0.5f, -0.5f, 32767 / 32768f, -1f),
                it.readRange(0, 5),
                1e-7f
            )
        }
    }

    @Test
    fun readRange_stereoIsDownmixedToMono() {
        // 左右声道分别为 32767 与 -32767，混合后应接近 0。
        val reader = open(wav(channels = 2, samples = shortsOf(32767, -32767, 16384, 16384)))
        reader.use {
            val samples = it.readRange(0, 2)
            assertEquals(2, samples.size)
            assertEquals(0f, samples[0], 1e-4f)
            assertEquals(0.5f, samples[1], 1e-7f)
        }
    }

    // ==================== readRange 边界 ====================

    @Test
    fun readRange_offsetIsHonoured() {
        val reader = open(wav(samples = shortsOf(0, 16384, -16384)))
        reader.use {
            assertArrayEquals(floatArrayOf(-0.5f), it.readRange(2, 1), 1e-7f)
        }
    }

    @Test
    fun readRange_countBeyondEnd_isClamped() {
        val reader = open(wav(samples = shortsOf(0, 16384, -16384)))
        reader.use { assertEquals(2, it.readRange(1, 100).size) }
    }

    @Test
    fun readRange_startBeyondEnd_returnsEmpty() {
        val reader = open(wav(samples = shortsOf(0, 16384)))
        reader.use { assertEquals(0, it.readRange(2, 4).size) }
    }

    @Test
    fun readRange_negativeStart_isCoercedToZero() {
        val reader = open(wav(samples = shortsOf(16384, -16384)))
        reader.use {
            assertArrayEquals(floatArrayOf(0.5f, -0.5f), it.readRange(-5, 2), 1e-7f)
        }
    }

    @Test
    fun readRange_nonPositiveCount_returnsEmpty() {
        val reader = open(wav(samples = shortsOf(1, 2)))
        reader.use {
            assertEquals(0, it.readRange(0, 0).size)
            assertEquals(0, it.readRange(0, -3).size)
        }
    }

    // ==================== forEachChunk ====================

    @Test
    fun forEachChunk_splitsWithCorrectOffsets() {
        val reader = open(wav(samples = shortsOf(0, 16384, -16384, 32767, 0)))
        val offsets = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()
        reader.use {
            it.forEachChunk(chunkSamples = 2) { chunk, startSample ->
                offsets += startSample
                sizes += chunk.size
            }
        }
        assertEquals(listOf(0L, 2L, 4L), offsets)
        assertEquals(listOf(2, 2, 1), sizes)
    }

    @Test
    fun forEachChunk_deliversAllSamplesInOrder() {
        val reader = open(wav(samples = shortsOf(0, 16384, -16384, 32767)))
        val collected = mutableListOf<Float>()
        reader.use {
            it.forEachChunk(chunkSamples = 3) { chunk, _ -> collected += chunk.toList() }
        }
        assertArrayEquals(
            floatArrayOf(0f, 0.5f, -0.5f, 32767 / 32768f),
            collected.toFloatArray(),
            1e-7f
        )
    }

    @Test
    fun forEachChunk_emptyData_neverInvokesCallback() {
        val reader = open(wav(samples = ByteArray(0)))
        var invoked = false
        reader.use { it.forEachChunk(chunkSamples = 4) { _, _ -> invoked = true } }
        assertEquals(false, invoked)
    }

    @Test
    fun forEachChunk_nonPositiveChunkSize_throws() {
        val reader = open(wav(samples = shortsOf(1, 2)))
        reader.use {
            val failure = runCatching { it.forEachChunk(chunkSamples = 0) { _, _ -> } }.exceptionOrNull()
            assertTrue("应当抛出 IllegalArgumentException，实际为 $failure", failure is IllegalArgumentException)
        }
    }

    // ==================== 辅助 ====================

    private fun open(bytes: ByteArray): Pcm16WavReader = Pcm16WavReader(writeTemp(bytes))

    private fun writeTemp(bytes: ByteArray): File =
        temporaryFolder.newFile("sample-${fileCounter++}.wav").apply { writeBytes(bytes) }

    private fun assertFailsWith(bytes: ByteArray, messagePart: String) {
        val failure = runCatching { Pcm16WavReader(writeTemp(bytes)).close() }.exceptionOrNull()
        assertNotNull("应当抛出 IOException：$messagePart", failure)
        assertTrue("异常类型不符：$failure", failure is IOException)
        assertTrue(
            "异常信息不符，期望包含「$messagePart」，实际为「${failure!!.message}」",
            failure.message.orEmpty().contains(messagePart)
        )
    }

    private fun shortsOf(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun wav(
        samples: ByteArray,
        sampleRate: Int = 8000,
        channels: Int = 1,
        formatCode: Int = 1,
        bitsPerSample: Int = 16,
        includeData: Boolean = true,
        extraChunks: List<Pair<String, ByteArray>> = emptyList()
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeAscii("WAVE")

        val fmt = ByteArrayOutputStream().apply {
            writeUInt16(formatCode)
            writeUInt16(channels)
            writeUInt32(sampleRate.toLong())
            writeUInt32(sampleRate.toLong() * channels * bitsPerSample / 8)
            writeUInt16(channels * bitsPerSample / 8)
            writeUInt16(bitsPerSample)
        }.toByteArray()
        body.writeChunk("fmt ", fmt)
        extraChunks.forEach { (id, data) -> body.writeChunk(id, data) }
        if (includeData) body.writeChunk("data", samples)

        val payload = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.writeAscii("RIFF")
        out.writeUInt32(payload.size.toLong())
        out.write(payload)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value shr 8) and 0xFF).toInt())
        write(((value shr 16) and 0xFF).toInt())
        write(((value shr 24) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeChunk(id: String, data: ByteArray) {
        writeAscii(id)
        writeUInt32(data.size.toLong())
        write(data)
        if (data.size % 2 == 1) write(0)
    }
}
