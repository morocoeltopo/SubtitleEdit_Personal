package com.subtitleedit

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EditorDocumentWriterTest {
    @Test
    fun writesContentUsingSelectedCharset() {
        val output = ByteArrayOutputStream()

        EditorDocumentWriter.write("字幕", StandardCharsets.UTF_8) { output }

        assertEquals("字幕", output.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    fun nullOutputStreamIsReportedAsFailure() {
        assertThrows(IllegalStateException::class.java) {
            EditorDocumentWriter.write("content", StandardCharsets.UTF_8) { null }
        }
    }
}
