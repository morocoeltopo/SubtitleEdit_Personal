package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.databinding.ActivityLogBinding
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.RuntimeLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : AppCompatActivity() {

    private companion object {
        const val MENU_CLEAR_LOG = 1
    }

    private lateinit var binding: ActivityLogBinding
    private var currentLogContent: String = ""

    private val exportDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { exportLogToDirectory(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
        refreshLog()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "运行日志"
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_CLEAR_LOG, Menu.NONE, "清空")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CLEAR_LOG -> {
                clearLog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupButtons() {
        binding.btnRefresh.setOnClickListener {
            refreshLog()
        }
        binding.btnExport.setOnClickListener {
            if (currentLogContent.isBlank()) {
                refreshLog { openExportDirectoryPicker() }
            } else {
                openExportDirectoryPicker()
            }
        }
    }

    private fun refreshLog(onComplete: (() -> Unit)? = null) {
        binding.btnRefresh.isEnabled = false
        binding.btnExport.isEnabled = false
        binding.tvLogInfo.text = "正在读取本应用最近 24 小时日志..."
        binding.tvLogContent.text = ""

        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                RuntimeLogManager.captureLastDay(this@LogActivity)
            }
            currentLogContent = snapshot.content
            binding.tvLogContent.text = currentLogContent
            binding.tvLogInfo.text = "${snapshot.packageName} 最近 24 小时"
            binding.btnRefresh.isEnabled = true
            binding.btnExport.isEnabled = true
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_UP)
            }
            onComplete?.invoke()
        }
    }

    private fun clearLog() {
        RuntimeLogManager.clear(this)
        currentLogContent = ""
        binding.tvLogContent.text = "暂无可读取的日志。"
        binding.tvLogInfo.text = "${packageName} 最近 24 小时"
        OverwritingToast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun openExportDirectoryPicker() {
        exportDirLauncher.launch(null)
    }

    private fun exportLogToDirectory(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val dir = DocumentFile.fromTreeUri(this@LogActivity, uri)
                        ?: throw IllegalStateException("无法访问所选目录")
                    val fileName = uniqueFileName(dir, RuntimeLogManager.exportFileName())
                    val file = dir.createFile("text/plain", fileName)
                        ?: throw IllegalStateException("无法创建日志文件")
                    contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                        output.write(currentLogContent.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: throw IllegalStateException("无法写入日志文件")
                    fileName
                }
            }

            result.onSuccess { fileName ->
                OverwritingToast.makeText(this@LogActivity, "已导出：$fileName", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                OverwritingToast.makeText(this@LogActivity, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uniqueFileName(dir: DocumentFile, originalName: String): String {
        val name = originalName.substringBeforeLast(".")
        val extension = originalName.substringAfterLast(".", "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var fileName = originalName
        var index = 1
        while (dir.findFile(fileName) != null) {
            fileName = "$name ($index)$suffix"
            index++
        }
        return fileName
    }
}
