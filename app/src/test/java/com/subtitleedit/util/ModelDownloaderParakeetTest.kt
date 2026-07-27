package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloaderParakeetTest {
    @Test
    fun parakeetCatalogUsesExpectedReleasePackages() {
        val tdt = ModelDownloader.PARAKEET_TDT_MODEL
        assertEquals(SettingsManager.ASR_MODEL_PARAKEET_TDT, tdt.modelType)
        assertEquals(ModelDownloader.ParakeetArchitecture.TDT, tdt.architecture)
        assertTrue(tdt.url.endsWith("/${tdt.directoryName}.tar.bz2"))

        val ctc = ModelDownloader.PARAKEET_CTC_JA_MODEL
        assertEquals(SettingsManager.ASR_MODEL_PARAKEET_CTC_JA, ctc.modelType)
        assertEquals(ModelDownloader.ParakeetArchitecture.CTC, ctc.architecture)
        assertTrue(ctc.url.endsWith("/${ctc.directoryName}.tar.bz2"))
    }

    @Test
    fun parakeetArchivesRequireFilesForTheirDecoderArchitecture() {
        assertEquals(
            setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            ModelDownloader.parakeetRequiredFileNames(ModelDownloader.ParakeetArchitecture.TDT)
        )
        assertEquals(
            setOf("model.int8.onnx", "tokens.txt"),
            ModelDownloader.parakeetRequiredFileNames(ModelDownloader.ParakeetArchitecture.CTC)
        )
    }
}
