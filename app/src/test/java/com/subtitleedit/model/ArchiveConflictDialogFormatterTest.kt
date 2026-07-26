package com.subtitleedit.model

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 格式化结果依赖默认 Locale 与时区，测试内固定为 US/UTC 以保证跨机器一致。 */
class ArchiveConflictDialogFormatterTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinLocaleAndTimeZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    // ==================== size ====================

    @Test
    fun size_nullOrNegative_isUnknown() {
        assertEquals("未知", ArchiveConflictDialogFormatter.size(null))
        assertEquals("未知", ArchiveConflictDialogFormatter.size(-1L))
    }

    @Test
    fun size_belowOneKilobyte_usesBytes() {
        assertEquals("0 B", ArchiveConflictDialogFormatter.size(0L))
        assertEquals("1 B", ArchiveConflictDialogFormatter.size(1L))
        assertEquals("1023 B", ArchiveConflictDialogFormatter.size(1023L))
    }

    @Test
    fun size_scalesThroughUnits() {
        assertEquals("1KB", ArchiveConflictDialogFormatter.size(1024L))
        assertEquals("1.5KB", ArchiveConflictDialogFormatter.size(1536L))
        assertEquals("1MB", ArchiveConflictDialogFormatter.size(1024L * 1024))
        assertEquals("1GB", ArchiveConflictDialogFormatter.size(1024L * 1024 * 1024))
        assertEquals("1TB", ArchiveConflictDialogFormatter.size(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun size_roundsToTwoDecimals() {
        assertEquals("1.75KB", ArchiveConflictDialogFormatter.size(1792L))
        // 1000000 / 1024 = 976.5625 → "0.##" 保留两位
        assertEquals("976.56KB", ArchiveConflictDialogFormatter.size(1_000_000L))
    }

    @Test
    fun size_beyondTerabyte_staysInTerabytes() {
        assertEquals("1024TB", ArchiveConflictDialogFormatter.size(1024L * 1024 * 1024 * 1024 * 1024))
    }

    // ==================== modifiedTime ====================

    @Test
    fun modifiedTime_nullOrNonPositive_isUnknown() {
        assertEquals("未知", ArchiveConflictDialogFormatter.modifiedTime(null))
        assertEquals("未知", ArchiveConflictDialogFormatter.modifiedTime(0L))
        assertEquals("未知", ArchiveConflictDialogFormatter.modifiedTime(-1L))
    }

    @Test
    fun modifiedTime_formatsWithoutLeadingZeros() {
        assertEquals("2020/9/13 12:26", ArchiveConflictDialogFormatter.modifiedTime(1_600_000_000_000L))
    }

    @Test
    fun modifiedTime_keepsTwoDigitHourAndMinute() {
        // 1970/1/1 00:00:01 UTC
        assertEquals("1970/1/1 00:00", ArchiveConflictDialogFormatter.modifiedTime(1_000L))
    }

    // ==================== 数据类默认值 ====================

    @Test
    fun metadata_defaultsAreNull() {
        val metadata = ArchiveConflictFileMetadata()
        assertEquals(null, metadata.sizeBytes)
        assertEquals(null, metadata.modifiedAtMillis)
        assertEquals("未知", ArchiveConflictDialogFormatter.size(metadata.sizeBytes))
        assertEquals("未知", ArchiveConflictDialogFormatter.modifiedTime(metadata.modifiedAtMillis))
    }

    @Test
    fun dialogModel_keepsSourceAndExistingSeparate() {
        val model = ArchiveConflictDialogModel(
            entryName = "docs/a.txt",
            source = ArchiveConflictFileMetadata(sizeBytes = 2048L, modifiedAtMillis = 1_600_000_000_000L),
            existing = ArchiveConflictFileMetadata(sizeBytes = 1024L)
        )
        assertEquals("docs/a.txt", model.entryName)
        assertEquals("2KB", ArchiveConflictDialogFormatter.size(model.source.sizeBytes))
        assertEquals("1KB", ArchiveConflictDialogFormatter.size(model.existing.sizeBytes))
        assertEquals("未知", ArchiveConflictDialogFormatter.modifiedTime(model.existing.modifiedAtMillis))
    }
}
