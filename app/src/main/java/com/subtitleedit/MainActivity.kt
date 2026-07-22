package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.adapter.FileListAdapter
import com.subtitleedit.databinding.ActivityMainBinding
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.UpdateChecker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 - 文件浏览器
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val MENU_SELECT_ALL = 0x10001
        const val MENU_SELECT_RANGE = 0x10002
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileListAdapter
    
    private var currentDirectory: File? = null
    private val directoryHistory = mutableListOf<File>()
    private val visibleFiles = mutableListOf<File>()
    private val selectedPaths = linkedSetOf<String>()
    private var pendingFileOperation: FileOperation? = null
    private var updateCheckStarted = false
    private var pendingUpdate: UpdateChecker.UpdateInfo? = null
    private var updateDialogShown = false
    private var showAllFileTypes = false
    private var showHiddenFiles = false

    private enum class FileOperation { COPY, MOVE }
    
    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            loadDirectory(getDefaultDirectory())
        } else {
            showPermissionDeniedDialog()
        }
    }
    
    // 管理外部存储权限请求
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            loadDirectory(getDefaultDirectory())
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settingsManager = com.subtitleedit.util.SettingsManager.getInstance(this)
        AppCompatDelegate.setDefaultNightMode(
            when (settingsManager.getThemeMode()) {
                com.subtitleedit.util.SettingsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                com.subtitleedit.util.SettingsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showAllFileTypes = settingsManager.isShowAllFileTypesEnabled()
        showHiddenFiles = settingsManager.isShowHiddenFilesEnabled()
        
        setupToolbar()
        setupRecyclerView()
        setupButtons()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        val newShowAllFileTypes = SettingsManager.getInstance(this).isShowAllFileTypesEnabled()
        val newShowHiddenFiles = SettingsManager.getInstance(this).isShowHiddenFilesEnabled()
        if (newShowAllFileTypes != showAllFileTypes || newShowHiddenFiles != showHiddenFiles) {
            showAllFileTypes = newShowAllFileTypes
            showHiddenFiles = newShowHiddenFiles
            currentDirectory?.let(::loadDirectory)
        }
        pendingUpdate?.let(::showPendingUpdate)
        if (!updateCheckStarted && SettingsManager.getInstance(this).shouldCheckUpdatesOnStartup()) {
            updateCheckStarted = true
            lifecycleScope.launch {
                val update = UpdateChecker.check(this@MainActivity) ?: return@launch
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    showPendingUpdate(update)
                } else {
                    pendingUpdate = update
                }
            }
        }
    }

    private fun showPendingUpdate(update: UpdateChecker.UpdateInfo) {
        pendingUpdate = null
        if (updateDialogShown || isFinishing || isDestroyed) return
        updateDialogShown = true
        UpdateChecker.showUpdateDialog(this, update)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        if (selectedPaths.isNotEmpty()) {
            if (pendingFileOperation == null) {
                menu.add(Menu.NONE, MENU_SELECT_ALL, 0, "全选")
                    .setIcon(R.drawable.ic_select_all)
                    .setContentDescription("全选")
                    .setTooltipText("全选")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, MENU_SELECT_RANGE, 1, "局部全选")
                    .setIcon(R.drawable.ic_select_range)
                    .setContentDescription("局部全选")
                    .setTooltipText("局部全选")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        } else {
            menuInflater.inflate(R.menu.menu_main, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SELECT_ALL -> {
            selectAllVisibleFiles()
            true
        }
        MENU_SELECT_RANGE -> {
            selectRangeBetweenSelectedFiles()
            true
        }
        R.id.menu_tools -> {
            startActivity(Intent(this, ToolsActivity::class.java))
            true
        }
        R.id.menu_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
    
    private fun setupRecyclerView() {
        fileAdapter = FileListAdapter(
            onItemClick = ::onFileClicked,
            onItemLongClick = ::enterSelectionMode
        )
        
        binding.rvFileList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
        }
    }
    
    private fun setupButtons() {
        binding.btnUpLevel.setOnClickListener {
            goUpLevel()
        }
        
        binding.btnDrafts.setOnClickListener {
            openDrafts()
        }

        binding.btnCopySelected.setOnClickListener { startDestinationSelection(FileOperation.COPY) }
        binding.btnMoveSelected.setOnClickListener { startDestinationSelection(FileOperation.MOVE) }
        binding.btnRenameSelected.setOnClickListener { renameSelectedFile() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelectedFiles() }
        binding.btnMoreSelected.setOnClickListener { showMoreActions() }
        binding.btnConfirmDestination.setOnClickListener { completeCopyOrMove() }
        binding.btnCancelDestination.setOnClickListener {
            pendingFileOperation = null
            updateSelectionUi()
        }
    }
    
    private fun openDrafts() {
        val intent = Intent(this, DraftsActivity::class.java)
        intent.putExtra(DraftsActivity.EXTRA_FROM_EDITOR, false)
        startActivity(intent)
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE
            if (Environment.isExternalStorageManager()) {
                loadDirectory(getDefaultDirectory())
            } else {
                requestManageStoragePermission()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 需要 READ_EXTERNAL_STORAGE
            val readPermission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, readPermission) 
                == PackageManager.PERMISSION_GRANTED) {
                loadDirectory(getDefaultDirectory())
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        readPermission,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        } else {
            // Android 5.x 不需要运行时权限
            loadDirectory(getDefaultDirectory())
        }
    }
    
    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        }
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.error)
            .setMessage("需要存储权限才能访问字幕文件。请在设置中授予权限。")
            .setPositiveButton(R.string.confirm) { _, _ ->
                checkPermissions()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun getDefaultDirectory(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    
    private fun loadDirectory(directory: File) {
        if (!directory.exists() || !directory.canRead()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "无法访问目录：${directory.name}", Toast.LENGTH_SHORT).show()
            return
        }
        
        currentDirectory = directory
        updatePathDisplay()
        
        val files = mutableListOf<File>()
        
        // 添加父目录（如果不是根目录）
        val parent = directory.parentFile
        if (parent != null && parent.canRead()) {
            // 使用特殊标记表示父目录
            files.add(File(directory.absolutePath + "/.."))
        }
        
        // 添加子目录
        val subDirs = directory.listFiles { file ->
            file.isDirectory && (showHiddenFiles || !file.name.startsWith("."))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        files.addAll(subDirs)
        
        // 添加字幕文件
        val subtitleFiles = directory.listFiles { file ->
            file.isFile && (showHiddenFiles || !file.name.startsWith(".")) && FileUtils.isSubtitleFile(file)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        files.addAll(subtitleFiles)
        
        // 添加音频文件
        val audioFiles = directory.listFiles { file ->
            file.isFile && (showHiddenFiles || !file.name.startsWith(".")) && FileUtils.isAudioFile(file)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        files.addAll(audioFiles)

        if (showAllFileTypes) {
            val otherFiles = directory.listFiles { file ->
                file.isFile && (showHiddenFiles || !file.name.startsWith(".")) &&
                    !FileUtils.isSubtitleFile(file) && !FileUtils.isAudioFile(file)
            }?.sortedBy { it.name.lowercase() } ?: emptyList()
            files.addAll(otherFiles)
        }
        visibleFiles.clear()
        visibleFiles.addAll(files.filter { it.name != ".." })
        
        fileAdapter.submitList(files)
        updateSelectionUi()
        
        // 更新空状态
        binding.emptyState.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFileList.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }
    
    private fun updatePathDisplay() {
        currentDirectory?.let {
            binding.tvCurrentPath.text = it.absolutePath
        }
    }
    
    private fun onFileClicked(file: File) {
        // 处理父目录导航
        if (file.name == "..") {
            goUpLevel()
            return
        }

        if (selectedPaths.isNotEmpty()) {
            if (file.isDirectory) {
                directoryHistory.add(currentDirectory!!)
                loadDirectory(file)
            } else {
                toggleSelection(file)
            }
            return
        }
        
        if (file.isDirectory) {
            // 进入子目录
            directoryHistory.add(currentDirectory!!)
            loadDirectory(file)
        } else if (FileUtils.isSubtitleFile(file)) {
            // 打开字幕文件进行编辑
            openFileForEdit(file)
        } else if (FileUtils.isAudioFile(file)) {
            // 打开音频文件进行编辑（自动查找同名字幕）
            openAudioFileForEdit(file)
        } else {
            com.subtitleedit.util.OverwritingToast.makeText(this, "不支持的文件格式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterSelectionMode(file: File) {
        if (file.name == "..") return
        if (file.absolutePath in selectedPaths) {
            toggleSelection(file)
            return
        }
        pendingFileOperation = null
        selectedPaths.add(file.absolutePath)
        updateSelectionUi()
    }

    private fun toggleSelection(file: File) {
        if (!selectedPaths.add(file.absolutePath)) selectedPaths.remove(file.absolutePath)
        if (selectedPaths.isEmpty()) exitSelectionMode() else updateSelectionUi()
    }

    private fun selectAllVisibleFiles() {
        if (visibleFiles.isNotEmpty() && visibleFiles.all { it.absolutePath in selectedPaths }) {
            exitSelectionMode()
            return
        }
        selectedPaths.addAll(visibleFiles.map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectRangeBetweenSelectedFiles() {
        val selectedIndices = visibleFiles.mapIndexedNotNull { index, file ->
            index.takeIf { file.absolutePath in selectedPaths }
        }
        if (selectedIndices.size < 2) {
            showShortToast("请先在当前目录选择两个文件")
            return
        }

        val start = selectedIndices.minOrNull() ?: return
        val end = selectedIndices.maxOrNull() ?: return
        selectedPaths.addAll(visibleFiles.subList(start, end + 1).map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectedFiles(): List<File> = selectedPaths.map(::File).filter { it.exists() }

    private fun updateSelectionUi() {
        val isSelecting = selectedPaths.isNotEmpty()
        binding.normalBottomActions.visibility = if (isSelecting) View.GONE else View.VISIBLE
        binding.selectionBottomActions.visibility = if (isSelecting) View.VISIBLE else View.GONE

        val operation = pendingFileOperation
        val selectionTitle = when (operation) {
            FileOperation.COPY -> "选择复制目标（已选 ${selectedPaths.size} 项）"
            FileOperation.MOVE -> "选择移动目标（已选 ${selectedPaths.size} 项）"
            null -> "已选择 ${selectedPaths.size} 项"
        }
        val choosingDestination = operation != null
        binding.selectionActionItems.visibility = if (choosingDestination) View.GONE else View.VISIBLE
        binding.destinationActionItems.visibility = if (choosingDestination) View.VISIBLE else View.GONE
        listOf(
            binding.btnCopySelected,
            binding.btnMoveSelected,
            binding.btnRenameSelected,
            binding.btnDeleteSelected,
            binding.btnMoreSelected
        ).forEach { it.isEnabled = !choosingDestination }
        binding.btnConfirmDestination.text = if (operation == FileOperation.MOVE) "移动到此处" else "复制到此处"
        supportActionBar?.title = if (isSelecting) {
            selectionTitle
        } else {
            getString(R.string.app_name)
        }
        binding.toolbar.navigationIcon = if (isSelecting) {
            ContextCompat.getDrawable(this, R.drawable.ic_close)
        } else {
            null
        }
        binding.toolbar.navigationContentDescription = if (isSelecting) "退出选择模式" else null
        binding.toolbar.setNavigationOnClickListener(if (isSelecting) {
            View.OnClickListener { exitSelectionMode() }
        } else {
            null
        })
        invalidateOptionsMenu()
        fileAdapter.updateSelection(isSelecting, selectedPaths)
    }

    private fun exitSelectionMode() {
        selectedPaths.clear()
        pendingFileOperation = null
        updateSelectionUi()
    }

    private fun startDestinationSelection(operation: FileOperation) {
        if (selectedFiles().isEmpty()) {
            exitSelectionMode()
            return
        }
        pendingFileOperation = operation
        updateSelectionUi()
    }

    private fun completeCopyOrMove() {
        val operation = pendingFileOperation ?: return
        val destination = currentDirectory ?: return
        val sources = selectedFiles()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sources.forEach { source -> copyOrMove(source, destination, operation) }
                }
            }
            result.onSuccess {
                showShortToast(if (operation == FileOperation.COPY) "已复制" else "已移动")
                exitSelectionMode()
                loadDirectory(destination)
            }.onFailure { error ->
                showShortToast("操作失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun copyOrMove(source: File, destination: File, operation: FileOperation) {
        val sourcePath = source.canonicalFile
        val destinationPath = destination.canonicalFile
        if (source.isDirectory && destinationPath.path.startsWith(sourcePath.path + File.separator)) {
            throw IllegalArgumentException("不能将文件夹复制或移动到其自身内部")
        }
        val target = File(destination, uniqueFileName(destination, source.name))
        if (!source.copyRecursively(target, overwrite = false)) {
            throw IllegalStateException("复制失败：${source.name}")
        }
        if (operation == FileOperation.MOVE && !source.deleteRecursively()) {
            throw IllegalStateException("已复制，但无法删除原文件：${source.name}")
        }
    }

    private fun uniqueFileName(directory: File, originalName: String): String {
        if (!File(directory, originalName).exists()) return originalName
        val separator = originalName.lastIndexOf('.')
        val base = if (separator > 0) originalName.substring(0, separator) else originalName
        val extension = if (separator > 0) originalName.substring(separator) else ""
        var index = 1
        var candidate: String
        do {
            candidate = "$base ($index)$extension"
            index++
        } while (File(directory, candidate).exists())
        return candidate
    }

    private fun renameSelectedFile() {
        val files = selectedFiles()
        if (files.size != 1) {
            showShortToast("请只选择一个文件或文件夹进行重命名")
            return
        }
        showRenameDialog(files.first())
    }

    private fun showRenameDialog(file: File) {
        val input = android.widget.EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                when {
                    newName.isEmpty() || newName == "." || newName == ".." || newName.contains('/') || newName.contains('\\') ->
                        showShortToast("文件名无效")
                    newName == file.name -> Unit
                    else -> {
                        val target = File(file.parentFile, newName)
                        if (target.exists()) {
                            showShortToast("目标名称已存在")
                        } else if (file.renameTo(target)) {
                            exitSelectionMode()
                            currentDirectory?.let(::loadDirectory)
                            showShortToast("已重命名")
                        } else {
                            showShortToast("重命名失败")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteSelectedFiles() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定要删除选中的 ${files.size} 项吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) { files.all { it.deleteRecursively() } }
                    if (deleted) {
                        exitSelectionMode()
                        currentDirectory?.let(::loadDirectory)
                        showShortToast("已删除")
                    } else {
                        showShortToast("删除失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoreActions() {
        PopupMenu(this, binding.btnMoreSelected).apply {
            menu.add("查看属性")
            setOnMenuItemClickListener {
                showSelectedProperties()
                true
            }
            show()
        }
    }

    private fun showSelectedProperties() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) {
                if (files.size == 1) filePropertiesText(files.first())
                else "已选择：${files.size} 项\n总大小：${FileUtils.formatFileSize(files.sumOf { if (it.isDirectory) directorySize(it) else it.length() })}"
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("属性")
                .setMessage(details)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun filePropertiesText(file: File): String {
        val type = if (file.isDirectory) "文件夹" else file.extension.uppercase().ifBlank { "未知" } + " 文件"
        val size = if (file.isDirectory) directorySize(file) else file.length()
        val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        return "名称：${file.name}\n目录：${file.parent ?: ""}\n类型：$type\n大小：${FileUtils.formatFileSize(size)}\n修改时间：$modified"
    }

    private fun showShortToast(message: String) {
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 打开音频文件进行编辑
     * 自动查找同文件夹下同名的字幕文件，多个时让用户选择
     */
    private fun openAudioFileForEdit(audioFile: File) {
        val possibleSubtitleFiles = FileUtils.getPossibleSubtitleFiles(audioFile)

        when {
            possibleSubtitleFiles.size > 1 -> {
                showSubtitleFilePicker(audioFile, possibleSubtitleFiles)
            }
            else -> {
                openAudioWithSubtitle(audioFile, possibleSubtitleFiles.firstOrNull())
            }
        }
    }

    /**
     * 当存在多个同名字幕文件时，弹出选择对话框
     */
    private fun showSubtitleFilePicker(audioFile: File, subtitleFiles: List<File>) {
        val fileNames = subtitleFiles.map { file ->
            file.name + "  (" + FileUtils.formatFileSize(file.length()) + ")"
        }.toTypedArray()

        // 用自定义标题同时显示标题和提示信息（setItems 与 setView/setMessage 互斥）
        val customTitle = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(8, 8, 8, 0)
            addView(android.widget.TextView(context).apply {
                text = "选择字幕文件"
                textSize = 19f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(4, 0, 4, 6)
            })
            addView(android.widget.TextView(context).apply {
                text = "音频「${audioFile.name}」同目录下存在多个字幕文件，请选择要打开的文件："
                textSize = 14f
                setPadding(4, 0, 4, 0)
            })
        }

        AlertDialog.Builder(this)
            .setCustomTitle(customTitle)
            .setItems(fileNames) { _, which ->
                openAudioWithSubtitle(audioFile, subtitleFiles[which])
            }
            .setNegativeButton("不加载字幕") { _, _ ->
                openAudioWithSubtitle(audioFile, null)
            }
            .show()
    }

    /**
     * 打开音频文件及指定字幕文件（字幕文件为 null 时仅打开音频）
     */
    private fun openAudioWithSubtitle(audioFile: File, subtitleFile: File?) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, audioFile.absolutePath)
        intent.putExtra(EditorActivity.EXTRA_IS_AUDIO_FILE, true)
        if (subtitleFile != null) {
            intent.putExtra(EditorActivity.EXTRA_SUBTITLE_FILE_PATH, subtitleFile.absolutePath)
        }
        startActivity(intent)
    }
    
    private fun goUpLevel() {
        if (directoryHistory.isNotEmpty()) {
            val parent = directoryHistory.removeAt(directoryHistory.size - 1)
            loadDirectory(parent)
        } else {
            currentDirectory?.parentFile?.let { parent ->
                if (parent.exists() && parent.canRead()) {
                    loadDirectory(parent)
                }
            }
        }
    }
    
    private fun openFileForEdit(file: File) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        startActivity(intent)
    }
    
    override fun onBackPressed() {
        if (pendingFileOperation != null && canGoUpLevel()) {
            goUpLevel()
        } else if (selectedPaths.isNotEmpty()) {
            exitSelectionMode()
        } else if (directoryHistory.isNotEmpty()) {
            goUpLevel()
        } else {
            super.onBackPressed()
        }
    }

    private fun canGoUpLevel(): Boolean {
        if (directoryHistory.isNotEmpty()) return true
        val parent = currentDirectory?.parentFile
        return parent?.exists() == true && parent.canRead()
    }
}
