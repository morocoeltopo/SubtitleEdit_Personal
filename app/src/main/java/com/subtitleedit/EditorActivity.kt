package com.subtitleedit

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.subtitleedit.view.DraggableScrollView
import com.subtitleedit.view.DraggableRecyclerView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.speech.tts.TextToSpeech
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.adapter.TranslationPreviewAdapter
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.editor.EditorAudioFilePreparer
import com.subtitleedit.editor.EditorPlaybackController
import com.subtitleedit.editor.EditorSearchController
import com.subtitleedit.editor.EditorWaveformController
import com.subtitleedit.util.AiTranslator
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.CutPasteController
import com.subtitleedit.util.SubtitlePasteOps
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.WhisperRecognizer
import com.subtitleedit.util.SubtitleEntryOps
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arthenica.ffmpegkit.FFmpegKit
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope

/**
 * 字幕编辑界面
 * 支持点击编辑、长按菜单、多选、复制粘贴功能
 * 支持草稿箱功能
 * 支持源视图模式（用于 TXT 文件）
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var subtitleAdapter: SubtitleAdapter
    
    private var filePath: String = ""
    private var currentFile: File? = null
    // 字幕文件路径（当打开音频文件时，用于保存字幕）
    private var subtitleFilePath: String = ""
    private var subtitleFile: File? = null
    private var subtitleEntries = mutableListOf<SubtitleEntry>()
    private var lastIndexedEntryCount = -1
    private var currentCharset: Charset = StandardCharsets.UTF_8
    private var currentFormat: SubtitleParser.SubtitleFormat = SubtitleParser.SubtitleFormat.UNKNOWN
    
    // 源视图模式标志
    private var isSourceViewMode = false
    // 源视图原始内容（用于 TXT 文件）- 保存原始文件内容，不做任何修改
    private var originalFileContent = ""
    // 当前显示的内容（可能是原始内容或从字幕列表生成的内容）
    private var sourceViewContent = ""
    
    // 切换视图前保存的滚动位置
    private var savedScrollPosition = 0
    private var savedFirstVisibleItemPosition = 0
    
    // 长按时的位置（用于时间偏移等操作）
    private var longClickPosition: Int = -1
    
    // 是否有未保存的更改
    private var hasUnsavedChanges = false

    // 是否为新建且从未保存过的文件
    private var isNewFile = true

    // 当前格式信息（用于 toolbar subtitle 恢复）
    private var currentFormatInfo = ""
    
    // 复制/剪贴板数据（支持多行）
    private var clipboardTexts: List<String> = emptyList()
    private val cutPasteController = CutPasteController()
    
    // 翻译相关
    private var translateJob: Job? = null
    private var isTranslating = false
    private var translateCancelled = false
    private var activeAiTranslator: AiTranslator? = null

    // 快速转录相关
    private var transcribeJob: Job? = null
    private var transcribeCancelled = false

    // 快速 TTS 相关
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var activeTtsEnginePreference: String? = null
    private var activeTtsLanguagePreference: String? = null
    private var ttsDefaultLocale: Locale? = null
    private var pendingTtsTexts: List<String> = emptyList()
    private var ttsGeneration = 0
    
    private lateinit var searchController: EditorSearchController
    
    // 音频文件相关
    private var isAudioFile: Boolean = false
    private lateinit var audioFilePreparer: EditorAudioFilePreparer
    private lateinit var playbackController: EditorPlaybackController
    private lateinit var waveformController: EditorWaveformController

    // 文件选择器
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { openFileFromUri(it) }
    }
    
    // 保存文件选择器
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { saveFileToUri(it) }
    }
    
    // 草稿箱选择器
    private val draftLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val content = result.data?.getStringExtra(DraftsActivity.EXTRA_DRAFT_CONTENT) ?: ""
            val draftFileName = result.data?.getStringExtra(DraftsActivity.EXTRA_DRAFT_FILE_NAME) ?: ""
            if (content.isNotEmpty()) {
                loadDraftContent(content, draftFileName)
            }
        }
    }
    
    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_IS_AUDIO_FILE = "extra_is_audio_file"
        const val EXTRA_SUBTITLE_FILE_PATH = "extra_subtitle_file_path"
        private const val MENU_SELECT_ALL = 0x20001
        private const val MENU_SELECT_RANGE = 0x20002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioFilePreparer = EditorAudioFilePreparer(cacheDir)
        
        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        isAudioFile = intent.getBooleanExtra(EXTRA_IS_AUDIO_FILE, false)
        subtitleFilePath = intent.getStringExtra(EXTRA_SUBTITLE_FILE_PATH) ?: ""
        
        if (filePath.isNotEmpty()) {
            if (isAudioFile) {
                // 音频文件模式：currentFile 指向音频文件，subtitleFile 指向字幕文件
                currentFile = File(filePath)
                if (subtitleFilePath.isNotEmpty()) {
                    subtitleFile = File(subtitleFilePath)
                }
            } else {
                // 普通模式：currentFile 指向字幕文件
                currentFile = File(filePath)
            }
        }
        
        setupToolbar()
        setupRecyclerView()
        setupSourceView()
        setupSearchController()
        setupPlaybackController()
        setupWaveformController()
        setupAudioActions()
        setupBackPressedHandler()
        
        if (filePath.isNotEmpty()) {
            if (isAudioFile) {
                loadAudioFile(subtitleFilePath)
            } else {
                loadFile()
            }
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "未命名"
        
        binding.toolbar.setNavigationOnClickListener {
            if (subtitleAdapter.getSelectedCount() > 0) {
                cancelSelection()
            } else {
                handleBackPressed()
            }
        }
        
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            handleMenuClick(menuItem)
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        if (::subtitleAdapter.isInitialized && subtitleAdapter.getSelectedCount() > 0) {
            menu.add(Menu.NONE, MENU_SELECT_ALL, 0, "全选")
                .setIcon(R.drawable.ic_select_all)
                .setContentDescription("全选")
                .setTooltipText("全选")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_SELECT_RANGE, 1, "区间选择")
                .setIcon(R.drawable.ic_select_range)
                .setContentDescription("区间选择")
                .setTooltipText("区间选择")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        } else {
            menuInflater.inflate(R.menu.menu_editor, menu)
        }
        return true
    }
    
    private fun handleMenuClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_new -> {
                newFile()
                true
            }
            R.id.menu_open -> {
                openFile()
                true
            }
            R.id.menu_save -> {
                saveFile()
                true
            }
            R.id.menu_save_as -> {
                saveFileAs()
                true
            }
            R.id.menu_encoding -> {
                showEncodingDialog()
                true
            }
            R.id.menu_source_view -> {
                toggleSourceView()
                true
            }
            R.id.menu_search -> {
                searchController.show()
                true
            }
            MENU_SELECT_ALL -> {
                selectAllSubtitles()
                true
            }
            MENU_SELECT_RANGE -> {
                selectRangeBetweenSelectedSubtitles()
                true
            }
            R.id.menu_save_draft -> {
                saveDraft()
                true
            }
            R.id.menu_drafts -> {
                openDrafts()
                true
            }
            else -> false
        }
    }
    
    private fun setupRecyclerView() {
        subtitleAdapter = SubtitleAdapter(
            onItemClick = { _, _ -> },
            onItemLongClick = { _, position ->
                showContextMenu(position)
            },
            onTimeClick = { entry, position, isStartTime ->
                showTimeEditDialog(entry, position, isStartTime)
            },
            onTextClick = { entry, position ->
                showTextEditDialog(entry, position)
            },
            onJumpToTimeClick = { entry, _ ->
                jumpToSubtitleTime(entry)
            },
            onSetTimeClick = { entry, position ->
                setSubtitleTimeToCurrentPosition(entry, position)
            },
            isAudioFile = isAudioFile,
            onSelectionChanged = {
                updateSelectedCountDisplay()
            }
        )
        
        binding.rvSubtitles.apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = subtitleAdapter
            // 滑块大跨度定位时复用更多已绑定行，减少文本测量和 ViewHolder 重绑。
            setItemViewCacheSize(12)
        }
    }
    
    private fun setupSourceView() {
        binding.etSourceView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSourceViewMode) {
                    hasUnsavedChanges = true
                    sourceViewContent = s?.toString() ?: ""
                }
            }
        })
    }
    
    private fun setupSearchController() {
        searchController = EditorSearchController(
            context = this,
            binding = binding,
            subtitleAdapter = subtitleAdapter,
            isSourceViewMode = { isSourceViewMode },
            entries = { subtitleEntries },
            replaceSourceContent = { content ->
                binding.etSourceView.setText(content)
                sourceViewContent = content
                hasUnsavedChanges = true
            },
            applyEntryUpdates = { updates ->
                updates.forEach { update ->
                    subtitleEntries.getOrNull(update.index)?.text = update.newText
                }
                notifyEntriesChanged(updates.map { it.index }, includeNeighbors = false)
            },
            confirmReplaceAll = ::showReplaceAllConfirm,
            showMessage = ::showShortToast
        )
    }

    private fun setupPlaybackController() {
        playbackController = EditorPlaybackController(
            context = this,
            binding = binding,
            isAudioFile = isAudioFile,
            audioFileName = { currentFile?.name },
            subtitles = { subtitleEntries },
            isSourceViewMode = { isSourceViewMode },
            onPlayingSubtitleChanged = { index ->
                if (index == null) {
                    subtitleAdapter.clearPlayingHighlight()
                } else {
                    subtitleAdapter.highlightCurrentPlaying(index)
                }
            },
            showMessage = ::showShortToast
        )
        playbackController.bind()
    }

    private fun setupWaveformController() {
        waveformController = EditorWaveformController(
            context = this,
            binding = binding,
            scope = lifecycleScope,
            isAudioFile = isAudioFile,
            appCacheDir = cacheDir,
            currentPlaybackPositionMs = { playbackController.currentPositionMs },
            onSubtitlesChanged = { updatedSubtitles ->
                setSubtitleEntries(updatedSubtitles)
                submitSubtitleList(refreshAll = true, syncWaveform = false, markChanged = true)
            },
            onSelectedIndexChanged = { index ->
                if (index in subtitleEntries.indices) {
                    (binding.rvSubtitles.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(index, 0)
                }
            },
            onTimestampInserted = ::insertSubtitleFromTimestamp,
            showMessage = ::showShortToast
        )
        waveformController.bind()
    }
    
    private fun updateSelectedCountDisplay() {
        val count = subtitleAdapter.getSelectedCount()
        if (count > 0) {
            val formatName = getFormatDisplayName(currentFormat)
            supportActionBar?.subtitle = "$formatName | ${subtitleEntries.size} 条 | 选中：$count"
        } else {
            supportActionBar?.subtitle = currentFormatInfo
        }
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(
            this,
            if (count > 0) R.drawable.ic_close else R.drawable.ic_back
        )
        binding.toolbar.navigationContentDescription =
            if (count > 0) "取消选择" else "返回"
        invalidateOptionsMenu()
    }
    
    private fun loadFile() {
        if (filePath.isEmpty() || currentFile == null) {
            finishWithToast("文件路径无效")
            return
        }

        val file = currentFile ?: run {
            finishWithToast("文件路径无效")
            return
        }

        if (!file.exists()) {
            finishWithToast("文件不存在")
            return
        }

        supportActionBar?.title = file.name
        // 使用用户设置的默认编码
        val settingsManager = SettingsManager.getInstance(this)
        currentCharset = settingsManager.getDefaultEncoding()

        val content = readFileOrNull(file, "读取文件失败") ?: return
        parseContent(content)
        hasUnsavedChanges = false
        isNewFile = false
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            val content = FileUtils.readUri(this, uri)
            currentFile = null
            // 获取文件名并更新显示
            val fileName = getFileNameFromUri(uri)
            supportActionBar?.title = fileName
            parseContent(content)
            hasUnsavedChanges = false
            isNewFile = false
            com.subtitleedit.util.OverwritingToast.makeText(this, "文件已打开：$fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "打开文件失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 从 URI 获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "未命名"
        // 尝试从 display name 获取
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) {
                fileName = it.getString(nameIndex)
            }
        }
        // 如果获取失败，尝试从 path 获取
        if (fileName == "未命名") {
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                fileName = path.substringAfterLast('/')
            }
        }
        return fileName
    }
    
    private fun reloadFile() {
        val targetFile = if (isAudioFile) subtitleFile else currentFile
        if (targetFile == null || !targetFile.exists()) {
            showShortToast("当前文件无法重新加载编码，请通过「打开」功能重新选择文件")
            return
        }

        val content = readFileOrNull(targetFile, "切换编码失败") ?: return
        parseContent(content)
        hasUnsavedChanges = false
        showShortToast("已切换编码为：${FileUtils.SUPPORTED_ENCODINGS.find { it.charset == currentCharset }?.displayName}")
    }
    
    private fun parseContent(content: String) {
        currentFormat = SubtitleParser.detectFormat(content)
        
        // 始终保存原始文件内容
        originalFileContent = content
        
        // 如果是 TXT 格式，直接使用源视图模式
        if (currentFormat == SubtitleParser.SubtitleFormat.TXT) {
            sourceViewContent = originalFileContent
            enterSourceViewMode()
        } else {
            setSubtitleEntries(SubtitleParser.parse(content, currentCharset))
            exitSourceViewMode()
        }
        
        updateFormatInfo()
        
        if (subtitleEntries.isEmpty() && !isSourceViewMode) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "未找到字幕内容", Toast.LENGTH_SHORT).show()
        }
        
        // 同步字幕到波形视图（仅音频模式有效）
        syncWaveformSubtitles()
    }
    
    /**
     * 进入源视图模式（带动画，保持滚动位置）
     */
    private fun enterSourceViewMode() {
        isSourceViewMode = true
        if (::searchController.isInitialized) searchController.onEditorModeChanged()
        
        // 保存 RecyclerView 的滚动位置
        val layoutManager = binding.rvSubtitles.layoutManager as LinearLayoutManager
        savedFirstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
        val firstView = layoutManager.findViewByPosition(savedFirstVisibleItemPosition)
        savedScrollPosition = firstView?.top ?: 0
        
        // 设置源视图内容
        binding.etSourceView.setText(sourceViewContent)
        
        // 淡出字幕列表，淡入源视图
        binding.rvSubtitles.animate()
            .alpha(0f)
            .setDuration(150)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.rvSubtitles.visibility = android.view.View.GONE
                    binding.svSourceView.alpha = 0f
                    binding.svSourceView.visibility = android.view.View.VISIBLE
                    binding.svSourceView.animate()
                        .alpha(1f)
                        .setDuration(150)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                // 恢复滚动位置 - 根据可见项计算滚动位置
                                if (savedFirstVisibleItemPosition >= 0 && savedFirstVisibleItemPosition < subtitleEntries.size) {
                                    // 估算滚动位置（每行约 80dp）
                                    val estimatedScroll = savedFirstVisibleItemPosition * 80 - savedScrollPosition
                                    binding.svSourceView.scrollTo(0, estimatedScroll.coerceAtLeast(0))
                                }
                            }
                        })
                }
            })
        
        updateSourceViewMenuTitle()
    }
    
    /**
     * 退出源视图模式（带动画，保持滚动位置）
     */
    private fun exitSourceViewMode() {
        isSourceViewMode = false
        if (::searchController.isInitialized) searchController.onEditorModeChanged()
        
        // 保存 ScrollView 的滚动位置
        savedScrollPosition = binding.svSourceView.scrollY
        
        // 刷新字幕列表
        submitSubtitleList(refreshAll = true, updateFormat = false, syncWaveform = false)
        
        // 淡出源视图，淡入字幕列表
        binding.svSourceView.animate()
            .alpha(0f)
            .setDuration(150)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.svSourceView.visibility = android.view.View.GONE
                    binding.rvSubtitles.alpha = 0f
                    binding.rvSubtitles.visibility = android.view.View.VISIBLE
                    binding.rvSubtitles.animate()
                        .alpha(1f)
                        .setDuration(150)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                // 恢复滚动位置 - 根据滚动位置计算可见项
                                val layoutManager = binding.rvSubtitles.layoutManager as LinearLayoutManager
                                if (subtitleEntries.isNotEmpty()) {
                                    val estimatedPosition = savedScrollPosition / 80
                                    layoutManager.scrollToPositionWithOffset(
                                        estimatedPosition.coerceIn(0, subtitleEntries.lastIndex),
                                        0
                                    )
                                }
                            }
                        })
                }
            })
        
        updateSourceViewMenuTitle()
    }
    
    /**
     * 切换源视图模式
     */
    private fun toggleSourceView() {
        if (currentFormat == SubtitleParser.SubtitleFormat.TXT) {
            showShortToast("TXT 文件只能使用源视图模式")
            return
        }

        if (isSourceViewMode) {
            // 源视图 → 列表视图：直接切换，解析源视图当前内容
            doExitSourceView()
        } else {
            // 列表视图 → 源视图：需要重新读取文件
            doEnterSourceView()
        }
    }

    /**
     * 列表视图 → 源视图
     * 重新从磁盘读取文件内容，有未保存更改时提醒先保存
     */
    private fun doEnterSourceView() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("有未保存的更改")
                .setMessage("切换到源视图将重新读取文件，当前列表中未保存的更改不会体现在源视图中。\n\n建议先保存后再切换。")
                .setPositiveButton("先保存再切换") { _, _ ->
                    saveFile()
                    reloadAndEnterSourceView()
                }
                .setNeutralButton("直接切换（丢弃更改）") { _, _ ->
                    reloadAndEnterSourceView()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            reloadAndEnterSourceView()
        }
    }

    /**
     * 重新从磁盘读取原始文件后进入源视图
     */
    private fun reloadAndEnterSourceView() {
        val file = getCurrentSubtitleFile()

        if (file != null && file.exists()) {
            // 有文件：重新读取磁盘内容，保证与已保存状态一致
            val freshContent = readFileOrNull(file, "读取文件失败") ?: return
            originalFileContent = freshContent
            sourceViewContent = freshContent
        } else {
            // 无文件（从剪贴板/URI 打开，或新建未保存）：退而序列化当前列表
            sourceViewContent = serializeEntriesForFormat(currentFormat)
            originalFileContent = sourceViewContent
            showShortToast("文件尚未保存，已从当前列表生成源视图内容")
        }

        enterSourceViewMode()
        showShortToast("已切换到源视图")
    }

    /**
     * 源视图 → 列表视图：解析源视图中当前编辑的内容
     */
    private fun doExitSourceView() {
        val editedContent = binding.etSourceView.text.toString()
        try {
            setSubtitleEntries(SubtitleParser.parse(editedContent, currentCharset))
            // 将源视图内容同步回 originalFileContent，使再次切换时内容一致
            originalFileContent = editedContent
            sourceViewContent   = editedContent
            // 标记有未保存更改（用户在源视图里编辑了内容）
            if (isSourceContentModifiedComparedToFile(editedContent)) {
                hasUnsavedChanges = true
            }
            exitSourceViewMode()
            updateFormatInfo()
            showShortToast("已切换到列表视图")
        } catch (e: Exception) {
            showShortToast("解析失败：${e.message}")
        }
    }

    /**
     * 更新源视图菜单项标题
     */
    private fun updateSourceViewMenuTitle() {
        // 菜单项标题在 strings.xml 中定义，这里不需要动态更新
    }
    
    /**
     * 加载草稿内容（覆盖当前内容）
     */
    private fun loadDraftContent(content: String, draftFileName: String) {
        AlertDialog.Builder(this)
            .setTitle("加载草稿")
            .setMessage("确定要用草稿内容覆盖当前编辑内容吗？（只覆盖内容，不更改文件名）")
            .setPositiveButton("确定") { _, _ ->
                parseContent(content)
                hasUnsavedChanges = true
                com.subtitleedit.util.OverwritingToast.makeText(this, "已加载草稿：$draftFileName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showContextMenu(position: Int) {
        if (!ensureListMode()) return
        
        // 保存长按位置
        longClickPosition = position
        
        val selectedCount = subtitleAdapter.getSelectedCount()
        val hasSelection = selectedCount > 0
        val hasClipboard = clipboardTexts.isNotEmpty()
        
        val regularActions = mutableListOf<Pair<String, () -> Unit>>()
        regularActions.add("时间偏移" to { showOffsetDialog(position) })
        if (hasClipboard) {
            regularActions.add("向前粘贴 (${clipboardTexts.size}项)" to {
                insertSubtitle(after = false, refPosition = position, pasteAfterInsert = true)
            })
        }
        regularActions.add("向前插入" to { insertSubtitle(false, position) })
        if (hasClipboard) {
            regularActions.add("向后粘贴 (${clipboardTexts.size}项)" to {
                insertSubtitle(after = true, refPosition = position, pasteAfterInsert = true)
            })
        }
        regularActions.add("向后插入" to { insertSubtitle(true, position) })
        regularActions.add("复制" to { copySingle(position) })
        regularActions.add("剪切 (粘贴后删除)" to { cutSingle(position) })
        regularActions.add(
            (if (hasClipboard) "粘贴 (${clipboardTexts.size}项)[当前行]" else "粘贴") to {
                if (hasClipboard) pasteToPosition(position) else ensureClipboardNotEmpty()
            }
        )
        regularActions.add("删除" to { deleteSingleSubtitle(position) })

        val itemsList = mutableListOf<String>()
        if (hasSelection) {
            itemsList.add("对勾选字幕操作 (${selectedCount}项)")
        }
        itemsList.addAll(regularActions.map { it.first })
        
        val items = itemsList.toTypedArray()
        
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (hasSelection && which == 0) {
                    // 用户选择了"只对勾选字幕生效"，显示针对选中项的操作菜单
                    showSelectionContextMenu(hasClipboard)
                } else {
                    val actualWhich = if (hasSelection) which - 1 else which
                    regularActions.getOrNull(actualWhich)?.second?.invoke()
                }
            }
            .show()
    }
    
    /**
     * 显示针对选中项的操作菜单
     */
    private fun showSelectionContextMenu(hasClipboard: Boolean) {
        if (!ensureListMode()) return
        
        val itemsList = mutableListOf<String>()
        itemsList.add("时间偏移")
        itemsList.add("AI 翻译")
        itemsList.add("复制")
        itemsList.add("剪切 (粘贴后删除)")
        if (hasClipboard) {
            itemsList.add("粘贴 (${clipboardTexts.size}项)")
        } else {
            itemsList.add("粘贴")
        }
        itemsList.add("删除选中")
        
        val items = itemsList.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("对勾选字幕操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showOffsetDialogForSelection()
                    1 -> showAiTranslate()
                    2 -> copySelected()
                    3 -> cutSelected()
                    4 -> if (hasClipboard) pasteToSelected() else {
                        ensureClipboardNotEmpty()
                    }
                    5 -> deleteSelectedSubtitles()
                }
            }
            .show()
    }
    
    /**
     * 复制单个字幕（长按的字幕）
     */
    private fun copySingle(position: Int) {
        if (isSourceViewMode) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            clipboardTexts = listOf(subtitleEntries[position].text)
            cutPasteController.clear()
            com.subtitleedit.util.OverwritingToast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 剪切单个字幕（长按的字幕）
     */
    private fun cutSingle(position: Int) {
        if (isSourceViewMode) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            // 先保存到剪贴板
            clipboardTexts = listOf(subtitleEntries[position].text)
            cutPasteController.markSingleCut(position)
            com.subtitleedit.util.OverwritingToast.makeText(this, "已剪切", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 剪切选中的字幕
     */
    private fun cutSelected() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要剪切的字幕") ?: return
        
        clipboardTexts = selectedEntries.map { it.first.text }
        cutPasteController.markMultiCut(selectedEntries.map { it.second })
        com.subtitleedit.util.OverwritingToast.makeText(this, "已剪切 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 执行剪切删除操作（在粘贴后调用）
     */
    private fun performCutDelete() {
        if (!cutPasteController.hasPendingCut()) return

        val deletedIndices = cutPasteController.snapshotDeletedIndices()
        val sortedPositions = cutPasteController.consumeDeletedIndicesDesc()
        sortedPositions.forEach { position ->
            if (position < subtitleEntries.size) {
                subtitleEntries.removeAt(position)
            }
        }
        syncAfterDelete(deletedIndices)
    }
    
    /**
     * 粘贴到指定位置（单行替换）
     */
    private fun pasteToPosition(position: Int) {
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return

        if (position >= 0 && position < subtitleEntries.size) {
            val targetSnapshot = SubtitleEntryOps.deepCopy(subtitleEntries[position])
            var targetPosition = position
            // 如果是剪切模式，先删除原字幕
            if (cutPasteController.hasPendingCut()) {
                targetPosition = cutPasteController.adjustPastePositionAfterCut(position)
                performCutDelete()
            }

            if (subtitleEntries.isEmpty()) {
                subtitleEntries.add(targetSnapshot)
                targetPosition = 0
            }
            targetPosition = targetPosition.coerceIn(0, subtitleEntries.lastIndex)

            val pasteResult = SubtitlePasteOps.pasteAtPosition(
                entries = subtitleEntries,
                position = targetPosition,
                clipboardTexts = clipboardTexts
            )
            if (pasteResult.structureChanged) {
                submitSubtitleList(refreshAll = true, markChanged = true)
                com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
            } else {
                notifyEntriesChanged(pasteResult.affectedPositions)
                com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 删除单个字幕（长按的字幕）
     */
    private fun deleteSingleSubtitle(position: Int) {
        if (!ensureListMode()) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            showDeleteConfirm("确定要删除此字幕吗？") {
                    subtitleEntries.removeAt(position)
                    syncAfterDelete(setOf(position))
                    com.subtitleedit.util.OverwritingToast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 插入字幕到指定位置
     */
    private fun insertSubtitle(
        after: Boolean,
        refPosition: Int,
        pasteAfterInsert: Boolean = false
    ) {
        if (!ensureListMode()) return
        if (refPosition !in subtitleEntries.indices) return
        if (pasteAfterInsert && !ensureClipboardNotEmpty()) return

        // 剪切粘贴会删除来源行，先保留参考行时间并修正插入位置。
        val refEntry = SubtitleEntryOps.deepCopy(subtitleEntries[refPosition])
        var insertPosition = if (after) refPosition + 1 else refPosition
        if (pasteAfterInsert && cutPasteController.hasPendingCut()) {
            insertPosition = cutPasteController.adjustPastePositionAfterCut(insertPosition)
            performCutDelete()
        }
        insertPosition = insertPosition.coerceIn(0, subtitleEntries.size)

        val insertedEntries = SubtitleEntryOps.createInsertedEntries(
            after = after,
            reference = refEntry,
            previous = subtitleEntries.getOrNull(insertPosition - 1),
            next = subtitleEntries.getOrNull(insertPosition),
            texts = if (pasteAfterInsert) clipboardTexts else listOf("新字幕")
        )
        insertedEntries.forEachIndexed { index, entry ->
            entry.index = insertPosition + index + 1
        }
        subtitleEntries.addAll(insertPosition, insertedEntries)
        submitSubtitleList(
            refreshAll = true,
            syncWaveform = false,
            markChanged = true
        ) {
            subtitleAdapter.syncSelectionWithCurrentList()
            updateSelectedCountDisplay()
        }
        setWaveformSubtitlesKeepSelection(insertPosition)
        val message = if (pasteAfterInsert) {
            "已${if (after) "向后" else "向前"}粘贴 ${clipboardTexts.size} 项"
        } else {
            "已插入新字幕"
        }
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun insertSubtitleFromTimestamp(startMs: Long, endMs: Long) {
        val realStart = minOf(startMs, endMs)
        val realEnd = maxOf(startMs, endMs)
        if (realEnd - realStart < 100) return

        val newEntry = SubtitleEntry().apply {
            this.startTime = realStart
            this.endTime = realEnd
            this.text = "新字幕"
        }
        val insertPos = subtitleEntries.indexOfFirst { it.startTime > realStart }
            .let { if (it == -1) subtitleEntries.size else it }

        subtitleEntries.add(insertPos, newEntry)
        submitSubtitleList(
            refreshAll = true,
            syncWaveform = false,
            markChanged = true
        )
        setWaveformSubtitlesKeepSelection(insertPos)
        com.subtitleedit.util.OverwritingToast.makeText(this, "已插入新字幕", Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示针对选中字幕的时间偏移对话框
     */
    private fun showOffsetDialogForSelection() {
        if (!ensureListMode()) return
        showOffsetInputDialog("时间偏移 (只对勾选字幕)") { totalOffset ->
            applyOffsetToSelection(totalOffset)
        }
    }
    
    /**
     * 对选中的字幕应用时间偏移
     */
    private fun applyOffsetToSelection(offsetMs: Long) {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("没有选中的字幕") ?: return
        
        // 保存选中的条目对象（用于同步选中状态）
        val selectedEntryObjects = selectedEntries.map { it.first }.toSet()
        
        // 应用时间偏移
        SubtitleEntryOps.applyOffsetAll(selectedEntryObjects, offsetMs)
        
        notifyEntriesChanged(selectedEntries.map { it.second })
        showShortToast("已对选中项应用 ${offsetMs}ms 偏移")
    }
    
    private fun showTimeEditDialog(entry: SubtitleEntry, position: Int, isStartTime: Boolean) {
        if (!ensureListMode()) return
        
        val currentTime = if (isStartTime) entry.startTime else entry.endTime
        val editText = EditText(this).apply {
            setText(TimeUtils.formatForInput(currentTime))
            inputType = EditorInfo.TYPE_CLASS_TEXT
            hint = "格式：00:00:01.500"
        }
        
        AlertDialog.Builder(this)
            .setTitle(if (isStartTime) "编辑开始时间" else "编辑结束时间")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newTime = TimeUtils.parseFromInput(editText.text.toString())
                if (newTime != null) {
                    if (isStartTime) {
                        entry.startTime = newTime
                    } else {
                        entry.endTime = newTime
                        // 用户修改了结束时间，设置标记
                        entry.endTimeModified = true
                    }
                    
                    onEntryUpdated(position)
                } else {
                    showShortToast("时间格式无效")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showTextEditDialog(entry: SubtitleEntry, position: Int) {
        if (!ensureListMode()) return
        
        val editText = EditText(this).apply {
            setText(entry.text)
            setLines(3)
        }
        
        AlertDialog.Builder(this)
            .setTitle("编辑字幕文本")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                entry.text = editText.text.toString()
                onEntryUpdated(position)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 复制选中的字幕（支持多行）
     */
    private fun copySelected() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要复制的字幕") ?: return
        
        clipboardTexts = selectedEntries.map { it.first.text }
        cutPasteController.clear()
        com.subtitleedit.util.OverwritingToast.makeText(this, "已复制 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 粘贴到选中的位置
     */
    private fun pasteToSelected() {
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要粘贴到的字幕") ?: return

        val selectedPositionsBeforeCut = selectedEntries.map { it.second }.sorted()
        if (clipboardTexts.size < selectedPositionsBeforeCut.size) {
            showShortToast("剪贴板行数不足：剪贴板 ${clipboardTexts.size} 行，当前选中 ${selectedPositionsBeforeCut.size} 行")
            return
        }
        var selectedPositions = selectedPositionsBeforeCut

        // 如果是剪切模式，先删除原字幕，并同步调整目标选中位置
        if (cutPasteController.hasPendingCut()) {
            val deletedIndices = cutPasteController.snapshotDeletedIndices()
            if (selectedPositionsBeforeCut.any { it in deletedIndices }) {
                showShortToast("剪切来源不能同时作为粘贴目标")
                return
            }
            selectedPositions = selectedPositionsBeforeCut
                .map { pos -> pos - deletedIndices.count { it < pos } }
                .filter { it >= 0 }
            performCutDelete()
        }

        if (selectedPositions.isEmpty()) {
            showShortToast("没有可粘贴到的目标位置")
            return
        }

        val pasteResult = SubtitlePasteOps.pasteToSelection(
            entries = subtitleEntries,
            selectedPositions = selectedPositions,
            clipboardTexts = clipboardTexts
        )

        submitSubtitleList(
            refreshAll = true,
            selectedIndices = pasteResult.affectedPositions,
            markChanged = true
        )
        com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    private fun markAsChanged() {
        hasUnsavedChanges = true
    }

    private fun onEntryUpdated(position: Int, message: String = "已更新") {
        notifyEntriesChanged(listOf(position))
        showShortToast(message)
    }

    private fun notifyEntriesChanged(
        positions: Iterable<Int>,
        includeNeighbors: Boolean = true,
        syncWaveform: Boolean = true,
        markChanged: Boolean = true
    ) {
        val positionList = positions.toList()
        if (includeNeighbors) {
            notifyPositionsWithNeighbors(positionList)
        } else {
            positionList
                .filter { it in subtitleEntries.indices }
                .distinct()
                .sorted()
                .forEach { subtitleAdapter.notifyItemChanged(it) }
        }
        if (syncWaveform) syncWaveformSubtitles()
        if (markChanged) markAsChanged()
        if (::searchController.isInitialized) searchController.onDocumentChanged()
    }

    private fun notifyPositionsWithNeighbors(positions: List<Int>) {
        if (positions.isEmpty()) return
        val allAffected = mutableSetOf<Int>()
        positions.forEach { pos ->
            if (pos in subtitleEntries.indices) {
                allAffected.add(pos)
            }
            val prev = pos - 1
            if (prev in subtitleEntries.indices) {
                allAffected.add(prev)
            }
            val next = pos + 1
            if (next in subtitleEntries.indices) {
                allAffected.add(next)
            }
        }
        allAffected.sorted().forEach { subtitleAdapter.notifyItemChanged(it) }
    }

    private fun syncWaveformSubtitles() {
        waveformController.setSubtitles(subtitleEntries.toList())
    }

    private fun setWaveformSubtitlesKeepSelection(selectedIndex: Int) {
        waveformController.setSubtitlesKeepSelection(subtitleEntries.toList(), selectedIndex)
    }

    private fun submitSubtitleList(
        refreshAll: Boolean = false,
        selectedIndices: Set<Int>? = null,
        clearSelection: Boolean = false,
        updateFormat: Boolean = true,
        syncWaveform: Boolean = true,
        markChanged: Boolean = false,
        afterSubmit: (() -> Unit)? = null
    ) {
        renumberEntries(force = refreshAll)
        subtitleAdapter.submitList(subtitleEntries.toList()) {
            if (clearSelection) {
                subtitleAdapter.clearSelection()
            }
            selectedIndices?.let { subtitleAdapter.setSelectionByIndices(it) }
            if (refreshAll) {
                subtitleAdapter.refreshAllItems()
            }
            updateSelectedCountDisplay()
            afterSubmit?.invoke()
        }
        if (updateFormat) updateFormatInfo()
        if (syncWaveform) syncWaveformSubtitles()
        if (markChanged) markAsChanged()
        if (::searchController.isInitialized) searchController.onDocumentChanged()
    }
    
    private fun newFile() {
        runAfterUnsavedChangesConfirmed(
            message = "当前文件有未保存的更改，确定要新建吗？",
            action = ::doNewFile
        )
    }
    
    private fun doNewFile() {
        filePath = ""
        currentFile = null
        isNewFile = true
        clearSubtitleEntries()
        // 添加默认字幕行：3秒时长，文本"请输入文本"
        subtitleEntries.add(SubtitleEntry(
            index = 1,
            startTime = 0L,
            endTime = 3000L,
            text = "请输入文本"
        ))
        sourceViewContent = ""
        originalFileContent = ""
        currentCharset = StandardCharsets.UTF_8
        currentFormat = SubtitleParser.SubtitleFormat.SRT
        isSourceViewMode = false
        binding.rvSubtitles.visibility = android.view.View.VISIBLE
        binding.svSourceView.visibility = android.view.View.GONE
        submitSubtitleList(refreshAll = true, clearSelection = true, syncWaveform = true)
        supportActionBar?.title = "未命名"
        currentFormatInfo = "格式：SRT | 条目数：${subtitleEntries.size}"
        supportActionBar?.subtitle = currentFormatInfo
        hasUnsavedChanges = false
        com.subtitleedit.util.OverwritingToast.makeText(this, "已新建文件", Toast.LENGTH_SHORT).show()
    }
    
    private fun openFile() {
        runAfterUnsavedChangesConfirmed(
            message = "当前文件有未保存的更改，确定要打开新文件吗？",
            action = ::doOpenFile
        )
    }
    
    private fun doOpenFile() {
        openFileLauncher.launch(arrayOf("text/*", "*/*"))
    }
    
    private fun saveFile() {
        // 确定要保存的目标文件
        val targetFile = if (isAudioFile) {
            // 音频文件模式：保存到字幕文件
            subtitleFile
        } else {
            // 普通模式：保存到当前文件
            currentFile
        }
        
        if (isNewFile || targetFile == null) {
            saveFileAs()
            return
        }

        saveWithContent { content ->
            FileUtils.writeFile(targetFile, content, currentCharset)
        }
    }
    
    private fun saveFileAs() {
        val formatExtension = if (isSourceViewMode) "txt" else getFormatExtension(currentFormat)
        saveFileLauncher.launch("subtitle.$formatExtension")
    }
    
    private fun saveFileToUri(uri: Uri) {
        saveWithContent { content ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray(currentCharset))
            }
        }
        isNewFile = false
    }
    
    private fun showEncodingDialog() {
        val encodings = FileUtils.SUPPORTED_ENCODINGS.map { it.displayName }
        val currentIndex = FileUtils.SUPPORTED_ENCODINGS.indexOfFirst { it.charset == currentCharset }
        
        AlertDialog.Builder(this)
            .setTitle("选择编码")
            .setSingleChoiceItems(encodings.toTypedArray(), currentIndex) { dialog, which ->
                val newCharset = FileUtils.SUPPORTED_ENCODINGS[which].charset
                if (newCharset != currentCharset) {
                    currentCharset = newCharset
                    reloadFile()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 保存草稿
     */
    private fun saveDraft() {
        val content = getCurrentEditableContent(requireNonEmptyList = true) ?: return
        
        val fileName = currentFile?.name ?: "未命名"
        val savedFileName = DraftManager.saveDraft(this, fileName, content)
        com.subtitleedit.util.OverwritingToast.makeText(this, "草稿已保存：$savedFileName", Toast.LENGTH_LONG).show()
    }
    
    /**
     * 打开草稿箱
     */
    private fun openDrafts() {
        val intent = Intent(this, DraftsActivity::class.java)
        intent.putExtra(DraftsActivity.EXTRA_FROM_EDITOR, true)
        draftLauncher.launch(intent)
    }
    
    private fun showOffsetDialog(longClickPos: Int = -1) {
        if (!ensureListMode()) return
        showOffsetInputDialog("时间偏移") { totalOffset ->
            applyOffset(totalOffset, longClickPos)
        }
    }

    private fun showOffsetInputDialog(
        title: String,
        onConfirm: (offsetMs: Long) -> Unit
    ) {
        val layout = createDialogInputContainer()
        val (msRow, etMs) = createLabeledNumberInputRow("毫秒", "毫秒", "0", allowSigned = true)
        val (secRow, etSec) = createLabeledNumberInputRow("秒", "秒", "0", allowSigned = true)
        val (minRow, etMin) = createLabeledNumberInputRow("分", "分", "0", allowSigned = true)
        val (hourRow, etHour) = createLabeledNumberInputRow("小时", "小时", "0", allowSigned = true)

        layout.addView(msRow)
        layout.addView(secRow)
        layout.addView(minRow)
        layout.addView(hourRow)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("输入偏移量，正数延迟，负数提前")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val ms = etMs.text.toString().toLongOrNull() ?: 0L
                val sec = etSec.text.toString().toLongOrNull() ?: 0L
                val min = etMin.text.toString().toLongOrNull() ?: 0L
                val hour = etHour.text.toString().toLongOrNull() ?: 0L
                onConfirm(ms + sec * 1000 + min * 60000 + hour * 3600000)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createDialogInputContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
    }

    private fun createLabeledNumberInputRow(
        hint: String,
        label: String,
        defaultValue: String,
        allowSigned: Boolean,
        labelPaddingStart: Int = 20
    ): Pair<LinearLayout, EditText> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val input = EditText(this).apply {
            this.hint = hint
            inputType = if (allowSigned) {
                EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            } else {
                EditorInfo.TYPE_CLASS_NUMBER
            }
            setText(defaultValue)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val text = TextView(this).apply {
            this.text = label
            setPadding(labelPaddingStart, 0, 0, 0)
        }
        row.addView(input)
        row.addView(text)
        return row to input
    }
    
    private fun applyOffset(offsetMs: Long, longClickPos: Int = -1) {
        if (!ensureListMode()) return

        when {
            // 有长按位置，对长按的那一行应用偏移（无论是否有选中状态）
            longClickPos >= 0 && longClickPos < subtitleEntries.size -> {
                val entry = subtitleEntries[longClickPos]
                SubtitleEntryOps.applyOffset(entry, offsetMs)
                
                notifyEntriesChanged(listOf(longClickPos))
            }
            // 没有长按位置但有选中的字幕，对选中的字幕应用偏移
            subtitleAdapter.getSelectedCount() > 0 -> {
                val selectedEntries = subtitleAdapter.getSelectedEntries()
                SubtitleEntryOps.applyOffsetAll(selectedEntries.map { it.first }, offsetMs)
                
                notifyEntriesChanged(selectedEntries.map { it.second })
            }
            // 都没有，对所有字幕应用偏移
            else -> {
                SubtitleEntryOps.applyOffsetAll(subtitleEntries, offsetMs)
                
                submitSubtitleList(refreshAll = true, markChanged = true)
            }
        }
        showShortToast("已应用 ${offsetMs}ms 偏移")
    }
    
    private fun deleteSelectedSubtitles() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要删除的字幕") ?: return
        
        showDeleteConfirm("确定要删除选中的字幕吗？") {
                val deletedIndices = selectedEntries.map { it.second }.toSet()
                // 从后往前删除，避免索引变化
                selectedEntries.sortedByDescending { it.second }.forEach { (_, position) ->
                    subtitleEntries.removeAt(position)
                }
                syncAfterDelete(deletedIndices)
                com.subtitleedit.util.OverwritingToast.makeText(this, "已删除 ${selectedEntries.size} 条字幕", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 删除字幕后同步状态（保持未删除项的选中状态）
     * @param deletedIndices 被删除的索引集合（删除前的索引）
     */
    private fun syncAfterDelete(deletedIndices: Set<Int>) {
        // 保存删除前的所有选中索引
        val allSelectedIndices = subtitleAdapter.getSelectedPositions()

        // 计算删除后应该保持选中的索引（未被删除的选中项）
        val remainingSelectedIndices = mutableSetOf<Int>()
        allSelectedIndices.forEach { idx ->
            if (idx !in deletedIndices) {
                // 计算有多少个被删除的索引在当前索引之前
                val offset = deletedIndices.count { it < idx }
                remainingSelectedIndices.add(idx - offset)
            }
        }

        submitSubtitleList(
            refreshAll = true,
            selectedIndices = remainingSelectedIndices,
            syncWaveform = false,
            markChanged = true
        ) {
            // 刷新被删除行的前一行（消除时间冲突标红）
            deletedIndices.forEach { deletedIdx ->
                val offset = deletedIndices.count { it < deletedIdx }
                val prevIdx = (deletedIdx - offset) - 1
                if (prevIdx >= 0 && prevIdx < subtitleEntries.size) {
                    subtitleAdapter.notifyItemChanged(prevIdx)
                }
            }
        }
        // 同步字幕到波形视图，保持选中状态
        waveformController.setSubtitlesAfterDelete(subtitleEntries.toList(), deletedIndices)
    }

    /**
     * 取消所有选择的字幕
     */
    private fun cancelSelection() {
        if (!ensureListMode()) return
        
        subtitleAdapter.clearSelection()
        updateSelectedCountDisplay()
    }

    private fun selectAllSubtitles() {
        if (!ensureListMode() || subtitleEntries.isEmpty()) return

        if (subtitleAdapter.getSelectedCount() == subtitleEntries.size) {
            subtitleAdapter.clearSelection()
        } else {
            subtitleAdapter.setSelectionByIndices(subtitleEntries.indices.toSet())
        }
        updateSelectedCountDisplay()
    }

    private fun selectRangeBetweenSelectedSubtitles() {
        if (!ensureListMode()) return

        val selectedPositions = subtitleAdapter.getSelectedPositions().sorted()
        if (selectedPositions.size < 2) {
            showShortToast("请先选择至少两行字幕")
            return
        }

        val start = selectedPositions.first()
        val end = selectedPositions.last()
        val range = (start..end).toSet()
        if (range.all { it in selectedPositions }) return

        subtitleAdapter.setSelectionByIndices(range)
        updateSelectedCountDisplay()
    }
    
    /**
     * 显示 AI 翻译对话框
     */
    private fun showAiTranslate() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要翻译的字幕") ?: return
        
        // 检查 API 设置
        val settingsManager = SettingsManager.getInstance(this)
        val provider = settingsManager.getAiProvider()
        val providerName = AiProviderConfig.getProvider(provider).displayName
        val apiKey = settingsManager.getAiApiKey()
        if (apiKey.isEmpty()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请先在设置中配置 $providerName API Key", Toast.LENGTH_LONG).show()
            return
        }
        
        val model = settingsManager.getAiModel()
        val sourceLanguage = settingsManager.getAiSourceLanguage()
        val targetLanguage = settingsManager.getAiTargetLanguage()
        val customPrompt = settingsManager.getAiTranslationPrompt()
        
        // 显示翻译确认对话框
        val sourceLangText = if (sourceLanguage == "自动检测") "自动检测" else sourceLanguage
        AlertDialog.Builder(this)
            .setTitle("AI 翻译")
            .setMessage("将使用 $providerName / $model 翻译选中的 ${selectedEntries.size} 条字幕\n源语言：$sourceLangText\n目标语言：$targetLanguage\n\n点击「开始翻译」继续")
            .setPositiveButton("开始翻译") { _, _ ->
                startTranslation(selectedEntries, provider, apiKey, model, sourceLanguage, targetLanguage, customPrompt)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 开始翻译
     */
    private fun startTranslation(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        provider: String,
        apiKey: String,
        model: String,
        sourceLanguage: String,
        targetLanguage: String,
        customPrompt: String
    ) {
        // 显示翻译进度对话框
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在翻译")
            .setMessage("正在翻译第 0/${selectedEntries.size} 条...")
            .setNegativeButton("取消") { _, _ ->
                translateCancelled = true
                activeAiTranslator?.cancel()
            }
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        translateCancelled = false
        isTranslating = true
        
        val aiTranslator = AiTranslator(provider, apiKey, model, sourceLanguage, targetLanguage, customPrompt)
        activeAiTranslator = aiTranslator
        val textsToTranslate = selectedEntries.map { it.first.text }
        
        translateJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = aiTranslator.translateTexts(
                    texts = textsToTranslate,
                    progressCallback = { current, total ->
                        runOnUiThread {
                            progressDialog.setMessage("正在翻译第 $current/$total 条...")
                        }
                    },
                    isCancelled = { translateCancelled }
                )
                
                finishTranslation(progressDialog)
                
                if (result.isSuccess) {
                    val translatedTexts = result.getOrNull() ?: emptyList()
                    showTranslationResult(selectedEntries, translatedTexts)
                } else {
                    val errorMessage = result.exceptionOrNull()?.message ?: "未知错误"
                    showTranslationError(errorMessage)
                }
            } catch (e: Exception) {
                finishTranslation(progressDialog)
                showTranslationError(e.message ?: "未知错误")
            }
        }
    }
    
    /**
     * 显示翻译结果预览
     */
    private fun showTranslationResult(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        translatedTexts: List<String>
    ) {
        if (translatedTexts.size != selectedEntries.size) {
            showShortToast("翻译结果数量不匹配")
            return
        }
        
        val previewItems = selectedEntries.mapIndexed { index, (entry, position) ->
            TranslationPreviewItem(position, entry.text, translatedTexts[index])
        }
        val recyclerView = DraggableRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = TranslationPreviewAdapter(previewItems) { item, onUpdated ->
                showTranslationTextEditDialog(item, onUpdated)
            }
            setPadding(16, 8, 16, 8)
            clipToPadding = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            post { showDragThumb() }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("翻译结果预览")
            .setView(recyclerView)
            .setPositiveButton("应用") { _, _ ->
                val appliedItems = previewItems.filter { it.apply }
                appliedItems.forEach { item ->
                    subtitleEntries.getOrNull(item.entryPosition)?.text = item.translatedText
                }
                if (appliedItems.isNotEmpty()) {
                    notifyEntriesChanged(appliedItems.map { it.entryPosition }, includeNeighbors = false)
                }
                showShortToast("已应用 ${appliedItems.size} 条翻译")
            }
            .setNeutralButton("保存草稿") { _, _ -> saveTranslationDraft(previewItems) }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                setLayout(
                (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                (resources.displayMetrics.heightPixels * 0.82f).toInt()
                )
            }
        }
        dialog.show()
    }

    private fun showTranslationTextEditDialog(
        previewItem: TranslationPreviewItem,
        onUpdated: () -> Unit,
        title: String = "编辑翻译文本"
    ) {
        val editText = EditText(this).apply {
            setText(previewItem.translatedText)
            setSelection(text.length)
            setLines(3)
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                previewItem.translatedText = editText.text?.toString().orEmpty()
                onUpdated()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            editText.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            editText.post {
                val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

    private fun saveTranslationDraft(previewItems: List<TranslationPreviewItem>) {
        val draftEntries = subtitleEntries.map { it.copy() }.toMutableList()
        previewItems.filter { it.apply }.forEach { item ->
            draftEntries.getOrNull(item.entryPosition)?.text = item.translatedText
        }
        val fileName = currentFile?.name ?: "未命名"
        val draftContent = serializeEntriesForFormat(currentFormat, draftEntries)
        val savedFileName = DraftManager.saveDraft(this, fileName, draftContent)
        showShortToast("翻译草稿已保存：$savedFileName")
    }

    private fun finishTranslation(progressDialog: AlertDialog) {
        if (progressDialog.isShowing) {
            progressDialog.dismiss()
        }
        isTranslating = false
        translateJob = null
        activeAiTranslator = null
    }

    private fun showTranslationError(message: String) {
        com.subtitleedit.util.OverwritingToast.makeText(this, "翻译失败：$message", Toast.LENGTH_LONG).show()
    }

    /** 对当前选中的字幕行按各自时间范围执行离线语音转录。 */
    private fun showQuickTranscribe() {
        if (!ensureListMode()) return
        if (!isAudioFile || currentFile == null) {
            showShortToast("仅在打开音频文件时可快速转录")
            return
        }
        val selectedEntries = requireSelectedEntries("请先选择要转录的字幕") ?: return
        if (selectedEntries.any { it.first.endTime <= it.first.startTime }) {
            showShortToast("选中的字幕包含无效时间范围")
            return
        }

        val settings = SettingsManager.getInstance(this)
        val modelType = settings.getAsrModelType()
        val sourceLanguage = settings.getQuickTranscribeSourceLanguage()
        val encoderPath: String
        val decoderPath: String
        val tokensPath: String
        if (modelType == SettingsManager.ASR_MODEL_SENSEVOICE) {
            encoderPath = settings.getSenseVoiceModelPath()
            decoderPath = ""
            tokensPath = settings.getSenseVoiceTokensPath()
        } else {
            encoderPath = settings.getWhisperEncoderPath()
            decoderPath = settings.getWhisperDecoderPath()
            tokensPath = settings.getWhisperTokensPath()
        }
        if (encoderPath.isBlank() || tokensPath.isBlank() ||
            (modelType == SettingsManager.ASR_MODEL_WHISPER && decoderPath.isBlank())
        ) {
            showShortToast("请先在语音转字幕配置中设置识别模型")
            return
        }

        val (dialogView, languageSpinner) =
            createQuickTranscribeLanguageView(selectedEntries.size, sourceLanguage)
        AlertDialog.Builder(this)
            .setTitle("快速转录")
            .setView(dialogView)
            .setPositiveButton("开始转录") { _, _ ->
                val selectedLanguage = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS[
                    languageSpinner.selectedItemPosition.coerceIn(
                        0,
                        SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.lastIndex
                    )
                ]
                settings.setQuickTranscribeSourceLanguage(selectedLanguage)
                startQuickTranscription(
                    selectedEntries,
                    encoderPath,
                    decoderPath,
                    tokensPath,
                    modelType,
                    selectedLanguage
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createQuickTranscribeLanguageView(
        selectedCount: Int,
        sourceLanguage: String
    ): Pair<LinearLayout, android.widget.Spinner> {
        val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val summary = TextView(this).apply {
            text = "将识别选中的 $selectedCount 条字幕对应音频，并在预览中确认后应用。"
            textSize = 14f
        }
        val label = TextView(this).apply {
            text = "源语言"
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        val spinner = android.widget.Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@EditorActivity,
                android.R.layout.simple_spinner_item,
                SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            setSelection(
                SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.indexOf(sourceLanguage).coerceAtLeast(0)
            )
        }
        container.addView(summary)
        container.addView(label)
        container.addView(spinner)
        return container to spinner
    }

    private fun startQuickTranscription(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        encoderPath: String,
        decoderPath: String,
        tokensPath: String,
        modelType: String,
        sourceLanguage: String
    ) {
        val inputFile = currentFile ?: return
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在转录")
            .setMessage("正在准备音频...")
            .setNegativeButton("取消") { _, _ -> transcribeCancelled = true }
            .setCancelable(false)
            .create()
        progressDialog.show()
        transcribeCancelled = false

        transcribeJob = lifecycleScope.launch {
            try {
                val cachedPcmFile = recognitionPcmCacheFile(inputFile)
                progressDialog.setMessage(
                    if (isRecognitionPcmCacheValid(cachedPcmFile)) "正在使用缓存音频..." else "正在准备音频..."
                )
                val pcmFile = withContext(Dispatchers.IO) { convertAudioToRecognitionPcm(inputFile) }
                    ?: throw IllegalStateException("音频转换失败")
                if (transcribeCancelled) return@launch

                val recognizer = WhisperRecognizer(
                    encoderPath = encoderPath,
                    decoderPath = decoderPath,
                    tokensPath = tokensPath,
                    useVad = false,
                    language = sourceLanguage,
                    contentResolver = contentResolver,
                    context = this@EditorActivity,
                    modelType = modelType
                )
                val ranges = selectedEntries.map { it.first.startTime..it.first.endTime }
                val result = withContext(Dispatchers.IO) {
                    recognizer.recognizeRanges(
                        audioFile = pcmFile,
                        ranges = ranges,
                        progressCallback = { current, total ->
                            runOnUiThread {
                                progressDialog.setMessage("正在转录第 $current/$total 条...")
                            }
                        },
                        isCancelled = { transcribeCancelled }
                    )
                }
                if (transcribeCancelled) return@launch

                progressDialog.dismiss()
                result.onSuccess { texts -> showTranscriptionResult(selectedEntries, texts) }
                    .onFailure { showShortToast("转录失败：${it.message ?: "未知错误"}") }
            } catch (e: Exception) {
                if (!transcribeCancelled) showShortToast("转录失败：${e.message ?: "未知错误"}")
            } finally {
                if (progressDialog.isShowing) progressDialog.dismiss()
                transcribeJob = null
            }
        }
    }

    /**
     * 缓存键包含源文件元数据。源文件变化时会自然切换到新的缓存，不会读取旧音频。
     */
    private fun recognitionPcmCacheFile(inputFile: File): File {
        val sourceKey = "${inputFile.absolutePath.hashCode().toUInt().toString(16)}_" +
            "${inputFile.length()}_${inputFile.lastModified()}"
        return File(cacheDir, "quick_transcribe_${sourceKey}_16k.wav")
    }

    private fun isRecognitionPcmCacheValid(file: File): Boolean = file.exists() && file.length() > 44L

    private fun convertAudioToRecognitionPcm(inputFile: File): File? {
        return try {
            val outputFile = recognitionPcmCacheFile(inputFile)
            if (isRecognitionPcmCacheValid(outputFile)) return outputFile
            if (outputFile.exists()) outputFile.delete()
            val command = "-y -i \"${inputFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${outputFile.absolutePath}\""
            val session = FFmpegKit.execute(command)
            outputFile.takeIf { session.getReturnCode()?.isValueSuccess() == true && isRecognitionPcmCacheValid(it) }
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "快速转录音频转换失败", e)
            null
        }
    }

    private fun showTranscriptionResult(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        transcribedTexts: List<String>
    ) {
        if (transcribedTexts.size != selectedEntries.size) {
            showShortToast("转录结果数量不匹配")
            return
        }
        val previewItems = selectedEntries.mapIndexed { index, (entry, position) ->
            TranslationPreviewItem(position, entry.text, transcribedTexts[index])
        }
        val recyclerView = DraggableRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = TranslationPreviewAdapter(previewItems) { item, onUpdated ->
                showTranslationTextEditDialog(item, onUpdated, "编辑转录文本")
            }
            setPadding(16, 8, 16, 8)
            clipToPadding = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            post { showDragThumb() }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("转录结果预览")
            .setView(recyclerView)
            .setPositiveButton("应用") { _, _ ->
                val appliedItems = previewItems.filter { it.apply }
                appliedItems.forEach { item ->
                    subtitleEntries.getOrNull(item.entryPosition)?.text = item.translatedText
                }
                if (appliedItems.isNotEmpty()) {
                    notifyEntriesChanged(appliedItems.map { it.entryPosition }, includeNeighbors = false)
                }
                showShortToast("已应用 ${appliedItems.size} 条转录")
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                    (resources.displayMetrics.heightPixels * 0.82f).toInt()
                )
            }
        }
        dialog.show()
    }
    
    private fun renumberEntries(force: Boolean = false) {
        val currentCount = subtitleEntries.size
        if (!force && currentCount == lastIndexedEntryCount) return
        subtitleEntries.forEachIndexed { index, entry ->
            entry.index = index + 1
        }
        lastIndexedEntryCount = currentCount
    }

    private fun setSubtitleEntries(entries: List<SubtitleEntry>) {
        subtitleEntries = entries.toMutableList()
        renumberEntries(force = true)
    }

    private fun clearSubtitleEntries() {
        subtitleEntries.clear()
        renumberEntries(force = true)
    }
    
    private fun updateFormatInfo() {
        val formatName = getFormatDisplayName(currentFormat)
        val countInfo = if (isSourceViewMode) {
            val lines = sourceViewContent.lines().size
            "行数：$lines"
        } else {
            "条目数：${subtitleEntries.size}"
        }
        currentFormatInfo = "格式：$formatName | $countInfo"
        supportActionBar?.subtitle = currentFormatInfo
    }

    private fun getFormatDisplayName(format: SubtitleParser.SubtitleFormat): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> "SRT"
            SubtitleParser.SubtitleFormat.LRC -> "LRC"
            SubtitleParser.SubtitleFormat.TXT -> "TXT"
            else -> "未知"
        }
    }

    private fun getFormatExtension(format: SubtitleParser.SubtitleFormat): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> "srt"
            SubtitleParser.SubtitleFormat.LRC -> "lrc"
            SubtitleParser.SubtitleFormat.TXT -> "txt"
            else -> "srt"
        }
    }

    private fun serializeEntriesForFormat(format: SubtitleParser.SubtitleFormat): String {
        return serializeEntriesForFormat(format, subtitleEntries)
    }

    private fun serializeEntriesForFormat(
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>
    ): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(entries)
            SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(entries)
            SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(entries)
            else -> SubtitleParser.toSRT(entries)
        }
    }

    private fun getCurrentEditableContent(requireNonEmptyList: Boolean = false): String? {
        if (isSourceViewMode) return sourceViewContent
        if (requireNonEmptyList && subtitleEntries.isEmpty()) {
            showShortToast("没有内容可保存")
            return null
        }
        return serializeEntriesForFormat(currentFormat)
    }

    private fun ensureListMode(): Boolean {
        if (!isSourceViewMode) return true
        showShortToast("源视图模式下不支持此操作")
        return false
    }

    private fun ensureAudioMode(): Boolean {
        if (isAudioFile) return true
        showShortToast("此功能仅在打开音频文件时可用")
        return false
    }

    private fun ensureClipboardNotEmpty(): Boolean {
        if (clipboardTexts.isNotEmpty()) return true
        showShortToast("剪贴板为空，请先复制")
        return false
    }

    private fun requireSelectedEntries(emptyMessage: String): List<Pair<SubtitleEntry, Int>>? {
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            showShortToast(emptyMessage)
            return null
        }
        return selectedEntries
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(negativeText, null)
            .show()
    }

    private fun showUnsavedChangesConfirm(message: String, onConfirm: () -> Unit) {
        showConfirmDialog(
            title = "提示",
            message = message,
            onConfirm = onConfirm
        )
    }

    private fun runAfterUnsavedChangesConfirmed(
        message: String,
        action: () -> Unit
    ) {
        if (!hasUnsavedChanges) {
            action()
            return
        }
        showUnsavedChangesConfirm(message, action)
    }

    private fun showReplaceAllConfirm(count: Int, onConfirm: () -> Unit) {
        showConfirmDialog(
            title = "确认替换",
            message = "确定要全部替换吗？共找到 $count 处匹配项。",
            onConfirm = onConfirm
        )
    }

    private fun showDeleteConfirm(message: String, onConfirm: () -> Unit) {
        showConfirmDialog(
            title = "删除",
            message = message,
            onConfirm = onConfirm
        )
    }

    private fun getCurrentSubtitleFile(): File? {
        return if (isAudioFile) subtitleFile else currentFile
    }

    private fun isSourceContentModifiedComparedToFile(editedContent: String): Boolean {
        val file = getCurrentSubtitleFile() ?: return true
        return editedContent != FileUtils.readFile(file, currentCharset)
    }

    private fun readFileOrNull(file: File, failurePrefix: String): String? {
        return try {
            FileUtils.readFile(file, currentCharset)
        } catch (e: Exception) {
            showShortToast("$failurePrefix：${e.message}")
            null
        }
    }

    private fun finishWithToast(message: String) {
        showShortToast(message)
        finish()
    }

    private inline fun saveWithContent(writeAction: (String) -> Unit) {
        try {
            val content = getCurrentEditableContent() ?: return
            writeAction(content)
            hasUnsavedChanges = false
            showShortToast("保存成功")
        } catch (e: Exception) {
            showShortToast("保存失败：${e.message}")
        }
    }

    private fun showShortToast(message: String) {
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
    }

    private fun handleBackPressed() {
        if (::subtitleAdapter.isInitialized && subtitleAdapter.getSelectedCount() > 0) {
            cancelSelection()
            return
        }
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("是否保存更改？")
                .setPositiveButton("保存") { _, _ ->
                    saveFile()
                    finish()
                }
                .setNegativeButton("不保存") { _, _ ->
                    finish()
                }
                .setNeutralButton("取消", null)
                .show()
        } else {
            finish()
        }
    }
    
    override fun onDestroy() {
        ttsGeneration++
        pendingTtsTexts = emptyList()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
        audioFilePreparer.release()
        playbackController.release()
        waveformController.release()
        if (isTranslating) {
            translateCancelled = true
            activeAiTranslator?.cancel()
            translateJob?.cancel()
        }
        transcribeCancelled = true
        transcribeJob?.cancel()
    }
    
    // ==================== 音频播放器相关方法 ====================
    
    private fun setupAudioActions() {
        if (!isAudioFile) return

        binding.btnQuickTranscribe.setOnClickListener {
            showQuickTranscribe()
        }
        binding.btnQuickTranscribe.setOnLongClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
            true
        }

        binding.btnQuickTts.setOnClickListener {
            showQuickTts()
        }
        binding.btnQuickTts.setOnLongClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
            true
        }
    }

    /** 使用设置中选定的系统 TTS 引擎，按字幕顺序朗读当前勾选项。 */
    private fun showQuickTts() {
        if (!ensureListMode()) return
        val selectedEntries = requireSelectedEntries("请先选择要朗读的字幕") ?: return
        val texts = selectedEntries.map { it.first.text.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) {
            showShortToast("选中的字幕没有可朗读文本")
            return
        }

        val settings = SettingsManager.getInstance(this)
        val requestedEngine = settings.getTtsEngine()
        val requestedLanguage = settings.getTtsLanguage()
        if (ttsReady && activeTtsEnginePreference == requestedEngine &&
            activeTtsLanguagePreference == requestedLanguage
        ) {
            speakSubtitleTexts(texts, requestedLanguage)
            return
        }

        pendingTtsTexts = texts
        if (ttsInitializing && activeTtsEnginePreference == requestedEngine &&
            activeTtsLanguagePreference == requestedLanguage
        ) return
        initializeTts(requestedEngine, requestedLanguage)
    }

    private fun initializeTts(requestedEngine: String, requestedLanguage: String) {
        if (requestedEngine.isNotBlank() && !isTtsEngineInstalled(requestedEngine)) {
            ttsGeneration++
            pendingTtsTexts = emptyList()
            textToSpeech?.shutdown()
            textToSpeech = null
            ttsReady = false
            ttsInitializing = false
            activeTtsEnginePreference = null
            activeTtsLanguagePreference = null
            showShortToast("所选 TTS 引擎已不可用，请在设置中重新选择")
            return
        }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        ttsInitializing = true
        activeTtsEnginePreference = requestedEngine
        activeTtsLanguagePreference = requestedLanguage
        ttsDefaultLocale = null
        val generation = ++ttsGeneration

        val listener = TextToSpeech.OnInitListener { status ->
            // post 确保构造函数已返回且 textToSpeech 字段已经完成赋值。
            binding.root.post {
                if (generation != ttsGeneration || isDestroyed) return@post
                ttsInitializing = false
                // defaultEngine 表示系统默认引擎，并不表示构造函数指定的当前引擎。
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    ttsDefaultLocale = textToSpeech?.voice?.locale
                    val texts = pendingTtsTexts
                    pendingTtsTexts = emptyList()
                    speakSubtitleTexts(texts, requestedLanguage)
                } else {
                    pendingTtsTexts = emptyList()
                    textToSpeech?.shutdown()
                    textToSpeech = null
                    activeTtsEnginePreference = null
                    activeTtsLanguagePreference = null
                    showShortToast(
                        if (requestedEngine.isBlank()) "系统默认 TTS 引擎初始化失败"
                        else "所选 TTS 引擎不可用，请在设置中重新选择"
                    )
                }
            }
        }

        textToSpeech = if (requestedEngine.isBlank()) {
            TextToSpeech(this, listener)
        } else {
            TextToSpeech(this, listener, requestedEngine)
        }
    }

    @Suppress("DEPRECATION")
    private fun isTtsEngineInstalled(packageName: String): Boolean {
        return packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            android.content.pm.PackageManager.MATCH_ALL
        ).any { it.serviceInfo?.packageName == packageName }
    }

    private fun speakSubtitleTexts(texts: List<String>, languagePreference: String) {
        val tts = textToSpeech ?: return
        val maxLength = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)

        tts.stop()
        val utterancePrefix = "quick_tts_${System.currentTimeMillis()}"
        var utteranceIndex = 0
        texts.forEach { subtitleText ->
            val locale = resolveTtsLocale(subtitleText, languagePreference) ?: ttsDefaultLocale
            if (locale != null) {
                val availability = tts.setLanguage(locale)
                if (availability == TextToSpeech.LANG_MISSING_DATA ||
                    availability == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    showShortToast("所选 TTS 引擎不支持 ${locale.displayLanguage}，请安装对应语音包")
                    tts.stop()
                    return
                }
            }

            subtitleText.chunked(maxLength).forEach { chunk ->
                val result = tts.speak(
                    chunk,
                    if (utteranceIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    "${utterancePrefix}_${utteranceIndex++}"
                )
                if (result == TextToSpeech.ERROR) {
                    showShortToast("TTS 朗读启动失败")
                    tts.stop()
                    return
                }
            }
        }
    }

    private fun resolveTtsLocale(text: String, preference: String): Locale? {
        return when (preference) {
            SettingsManager.TTS_LANGUAGE_JAPANESE -> Locale.JAPAN
            SettingsManager.TTS_LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
            SettingsManager.TTS_LANGUAGE_ENGLISH -> Locale.US
            SettingsManager.TTS_LANGUAGE_AUTO -> when {
                // 假名可以可靠地区分日语；纯汉字在中日文之间本身具有歧义。
                text.any { it in '\u3040'..'\u30ff' || it in '\uff66'..'\uff9f' } -> Locale.JAPAN
                text.any { it in '\u3400'..'\u9fff' } -> {
                    if (Locale.getDefault().language == Locale.JAPANESE.language) Locale.JAPAN
                    else Locale.SIMPLIFIED_CHINESE
                }
                text.any { it in 'A'..'Z' || it in 'a'..'z' } -> Locale.US
                else -> null
            }
            else -> null
        }
    }

    /**
     * 加载音频文件
     */
    private fun loadAudioFile(subtitleFilePath: String?) {
        if (filePath.isEmpty() || currentFile == null) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "音频文件路径无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        if (!currentFile!!.exists()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "音频文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // 先显示检测中提示，再异步检测 start time
        val checkingDialog = android.app.AlertDialog.Builder(this)
            .setMessage("正在检测音频文件...")
            .setCancelable(false)
            .create()
        checkingDialog.show()
        
        lifecycleScope.launch {
            val originalFile = currentFile!!
            val preparedAudio = audioFilePreparer.prepare(originalFile)
            
            checkingDialog.dismiss()
            
            if (preparedAudio.wasFixed) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this@EditorActivity,
                    "检测到音频 start time 不为 0,请注意处理,已临时修复，正在加载...",
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // 使用修复后的文件路径继续加载
            doLoadAudioFile(preparedAudio.file, subtitleFilePath)
        }
    }
    
    /**
     * 实际执行音频加载（原 loadAudioFile 的主体逻辑）
     */
    private fun doLoadAudioFile(audioFile: File, subtitleFilePath: String?) {
        supportActionBar?.title = subtitleFilePath?.let { File(it).name } ?: "（无字幕文件）"
        
        try {
            playbackController.prepare(audioFile)
        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "加载音频失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        
        if (subtitleFilePath != null) {
            val subtitleFile = File(subtitleFilePath)
            if (subtitleFile.exists()) {
                loadSubtitleFile(subtitleFile)
            } else {
                clearSubtitleEntries()
                submitSubtitleList(refreshAll = true, syncWaveform = false)
                com.subtitleedit.util.OverwritingToast.makeText(this, "未找到同名字幕文件", Toast.LENGTH_SHORT).show()
            }
        } else {
            clearSubtitleEntries()
            submitSubtitleList(refreshAll = true, syncWaveform = false)
        }
        
        waveformController.load(
            audioFile,
            playbackController.durationMs,
            subtitleEntries.toList()
        )
    }

    /**
     * 加载字幕文件
     */
    private fun loadSubtitleFile(subtitleFile: File) {
        val settingsManager = SettingsManager.getInstance(this)
        currentCharset = settingsManager.getDefaultEncoding()
        
        try {
            val content = FileUtils.readFile(subtitleFile, currentCharset)
            parseContent(content)
            hasUnsavedChanges = false
            isNewFile = false
        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "读取字幕文件失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== 字幕时间控制按钮方法 ====================
    
    /**
     * 跳转到字幕的开始时间
     */
    private fun jumpToSubtitleTime(entry: SubtitleEntry) {
        if (!ensureAudioMode()) return
        
        playbackController.seekTo(entry.startTime)
        showShortToast("已跳转到 ${TimeUtils.formatForDisplay(entry.startTime)}")
    }
    
    
    
    /**
     * 将字幕的开始时间设置为当前音频进度
     */
    private fun setSubtitleTimeToCurrentPosition(entry: SubtitleEntry, position: Int) {
        if (!ensureAudioMode()) return
        
        val newStartTime = playbackController.currentPositionMs
        entry.startTime = newStartTime
        
        notifyEntriesChanged(listOf(position))
        
        if (newStartTime >= entry.endTime) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "开始时间已设置，但大于结束时间，请调整结束时间", Toast.LENGTH_LONG).show()
        } else {
            showShortToast("已将开始时间设置为 ${TimeUtils.formatForDisplay(newStartTime)}")
        }
    }
    
}

