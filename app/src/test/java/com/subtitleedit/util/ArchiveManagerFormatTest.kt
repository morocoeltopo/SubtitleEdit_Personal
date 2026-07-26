package com.subtitleedit.util

import com.subtitleedit.util.ArchiveManager.CompressionMethod
import com.subtitleedit.util.ArchiveManager.CreateFormat
import com.subtitleedit.util.ArchiveManager.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只覆盖不依赖 7z 原生库的查表函数。 */
class ArchiveManagerFormatTest {

    // ==================== compressionMethods ====================

    @Test
    fun compressionMethods_zipOffersDeflateAndStore() {
        assertEquals(
            listOf(CompressionMethod.ZIP_DEFLATE, CompressionMethod.ZIP_STORE),
            ArchiveManager.compressionMethods(CreateFormat.ZIP)
        )
    }

    @Test
    fun compressionMethods_sevenZDefaultsToDeflate() {
        val methods = ArchiveManager.compressionMethods(CreateFormat.SEVEN_Z)
        assertEquals(CompressionMethod.SEVEN_Z_DEFLATE, methods.first())
        assertEquals(
            listOf(
                CompressionMethod.SEVEN_Z_DEFLATE,
                CompressionMethod.SEVEN_Z_LZMA2,
                CompressionMethod.SEVEN_Z_BZIP2,
                CompressionMethod.SEVEN_Z_COPY
            ),
            methods
        )
    }

    @Test
    fun compressionMethods_tarDefaultsToStore() {
        val methods = ArchiveManager.compressionMethods(CreateFormat.TAR)
        assertEquals(CompressionMethod.TAR_STORE, methods.first())
        assertEquals(
            listOf(
                CompressionMethod.TAR_STORE,
                CompressionMethod.TAR_GZIP,
                CompressionMethod.TAR_BZIP2,
                CompressionMethod.TAR_XZ
            ),
            methods
        )
    }

    @Test
    fun compressionMethods_doNotOverlapBetweenFormats() {
        val zip = ArchiveManager.compressionMethods(CreateFormat.ZIP).toSet()
        val sevenZ = ArchiveManager.compressionMethods(CreateFormat.SEVEN_Z).toSet()
        val tar = ArchiveManager.compressionMethods(CreateFormat.TAR).toSet()
        assertTrue((zip intersect sevenZ).isEmpty())
        assertTrue((zip intersect tar).isEmpty())
        assertTrue((sevenZ intersect tar).isEmpty())
    }

    @Test
    fun compressionMethods_coverEveryEnumConstant() {
        val listed = CreateFormat.entries.flatMap { ArchiveManager.compressionMethods(it) }.toSet()
        assertEquals(CompressionMethod.entries.toSet(), listed)
    }

    @Test
    fun compressionMethods_allHaveDisplayName() {
        CompressionMethod.entries.forEach { assertTrue(it.name, it.displayName.isNotBlank()) }
    }

    // ==================== encryptionMethods ====================

    @Test
    fun encryptionMethods_zipOffersCryptoAndAes() {
        assertEquals(
            listOf(EncryptionMethod.ZIP_CRYPTO, EncryptionMethod.ZIP_AES_256),
            ArchiveManager.encryptionMethods(CreateFormat.ZIP)
        )
    }

    @Test
    fun encryptionMethods_sevenZOffersAesOnly() {
        assertEquals(
            listOf(EncryptionMethod.SEVEN_Z_AES_256),
            ArchiveManager.encryptionMethods(CreateFormat.SEVEN_Z)
        )
    }

    @Test
    fun encryptionMethods_tarSupportsNone() {
        assertTrue(ArchiveManager.encryptionMethods(CreateFormat.TAR).isEmpty())
    }

    @Test
    fun defaultEncryptionMethod_isFirstAvailableOrNull() {
        assertEquals(EncryptionMethod.ZIP_CRYPTO, ArchiveManager.defaultEncryptionMethod(CreateFormat.ZIP))
        assertEquals(
            EncryptionMethod.SEVEN_Z_AES_256,
            ArchiveManager.defaultEncryptionMethod(CreateFormat.SEVEN_Z)
        )
        assertNull(ArchiveManager.defaultEncryptionMethod(CreateFormat.TAR))
    }

    // ==================== outputExtension ====================

    @Test
    fun outputExtension_zipAndSevenZIgnoreMethod() {
        ArchiveManager.compressionMethods(CreateFormat.ZIP).forEach {
            assertEquals("zip", ArchiveManager.outputExtension(CreateFormat.ZIP, it))
        }
        ArchiveManager.compressionMethods(CreateFormat.SEVEN_Z).forEach {
            assertEquals("7z", ArchiveManager.outputExtension(CreateFormat.SEVEN_Z, it))
        }
    }

    @Test
    fun outputExtension_tarDependsOnCompression() {
        assertEquals("tar", ArchiveManager.outputExtension(CreateFormat.TAR, CompressionMethod.TAR_STORE))
        assertEquals("tar.gz", ArchiveManager.outputExtension(CreateFormat.TAR, CompressionMethod.TAR_GZIP))
        assertEquals("tar.bz2", ArchiveManager.outputExtension(CreateFormat.TAR, CompressionMethod.TAR_BZIP2))
        assertEquals("tar.xz", ArchiveManager.outputExtension(CreateFormat.TAR, CompressionMethod.TAR_XZ))
    }

    @Test
    fun outputExtension_tarWithForeignMethod_throws() {
        val failure = runCatching {
            ArchiveManager.outputExtension(CreateFormat.TAR, CompressionMethod.ZIP_DEFLATE)
        }.exceptionOrNull()
        assertTrue("应当抛出 IllegalArgumentException，实际为 $failure", failure is IllegalArgumentException)
        assertEquals("压缩方式与 TAR 不匹配", failure!!.message)
    }

    @Test
    fun outputExtension_recognizedForEveryFormatMethodPair() {
        CreateFormat.entries.forEach { format ->
            ArchiveManager.compressionMethods(format).forEach { method ->
                val extension = ArchiveManager.outputExtension(format, method)
                assertTrue(
                    "$format/$method 的扩展名 $extension 未被识别",
                    extension.substringAfterLast('.') in ArchiveManager.recognizedExtensions
                )
            }
        }
    }

    // ==================== ExtractionLimits ====================

    @Test
    fun extractionLimits_defaultsArePositive() {
        val limits = ArchiveManager.ExtractionLimits()
        assertTrue(limits.maxEntries > 0)
        assertTrue(limits.maxBytes > 0L)
    }

    @Test
    fun extractionLimits_rejectNonPositiveValues() {
        assertTrue(
            runCatching { ArchiveManager.ExtractionLimits(maxEntries = 0) }
                .exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching { ArchiveManager.ExtractionLimits(maxBytes = 0L) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    // ==================== DestinationConflictException ====================

    @Test
    fun destinationConflictException_carriesEntryName() {
        val exception = ArchiveManager.DestinationConflictException("dir/a.txt")
        assertEquals("dir/a.txt", exception.entryName)
        assertEquals("目标已存在：dir/a.txt", exception.message)
        assertEquals(-1L, exception.conflict.sourceSize)
    }
}
