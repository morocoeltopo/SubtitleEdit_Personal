package com.subtitleedit.util

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object RuntimeLogManager {
    private const val LOG_DIR = "runtime_logs"
    private const val PREFS_NAME = "runtime_logs"
    private const val KEY_CLEAR_TIME = "clear_time"
    private const val RETENTION_MS = 24L * 60L * 60L * 1000L

    private val logcatTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val displayTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    data class Snapshot(
        val content: String,
        val file: File,
        val capturedAt: Long,
        val packageName: String
    )

    fun captureLastDay(context: Context): Snapshot {
        val now = System.currentTimeMillis()
        val since = maxOf(now - RETENTION_MS, getClearTime(context))
        pruneOldLogs(context, now)

        val packageName = context.packageName
        val sinceArg = logcatTimeFormat.format(Date(since))
        val raw = runLogcat(
            listOf(
                "logcat",
                "-d",
                "-v",
                "time",
                "-T",
                sinceArg,
                "--pid",
                Process.myPid().toString()
            )
        )

        val filtered = filterLastDay(raw, since, now).trim()
        val content = buildString {
            appendLine("SubtitleEdit for Android 运行日志")
            appendLine("包名：$packageName")
            appendLine("采集时间：${displayTimeFormat.format(Date(now))}")
            appendLine("有效范围：本应用最近 24 小时，且不早于上次清空时间")
            appendLine()
            append(if (filtered.isBlank()) "暂无可读取的日志。" else filtered)
            appendLine()
        }

        val file = File(logDir(context), "runtime-log-${fileTimeFormat.format(Date(now))}.txt")
        file.writeText(content, Charsets.UTF_8)
        return Snapshot(content, file, now, packageName)
    }

    fun exportFileName(now: Long = System.currentTimeMillis()): String {
        return "subtitleedit-log-${fileTimeFormat.format(Date(now))}.txt"
    }

    fun clear(context: Context) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CLEAR_TIME, now)
            .apply()
        logDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun getClearTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CLEAR_TIME, 0L)
    }

    private fun logDir(context: Context): File {
        return File(context.cacheDir, LOG_DIR).apply { mkdirs() }
    }

    private fun pruneOldLogs(context: Context, now: Long) {
        logDir(context).listFiles()
            ?.filter { it.isFile && now - it.lastModified() > RETENTION_MS }
            ?.forEach { it.delete() }
    }

    private fun runLogcat(command: List<String>): String {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            "读取 logcat 失败：${e.message}"
        }
    }

    private fun filterLastDay(raw: String, since: Long, now: Long): String {
        if (raw.isBlank()) return ""
        return raw.lineSequence()
            .filter { line ->
                val time = parseLogcatTime(line, now)
                time == null || time in since..now
            }
            .joinToString("\n")
    }

    private fun parseLogcatTime(line: String, now: Long): Long? {
        if (line.length < 18) return null
        return try {
            val parsed = logcatTimeFormat.parse(line.substring(0, 18)) ?: return null
            val current = Calendar.getInstance()
            val logTime = Calendar.getInstance().apply {
                time = parsed
                set(Calendar.YEAR, current.get(Calendar.YEAR))
            }
            if (logTime.timeInMillis > now + RETENTION_MS) {
                logTime.add(Calendar.YEAR, -1)
            }
            logTime.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
