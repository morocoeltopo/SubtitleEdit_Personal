package com.subtitleedit

import java.io.OutputStream
import java.nio.charset.Charset

internal object EditorDocumentWriter {
    fun write(
        content: String,
        charset: Charset,
        openStream: () -> OutputStream?
    ) {
        val outputStream = openStream() ?: throw IllegalStateException("无法打开目标文件")
        outputStream.use { it.write(content.toByteArray(charset)) }
    }
}
