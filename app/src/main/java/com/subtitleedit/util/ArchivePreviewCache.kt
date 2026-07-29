package com.subtitleedit.util

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

object ArchivePreviewCache {
    private const val MAGIC = 0x41505256
    private const val VERSION = 2
    private const val MAX_ENTRIES = 100_000
    private const val MAX_NAME_BYTES = 1024 * 1024

    fun write(context: Context, entries: List<ArchiveManager.EntryInfo>): File {
        require(entries.size <= MAX_ENTRIES) { "预览条目数量超过限制" }
        val directory = File(context.cacheDir, "archive_previews").apply { mkdirs() }
        val outputFile = File(directory, "${UUID.randomUUID()}.bin")
        try {
            DataOutputStream(BufferedOutputStream(outputFile.outputStream())).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(entries.size)
                entries.forEach { entry ->
                    val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                    require(nameBytes.size <= MAX_NAME_BYTES) { "压缩包条目名称过长" }
                    output.writeInt(nameBytes.size)
                    output.write(nameBytes)
                    output.writeLong(entry.size)
                    output.writeLong(entry.compressedSize)
                    output.writeBoolean(entry.isDirectory)
                    output.writeLong(entry.modifiedTimeMillis)
                }
            }
            return outputFile
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    fun read(file: File): List<ArchiveManager.EntryInfo> {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC) {
                throw IOException("预览数据格式无效")
            }
            val version = input.readInt()
            if (version !in 1..VERSION) throw IOException("预览数据格式无效")
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) throw IOException("预览条目数量无效")
            return List(count) {
                val nameLength = input.readInt()
                if (nameLength !in 0..MAX_NAME_BYTES) throw IOException("预览条目名称无效")
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val name = nameBytes.toString(Charsets.UTF_8)
                val size = input.readLong()
                val compressedSize = input.readLong()
                val isDirectory = input.readBoolean()
                val modifiedTimeMillis = if (version >= 2) input.readLong() else 0L
                ArchiveManager.EntryInfo(name, size, compressedSize, isDirectory, modifiedTimeMillis)
            }
        }
    }
}
