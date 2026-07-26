package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    private val srtSample = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello
        World

        2
        00:00:03,000 --> 00:00:04,000
        Second
    """.trimIndent()

    // ==================== detectFormat ====================

    @Test
    fun detectFormat_srt() {
        assertEquals(SubtitleParser.SubtitleFormat.SRT, SubtitleParser.detectFormat(srtSample))
        // 没有序号行、只有时间轴的内容也应识别为 SRT
        assertEquals(
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.detectFormat("00:00:01,000 --> 00:00:02,000\nHello")
        )
    }

    @Test
    fun detectFormat_lrc() {
        assertEquals(SubtitleParser.SubtitleFormat.LRC, SubtitleParser.detectFormat("[00:01.00]hi"))
        assertEquals(
            SubtitleParser.SubtitleFormat.LRC,
            SubtitleParser.detectFormat("[ti:title]\n[00:01.00]hi")
        )
    }

    @Test
    fun detectFormat_txt() {
        assertEquals(SubtitleParser.SubtitleFormat.TXT, SubtitleParser.detectFormat("hello\nworld"))
        // 数字开头但没有时间轴标记的纯文本
        assertEquals(SubtitleParser.SubtitleFormat.TXT, SubtitleParser.detectFormat("5 dollars"))
    }

    @Test
    fun detectFormat_emptyIsUnknown() {
        assertEquals(SubtitleParser.SubtitleFormat.UNKNOWN, SubtitleParser.detectFormat(""))
        assertEquals(SubtitleParser.SubtitleFormat.UNKNOWN, SubtitleParser.detectFormat("   \n  "))
    }

    // ==================== parseSRT ====================

    @Test
    fun parseSRT_standardEntries() {
        val entries = SubtitleParser.parseSRT(srtSample)
        assertEquals(2, entries.size)

        assertEquals(1, entries[0].index)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(2500L, entries[0].endTime)
        assertEquals("Hello\nWorld", entries[0].text)

        assertEquals(2, entries[1].index)
        assertEquals(3000L, entries[1].startTime)
        assertEquals(4000L, entries[1].endTime)
        assertEquals("Second", entries[1].text)
    }

    @Test
    fun parseSRT_renumbersEntries() {
        val content = "5\n00:00:01,000 --> 00:00:02,000\nA\n\n9\n00:00:03,000 --> 00:00:04,000\nB"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(listOf(1, 2), entries.map { it.index })
    }

    @Test
    fun parseSRT_dropsEntriesWithoutText() {
        val content = "1\n00:00:01,000 --> 00:00:02,000\n\n2\n00:00:03,000 --> 00:00:04,000\nKept"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1, entries.size)
        assertEquals("Kept", entries[0].text)
        assertEquals(1, entries[0].index)
    }

    @Test
    fun parseSRT_acceptsDotMillis() {
        val content = "1\n00:00:01.000 --> 00:00:02.000\nText"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(2000L, entries[0].endTime)
    }

    @Test
    fun parseSRT_ignoresLeadingGarbage() {
        val content = "WEBVTT-like junk\n1\n00:00:01,000 --> 00:00:02,000\nText"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1, entries.size)
        assertEquals("Text", entries[0].text)
    }

    @Test
    fun parseSRT_lastEntryWithoutTrailingBlankLine() {
        val content = "1\n00:00:01,000 --> 00:00:02,000\nOnly"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1, entries.size)
        assertEquals("Only", entries[0].text)
    }

    @Test
    fun parseSRT_toleratesSpacesAroundArrow() {
        val content = "1\n00:00:01,000   -->   00:00:02,000\nText"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(2000L, entries[0].endTime)
    }

    // ==================== parseLRC ====================

    @Test
    fun parseLRC_endTimeClampedToNextStart() {
        val entries = SubtitleParser.parseLRC("[00:01.00]Hello\n[00:03.50]World")
        assertEquals(2, entries.size)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(3500L, entries[0].endTime)
        assertFalse(entries[0].endTimeModified)
        // 最后一行使用默认 6 秒时长
        assertEquals(3500L, entries[1].startTime)
        assertEquals(9500L, entries[1].endTime)
    }

    @Test
    fun parseLRC_blankTagActsAsTerminator() {
        val entries = SubtitleParser.parseLRC("[00:01.00]Hello\n[00:02.00]\n[00:10.00]World")
        assertEquals(2, entries.size)
        assertEquals(2000L, entries[0].endTime)
        assertTrue(entries[0].endTimeModified)
        assertEquals(10000L, entries[1].startTime)
    }

    @Test
    fun parseLRC_defaultSixSecondsWhenGapIsLarge() {
        val entries = SubtitleParser.parseLRC("[00:01.00]A\n[00:20.00]B")
        assertEquals(7000L, entries[0].endTime)
        assertFalse(entries[0].endTimeModified)
    }

    @Test
    fun parseLRC_skipsMetadataTags() {
        val entries = SubtitleParser.parseLRC("[ti:Title]\n[ar:Artist]\n[00:01.00]A")
        assertEquals(1, entries.size)
        assertEquals("A", entries[0].text)
    }

    @Test
    fun parseLRC_twoAndThreeDigitMillis() {
        assertEquals(1230L, SubtitleParser.parseLRC("[00:01.23]X")[0].startTime)
        assertEquals(1234L, SubtitleParser.parseLRC("[00:01.234]X")[0].startTime)
    }

    @Test
    fun parseLRC_trimsText() {
        assertEquals("spaced", SubtitleParser.parseLRC("[00:01.00]  spaced  ")[0].text)
    }

    @Test
    fun parseLRC_assignsSequentialIndices() {
        val entries = SubtitleParser.parseLRC("[00:01.00]A\n[00:02.00]B\n[00:03.00]C")
        assertEquals(listOf(1, 2, 3), entries.map { it.index })
    }

    // ==================== toSRT / toLRC / TXT ====================

    @Test
    fun toSRT_singleEntryLayout() {
        val content = SubtitleParser.toSRT(
            listOf(SubtitleEntry(index = 1, startTime = 1000, endTime = 2000, text = "Hello"))
        )
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHello\n\n", content)
    }

    @Test
    fun toSRT_renumbersFromOne() {
        val content = SubtitleParser.toSRT(
            listOf(SubtitleEntry(index = 99, startTime = 0, endTime = 1000, text = "A"))
        )
        assertTrue(content.startsWith("1\n"))
    }

    @Test
    fun toLRC_contiguousEntriesHaveNoInnerTerminator() {
        val content = SubtitleParser.toLRC(
            listOf(
                SubtitleEntry(startTime = 1000, endTime = 2000, text = "A"),
                SubtitleEntry(startTime = 2000, endTime = 3000, text = "B")
            )
        )
        assertEquals("[00:01.00]A\n[00:02.00]B\n[00:03.00]\n", content)
    }

    @Test
    fun toLRC_gapInsertsTerminator() {
        val content = SubtitleParser.toLRC(
            listOf(
                SubtitleEntry(startTime = 1000, endTime = 2000, text = "A"),
                SubtitleEntry(startTime = 5000, endTime = 6000, text = "B")
            )
        )
        assertEquals("[00:01.00]A\n[00:02.00]\n[00:05.00]B\n[00:06.00]\n", content)
    }

    @Test
    fun parseTXT_skipsBlankLinesAndTrims() {
        val entries = SubtitleParser.parseTXT("Hello\n\n  World  \n")
        assertEquals(2, entries.size)
        assertEquals("Hello", entries[0].text)
        assertEquals(0L, entries[0].startTime)
        assertEquals(3000L, entries[0].endTime)
        assertEquals("World", entries[1].text)
        assertEquals(3000L, entries[1].startTime)
        assertEquals(6000L, entries[1].endTime)
    }

    @Test
    fun toTXT_outputsOneLinePerEntry() {
        val content = SubtitleParser.toTXT(
            listOf(
                SubtitleEntry(text = "Hello"),
                SubtitleEntry(text = "World")
            )
        )
        assertEquals("Hello\nWorld\n", content)
    }

    // ==================== 往返一致性 ====================

    @Test
    fun srt_roundTrip_preservesTimesAndText() {
        val original = listOf(
            SubtitleEntry(index = 1, startTime = 1000, endTime = 2500, text = "Hello\nWorld"),
            SubtitleEntry(index = 2, startTime = 3000, endTime = 4000, text = "第二条")
        )
        val reparsed = SubtitleParser.parseSRT(SubtitleParser.toSRT(original))
        assertEquals(original.size, reparsed.size)
        original.zip(reparsed).forEach { (o, r) ->
            assertEquals(o.startTime, r.startTime)
            assertEquals(o.endTime, r.endTime)
            assertEquals(o.text, r.text)
        }
    }

    @Test
    fun lrc_roundTrip_preservesCentisecondAlignedTimes() {
        // LRC 精度是厘秒，用 10ms 对齐的时间验证
        val original = listOf(
            SubtitleEntry(startTime = 1230, endTime = 2560, text = "A"),
            SubtitleEntry(startTime = 5000, endTime = 8000, text = "B")
        )
        val reparsed = SubtitleParser.parseLRC(SubtitleParser.toLRC(original))
        assertEquals(2, reparsed.size)
        assertEquals(1230L, reparsed[0].startTime)
        assertEquals(2560L, reparsed[0].endTime)
        assertEquals(5000L, reparsed[1].startTime)
        assertEquals(8000L, reparsed[1].endTime)
    }

    @Test
    fun lrc_roundTrip_contiguousEntries() {
        val original = listOf(
            SubtitleEntry(startTime = 1000, endTime = 2000, text = "A"),
            SubtitleEntry(startTime = 2000, endTime = 3000, text = "B")
        )
        val reparsed = SubtitleParser.parseLRC(SubtitleParser.toLRC(original))
        assertEquals(2000L, reparsed[0].endTime)
        assertEquals(3000L, reparsed[1].endTime)
    }

    // ==================== parse 分派 / convertFormat ====================

    @Test
    fun parse_dispatchesByDetectedFormat() {
        assertEquals(2, SubtitleParser.parse(srtSample).size)
        assertEquals(1, SubtitleParser.parse("[00:01.00]hi").size)
        assertEquals(2, SubtitleParser.parse("line one\nline two").size)
        assertTrue(SubtitleParser.parse("").isEmpty())
    }

    @Test
    fun convertFormat_srtToLrc() {
        val lrc = SubtitleParser.convertFormat(
            srtSample,
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.SubtitleFormat.LRC
        )
        assertTrue(lrc.contains("[00:01.00]Hello"))
    }

    @Test
    fun convertFormat_lrcToSrt() {
        val srt = SubtitleParser.convertFormat(
            "[00:01.00]Hello\n[00:02.00]\n",
            SubtitleParser.SubtitleFormat.LRC,
            SubtitleParser.SubtitleFormat.SRT
        )
        assertTrue(srt.contains("00:00:01,000 --> 00:00:02,000"))
    }

    @Test
    fun convertFormat_unknownTargetReturnsOriginal() {
        val result = SubtitleParser.convertFormat(
            srtSample,
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.SubtitleFormat.UNKNOWN
        )
        assertEquals(srtSample, result)
    }
}
