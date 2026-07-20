package com.subtitleedit.util

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class ModelDownloadProgressDialog(
    context: Context,
    title: String,
    private val onCancel: () -> Unit
) {
    private val statusText = TextView(context)
    private val progressBar = ProgressBar(
        context,
        null,
        android.R.attr.progressBarStyleHorizontal
    ).apply {
        max = 1000
        isIndeterminate = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(12) }
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
        addView(statusText)
        addView(progressBar)
    }
    private val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(content)
        .setNegativeButton("取消") { _, _ -> onCancel() }
        .create()

    fun show() {
        statusText.text = "正在准备下载"
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancel() }
        dialog.show()
    }

    fun update(progress: ModelDownloader.Progress) {
        if (!dialog.isShowing) return
        if (progress.totalBytes > 0L) {
            progressBar.isIndeterminate = false
            progressBar.progress = ((progress.downloadedBytes * 1000L) / progress.totalBytes)
                .coerceIn(0L, 1000L)
                .toInt()
            val percent = ((progress.downloadedBytes * 100L) / progress.totalBytes)
                .coerceIn(0L, 100L)
            statusText.text = "${progress.message}：$percent%（${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}）"
        } else {
            progressBar.isIndeterminate = true
            statusText.text = progress.message
        }
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
