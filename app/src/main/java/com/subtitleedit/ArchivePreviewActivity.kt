package com.subtitleedit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.adapter.ArchivePreviewAdapter
import com.subtitleedit.databinding.ActivityArchivePreviewBinding
import com.subtitleedit.model.ArchivePreviewBrowser
import com.subtitleedit.util.ArchivePreviewCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ArchivePreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityArchivePreviewBinding
    private lateinit var adapter: ArchivePreviewAdapter
    private lateinit var browser: ArchivePreviewBrowser
    private lateinit var previewFile: File
    private var archiveName = ""
    private var currentDirectory = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchivePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        archiveName = intent.getStringExtra(EXTRA_ARCHIVE_NAME).orEmpty()
        previewFile = File(intent.getStringExtra(EXTRA_PREVIEW_PATH).orEmpty())
        currentDirectory = savedInstanceState?.getString(STATE_DIRECTORY).orEmpty()

        setupToolbar()
        adapter = ArchivePreviewAdapter { directory -> showDirectory(directory.path) }
        binding.rvFileList.layoutManager = LinearLayoutManager(this)
        binding.rvFileList.adapter = adapter
        loadPreview()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDirectory.isNotEmpty()) {
                    showDirectory(ArchivePreviewBrowser.parentOf(currentDirectory))
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = archiveName
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun loadPreview() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ArchivePreviewCache.read(previewFile) }
            }
            binding.loadingIndicator.visibility = View.GONE
            result.onSuccess { entries ->
                browser = ArchivePreviewBrowser(entries)
                supportActionBar?.subtitle = "${entries.size} 项"
                showDirectory(currentDirectory)
            }.onFailure { error ->
                binding.tvEmptyState.text = "无法读取预览：${error.message ?: "未知错误"}"
                binding.tvEmptyState.visibility = View.VISIBLE
            }
        }
    }

    private fun showDirectory(directory: String) {
        if (!::browser.isInitialized) return
        currentDirectory = directory.trim('/')
        val items = browser.itemsAt(currentDirectory)
        adapter.submitList(items)
        binding.tvCurrentPath.text = buildString {
            append(archiveName)
            if (currentDirectory.isNotEmpty()) append(" / ").append(currentDirectory)
        }
        binding.pathNavigation.post { binding.pathNavigation.fullScroll(View.FOCUS_RIGHT) }
        binding.tvEmptyState.text = "此文件夹为空"
        binding.tvEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DIRECTORY, currentDirectory)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) previewFile.delete()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ARCHIVE_NAME = "extra_archive_name"
        private const val EXTRA_PREVIEW_PATH = "extra_preview_path"
        private const val STATE_DIRECTORY = "state_directory"

        fun createIntent(context: Context, archiveName: String, previewFile: File): Intent =
            Intent(context, ArchivePreviewActivity::class.java).apply {
                putExtra(EXTRA_ARCHIVE_NAME, archiveName)
                putExtra(EXTRA_PREVIEW_PATH, previewFile.absolutePath)
            }
    }
}
