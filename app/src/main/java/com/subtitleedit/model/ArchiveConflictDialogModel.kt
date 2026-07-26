package com.subtitleedit.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ArchiveConflictFileMetadata(
    val sizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null
)

data class ArchiveConflictDialogModel(
    val entryName: String,
    val source: ArchiveConflictFileMetadata,
    val existing: ArchiveConflictFileMetadata
)

object ArchiveConflictDialogFormatter {
    fun size(sizeBytes: Long?): String {
        if (sizeBytes == null || sizeBytes < 0L) return "未知"
        if (sizeBytes < 1024L) return "$sizeBytes B"

        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = sizeBytes.toDouble()
        var unitIndex = -1
        do {
            value /= 1024.0
            unitIndex++
        } while (value >= 1024.0 && unitIndex < units.lastIndex)

        return DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault()))
            .format(value) + units[unitIndex]
    }

    fun modifiedTime(modifiedAtMillis: Long?): String {
        if (modifiedAtMillis == null || modifiedAtMillis <= 0L) return "未知"
        return SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())
            .format(Date(modifiedAtMillis))
    }
}
