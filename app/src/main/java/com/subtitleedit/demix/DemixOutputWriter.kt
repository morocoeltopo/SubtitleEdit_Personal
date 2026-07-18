package com.subtitleedit.demix

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

object DemixOutputWriter {
    fun exists(context: Context, directory: Uri, fileName: String): Boolean {
        return if (directory.scheme == "file") {
            File(requireNotNull(directory.path), fileName).exists()
        } else {
            documentDirectory(context, directory)?.findFile(fileName) != null
        }
    }

    fun copy(
        context: Context,
        source: File,
        directory: Uri,
        requestedName: String,
        overwrite: Boolean
    ): String {
        return if (directory.scheme == "file") {
            val dir = File(requireNotNull(directory.path)).apply { mkdirs() }
            val target = if (overwrite) File(dir, requestedName) else uniqueFile(dir, requestedName)
            source.inputStream().use { input ->
                FileOutputStream(target, false).use { output -> input.copyTo(output, 256 * 1024) }
            }
            target.name
        } else {
            val dir = documentDirectory(context, directory)
                ?: throw IllegalStateException("无法访问输出目录")
            val finalName = if (overwrite) requestedName else uniqueDocumentName(dir, requestedName)
            dir.findFile(finalName)?.takeIf { overwrite }?.delete()
            val document = dir.createFile("audio/wav", finalName)
                ?: throw IllegalStateException("无法创建输出文件：$finalName")
            context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            } ?: throw IllegalStateException("无法写入输出文件：$finalName")
            finalName
        }
    }

    private fun documentDirectory(context: Context, uri: Uri): DocumentFile? {
        return DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val initial = File(directory, requestedName)
        if (!initial.exists()) return initial
        val base = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidateName = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
            val candidate = File(directory, candidateName)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun uniqueDocumentName(directory: DocumentFile, requestedName: String): String {
        if (directory.findFile(requestedName) == null) return requestedName
        val base = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
            if (directory.findFile(candidate) == null) return candidate
            index++
        }
    }
}
