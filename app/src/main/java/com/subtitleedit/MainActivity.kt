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
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
import com.subtitleedit.databinding.DialogArchiveProgressBinding
import com.subtitleedit.databinding.DialogArchivePasswordBinding
import com.subtitleedit.databinding.DialogArchiveConflictBinding
import com.subtitleedit.databinding.DialogCreateArchiveBinding
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.ArchivePasswordVault
import com.subtitleedit.model.ArchiveConflictDialogFormatter
import com.subtitleedit.model.ArchiveConflictDialogModel
import com.subtitleedit.model.ArchiveConflictFileMetadata
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.UpdateChecker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.subtitleedit.util.ArchivePasswordRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 主界面 - 文件浏览器
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val MENU_SELECT_ALL = 0x10001
        const val MENU_SELECT_RANGE = 0x10002
        const val CONFLICT_WAIT_INTERVAL_MS = 250L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileListAdapter
    
    private var currentDirectory: File? = null
    private val directoryHistory = mutableListOf<File>()
    private val visibleFiles = mutableListOf<File>()
    private val selectedPaths = linkedSetOf<String>()
    private var pendingFileOperation: FileOperation? = null
    private var pendingArchiveFile: File? = null
    private val destinationNavigationHistory = mutableListOf<DestinationNavigationState>()
    private var updateCheckStarted = false
    private var pendingUpdate: UpdateChecker.UpdateInfo? = null
    private var updateDialogShown = false
    private var showAllFileTypes = false
    private var showHiddenFiles = false

    private enum class FileOperation { COPY, MOVE, EXTRACT }

    private enum class ArchiveAction { PREVIEW, EXTRACT_CURRENT, TEST }

    private data class SplitOption(val label: String, val bytes: Long?)

    private data class ArchiveProgressUi(
        val dialog: AlertDialog,
        val binding: DialogArchiveProgressBinding
    )

    private data class DestinationNavigationState(
        val directory: File,
        val directoryHistory: List<File>
    )
    
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
        if (selectedPaths.isNotEmpty() || pendingFileOperation != null) {
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
            if (pendingFileOperation != null) navigateDestinationUp() else goUpLevel()
        }
        
        binding.btnDrafts.setOnClickListener {
            openDrafts()
        }

        binding.btnCopySelected.setOnClickListener { startDestinationSelection(FileOperation.COPY) }
        binding.btnMoveSelected.setOnClickListener { startDestinationSelection(FileOperation.MOVE) }
        binding.btnRenameSelected.setOnClickListener { renameSelectedFile() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelectedFiles() }
        binding.btnMoreSelected.setOnClickListener { showMoreActions() }
        binding.btnConfirmDestination.setOnClickListener { completeDestinationOperation() }
        binding.btnCancelDestination.setOnClickListener {
            cancelDestinationSelection()
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
    
    private fun loadDirectory(directory: File): Boolean {
        if (!directory.exists() || !directory.canRead()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "无法访问目录：${directory.name}", Toast.LENGTH_SHORT).show()
            return false
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

        val archiveFiles = directory.listFiles { file ->
            file.isFile && (showHiddenFiles || !file.name.startsWith(".")) &&
                ArchiveManager.isRecognizedArchive(file)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        files.addAll(archiveFiles)

        if (showAllFileTypes) {
            val otherFiles = directory.listFiles { file ->
                file.isFile && (showHiddenFiles || !file.name.startsWith(".")) &&
                    !FileUtils.isSubtitleFile(file) && !FileUtils.isAudioFile(file) &&
                    !ArchiveManager.isRecognizedArchive(file)
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
        return true
    }
    
    private fun updatePathDisplay() {
        currentDirectory?.let {
            binding.tvCurrentPath.text = it.absolutePath
        }
    }
    
    private fun onFileClicked(file: File) {
        // 处理父目录导航
        if (file.name == "..") {
            if (pendingFileOperation != null) navigateDestinationUp() else goUpLevel()
            return
        }

        if (pendingFileOperation != null) {
            if (file.isDirectory) {
                navigateDestinationInto(file)
            } else {
                showShortToast("请选择目标文件夹")
            }
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
        } else if (ArchiveManager.isRecognizedArchive(file)) {
            if (ArchiveManager.isSupportedArchive(file)) {
                showArchiveActions(file)
            } else {
                showShortToast("当前库暂不支持 ${file.extension.uppercase()} 格式")
            }
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
        if (file.name == ".." || pendingFileOperation != null) return
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
        val operation = pendingFileOperation
        val isSelectionUiActive = selectedPaths.isNotEmpty() || operation != null
        binding.normalBottomActions.visibility = if (isSelectionUiActive) View.GONE else View.VISIBLE
        binding.selectionBottomActions.visibility = if (isSelectionUiActive) View.VISIBLE else View.GONE

        val selectionTitle = when (operation) {
            FileOperation.COPY -> "选择复制目标（已选 ${selectedPaths.size} 项）"
            FileOperation.MOVE -> "选择移动目标（已选 ${selectedPaths.size} 项）"
            FileOperation.EXTRACT -> "选择解压目录"
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
        binding.btnConfirmDestination.text = when (operation) {
            FileOperation.MOVE -> "移动到此处"
            FileOperation.EXTRACT -> "解压到此处"
            else -> "复制到此处"
        }
        supportActionBar?.title = if (isSelectionUiActive) {
            selectionTitle
        } else {
            getString(R.string.app_name)
        }
        binding.toolbar.navigationIcon = if (isSelectionUiActive) {
            ContextCompat.getDrawable(this, R.drawable.ic_close)
        } else {
            null
        }
        binding.toolbar.navigationContentDescription = if (isSelectionUiActive) "退出选择模式" else null
        binding.toolbar.setNavigationOnClickListener(if (isSelectionUiActive) {
            View.OnClickListener { exitSelectionMode() }
        } else {
            null
        })
        invalidateOptionsMenu()
        fileAdapter.updateSelection(selectedPaths.isNotEmpty() && operation == null, selectedPaths)
    }

    private fun exitSelectionMode() {
        selectedPaths.clear()
        pendingFileOperation = null
        pendingArchiveFile = null
        destinationNavigationHistory.clear()
        updateSelectionUi()
    }

    private fun startDestinationSelection(operation: FileOperation) {
        if (selectedFiles().isEmpty()) {
            exitSelectionMode()
            return
        }
        pendingFileOperation = operation
        destinationNavigationHistory.clear()
        updateSelectionUi()
    }

    private fun completeDestinationOperation() {
        val operation = pendingFileOperation ?: return
        val destination = currentDirectory ?: return
        if (operation == FileOperation.EXTRACT) {
            val archive = pendingArchiveFile ?: run {
                cancelDestinationSelection()
                return
            }
            extractArchive(archive, destination)
            return
        }
        val sources = selectedFiles()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sources.forEach { source -> copyOrMove(source, destination, operation) }
                }
            }
            result.onSuccess {
                showShortToast(
                    when (operation) {
                        FileOperation.COPY -> "已复制"
                        FileOperation.MOVE -> "已移动"
                        FileOperation.EXTRACT -> error("无效的复制操作")
                    }
                )
                exitSelectionMode()
                loadDirectory(destination)
            }.onFailure { error ->
                showShortToast("操作失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun cancelDestinationSelection() {
        pendingFileOperation = null
        pendingArchiveFile = null
        destinationNavigationHistory.clear()
        updateSelectionUi()
    }

    private fun copyOrMove(source: File, destination: File, operation: FileOperation) {
        require(operation == FileOperation.COPY || operation == FileOperation.MOVE)
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
            val compressItem = menu.add("压缩")
            val propertiesItem = menu.add("查看属性")
            setOnMenuItemClickListener { item ->
                when (item) {
                    compressItem -> showCreateArchiveDialog()
                    propertiesItem -> showSelectedProperties()
                }
                true
            }
            show()
        }
    }

    private fun showCreateArchiveDialog() {
        val sources = selectedFiles()
        val outputDirectory = currentDirectory ?: return
        if (sources.isEmpty()) return

        val dialogBinding = DialogCreateArchiveBinding.inflate(layoutInflater)
        val formats = listOf(
            ArchiveManager.CreateFormat.ZIP,
            ArchiveManager.CreateFormat.SEVEN_Z,
            ArchiveManager.CreateFormat.TAR
        )
        val splitOptions = listOf(
            SplitOption("不分卷", null),
            SplitOption("10 MB", 10L * 1024 * 1024),
            SplitOption("50 MB", 50L * 1024 * 1024),
            SplitOption("100 MB", 100L * 1024 * 1024),
            SplitOption("500 MB", 500L * 1024 * 1024)
        )
        dialogBinding.etArchiveName.setText(defaultArchiveName(sources))
        dialogBinding.etArchiveName.setSelection(dialogBinding.etArchiveName.text?.length ?: 0)
        dialogBinding.spinnerArchiveFormat.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            formats.map { it.displayName }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        dialogBinding.spinnerSplitSize.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            splitOptions.map { it.label }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        var methods = ArchiveManager.compressionMethods(formats.first())
        var encryptionMethods = ArchiveManager.encryptionMethods(formats.first())
        fun refreshFormatControls(position: Int) {
            val format = formats[position.coerceIn(formats.indices)]
            methods = ArchiveManager.compressionMethods(format)
            dialogBinding.spinnerCompressionMethod.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                methods.map { it.displayName }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            dialogBinding.spinnerCompressionMethod.setSelection(0, false)
            encryptionMethods = ArchiveManager.encryptionMethods(format)
            dialogBinding.spinnerEncryptionMethod.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                encryptionMethods.map { it.displayName }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            if (encryptionMethods.isNotEmpty()) {
                dialogBinding.spinnerEncryptionMethod.setSelection(0, false)
            }
            val passwordEnabled = encryptionMethods.isNotEmpty()
            dialogBinding.layoutArchivePassword.isEnabled = passwordEnabled
            dialogBinding.btnPasswordBook.isEnabled = passwordEnabled
            val zipEncryptionOptions = format == ArchiveManager.CreateFormat.ZIP
            dialogBinding.layoutArchiveEncryption.visibility =
                if (zipEncryptionOptions) View.VISIBLE else View.GONE
            dialogBinding.spinnerEncryptionMethod.isEnabled = zipEncryptionOptions
            val splitEnabled = format == ArchiveManager.CreateFormat.ZIP ||
                format == ArchiveManager.CreateFormat.SEVEN_Z
            dialogBinding.spinnerSplitSize.isEnabled = splitEnabled
            if (!splitEnabled) dialogBinding.spinnerSplitSize.setSelection(0)
            dialogBinding.tvPasswordHint.text = when (format) {
                ArchiveManager.CreateFormat.ZIP -> "留空则不加密；ZipCrypto 兼容性更好，AES-256 更安全"
                ArchiveManager.CreateFormat.SEVEN_Z -> "使用 7Z AES-256 加密；留空则不加密"
                ArchiveManager.CreateFormat.TAR -> "密码仅适用于 ZIP 和 7Z 格式"
            }
        }
        dialogBinding.spinnerArchiveFormat.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshFormatControls(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        refreshFormatControls(0)
        dialogBinding.btnPasswordBook.setOnClickListener {
            showPasswordBook(dialogBinding.etArchivePassword)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("创建压缩文件")
            .setView(dialogBinding.root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val format = formats[dialogBinding.spinnerArchiveFormat.selectedItemPosition]
                val method = methods[dialogBinding.spinnerCompressionMethod.selectedItemPosition]
                val splitSizeBytes = splitOptions[dialogBinding.spinnerSplitSize.selectedItemPosition].bytes
                val extension = ArchiveManager.outputExtension(format, method)
                val rawName = dialogBinding.etArchiveName.text?.toString()?.trim().orEmpty()
                val baseName = stripArchiveExtension(rawName)
                when {
                    !isValidFileName(baseName) -> {
                        dialogBinding.etArchiveName.error = "请输入有效名称"
                    }
                    File(outputDirectory, "$baseName.$extension").exists() -> {
                        dialogBinding.etArchiveName.error = "同名压缩包已存在"
                    }
                    else -> {
                        val password = if (encryptionMethods.isNotEmpty()) {
                            dialogBinding.etArchivePassword.text?.toString().orEmpty()
                        } else {
                            ""
                        }
                        val encryptionMethod = when (format) {
                            ArchiveManager.CreateFormat.ZIP -> encryptionMethods[
                                dialogBinding.spinnerEncryptionMethod.selectedItemPosition
                            ]
                            ArchiveManager.CreateFormat.SEVEN_Z -> encryptionMethods.first()
                            ArchiveManager.CreateFormat.TAR -> null
                        }
                        dialog.dismiss()
                        createArchive(
                            sources = sources,
                            output = File(outputDirectory, "$baseName.$extension"),
                            format = format,
                            method = method,
                            password = password,
                            encryptionMethod = encryptionMethod,
                            splitSizeBytes = splitSizeBytes,
                            deleteSources = dialogBinding.cbDeleteSources.isChecked
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun createArchive(
        sources: List<File>,
        output: File,
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod,
        password: String,
        encryptionMethod: ArchiveManager.EncryptionMethod?,
        splitSizeBytes: Long?,
        deleteSources: Boolean
    ) {
        val progress = showArchiveProgress(
            title = "正在压缩",
            message = "正在创建 ${output.name}...",
            showCancel = true
        )
        val committed = AtomicBoolean(false)
        val compressionJob = lifecycleScope.launch {
            val passwordChars = password.takeIf(String::isNotEmpty)?.toCharArray()
            try {
                val deleteFailures = withContext(Dispatchers.IO) {
                    val workerContext = coroutineContext
                    ArchiveManager.createArchive(
                        sources = sources,
                        destination = output,
                        format = format,
                        method = method,
                        password = passwordChars,
                        encryptionMethod = encryptionMethod,
                        splitSizeBytes = splitSizeBytes,
                        checkCancelled = workerContext::ensureActive,
                        onCommitted = {
                            committed.set(true)
                            runOnUiThread {
                                if (progress.dialog.isShowing) {
                                    progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                                    if (deleteSources) {
                                        progress.binding.tvProgressMessage.text = "正在删除源文件..."
                                    }
                                }
                            }
                        }
                    )
                    if (deleteSources) sources.filterNot { it.deleteRecursively() } else emptyList()
                }
                exitSelectionMode()
                output.parentFile?.let(::loadDirectory)
                if (deleteFailures.isEmpty()) {
                    showShortToast("压缩完成：${output.name}")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("压缩已完成")
                        .setMessage("${output.name} 已创建，但有 ${deleteFailures.size} 个源文件无法删除。")
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (_: CancellationException) {
                exitSelectionMode()
                output.parentFile?.let(::loadDirectory)
                showShortToast(if (committed.get()) "压缩完成：${output.name}" else "已取消压缩")
            } catch (error: Throwable) {
                showOperationError("压缩失败", error)
                output.parentFile?.let(::loadDirectory)
            } finally {
                passwordChars?.fill('\u0000')
                progress.dialog.dismiss()
            }
        }
        progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
            if (committed.get()) {
                button.isEnabled = false
                return@setOnClickListener
            }
            button.isEnabled = false
            progress.binding.tvProgressMessage.text = "正在取消..."
            progress.binding.progressBar.isIndeterminate = true
            progress.binding.tvProgressPercent.visibility = View.GONE
            compressionJob.cancel(CancellationException("用户取消压缩"))
        }
    }

    private fun showArchiveActions(archive: File) {
        val actions = arrayOf("解压预览", "解压到当前文件夹", "解压到指定目录", "解压测试")
        AlertDialog.Builder(this)
            .setTitle(archive.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runArchiveAction(archive, ArchiveAction.PREVIEW)
                    1 -> runArchiveAction(archive, ArchiveAction.EXTRACT_CURRENT)
                    2 -> startExtractDestinationSelection(archive)
                    3 -> runArchiveAction(archive, ArchiveAction.TEST)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runArchiveAction(
        archive: File,
        action: ArchiveAction,
        password: String? = null
    ) {
        if (action == ArchiveAction.EXTRACT_CURRENT) {
            prepareArchiveExtraction(
                archive = archive,
                destination = archive.parentFile ?: error("找不到目标目录"),
                password = password,
                onCompleted = { archive.parentFile?.let(::loadDirectory) }
            )
            return
        }
        val title = when (action) {
            ArchiveAction.PREVIEW -> "正在读取压缩包"
            ArchiveAction.TEST -> "正在测试压缩包"
            ArchiveAction.EXTRACT_CURRENT -> error("不应直接执行解压操作")
        }
        val progress = showBlockingProgress(title, archive.name)
        lifecycleScope.launch {
            val passwordChars = password?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (action) {
                            ArchiveAction.PREVIEW -> ArchiveManager.listEntries(archive, passwordChars)
                            ArchiveAction.TEST -> ArchiveManager.testArchive(archive, passwordChars)
                            ArchiveAction.EXTRACT_CURRENT -> error("不应直接执行解压操作")
                        }
                    }
                }
            } finally {
                passwordChars?.fill('\u0000')
                progress.dismiss()
            }
            result.onSuccess { value ->
                when (action) {
                    ArchiveAction.PREVIEW -> showArchivePreview(
                        archive,
                        @Suppress("UNCHECKED_CAST") (value as List<ArchiveManager.EntryInfo>),
                        password
                    )
                    ArchiveAction.EXTRACT_CURRENT -> Unit
                    ArchiveAction.TEST -> {
                        val tested = value as ArchiveManager.TestResult
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("解压测试通过")
                            .setMessage(
                                "压缩包完整可读。\n\n条目：${tested.entryCount}\n解压大小：${FileUtils.formatFileSize(tested.totalBytes)}"
                            )
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }.onFailure { error ->
                if (needsArchivePassword(archive, error, password != null)) {
                    showArchivePasswordDialog(
                        archive = archive,
                        onPassword = { enteredPassword ->
                            runArchiveAction(archive, action, enteredPassword)
                        }
                    )
                } else {
                    showOperationError("操作失败", error)
                }
            }
        }
    }

    private fun showArchivePreview(
        archive: File,
        entries: List<ArchiveManager.EntryInfo>,
        password: String?
    ) {
        val maxVisibleEntries = 300
        val labels = entries.take(maxVisibleEntries).map { entry ->
            if (entry.isDirectory) {
                "[目录] ${entry.name}"
            } else {
                val size = if (entry.size >= 0L) FileUtils.formatFileSize(entry.size) else "大小未知"
                "${entry.name}  ($size)"
            }
        }.toMutableList()
        if (entries.size > maxVisibleEntries) {
            labels += "... 另有 ${entries.size - maxVisibleEntries} 项"
        }
        AlertDialog.Builder(this)
            .setTitle("解压预览（${entries.size} 项）")
            .apply {
                if (labels.isEmpty()) setMessage("压缩包为空") else setItems(labels.toTypedArray(), null)
            }
            .setPositiveButton("确定", null)
            .setNeutralButton("解压到当前文件夹") { _, _ ->
                runArchiveAction(archive, ArchiveAction.EXTRACT_CURRENT, password)
            }
            .show()
    }

    private fun startExtractDestinationSelection(archive: File) {
        selectedPaths.clear()
        pendingArchiveFile = archive
        pendingFileOperation = FileOperation.EXTRACT
        destinationNavigationHistory.clear()
        updateSelectionUi()
    }

    private fun extractArchive(archive: File, destination: File) {
        prepareArchiveExtraction(
            archive = archive,
            destination = destination,
            onCompleted = {
                exitSelectionMode()
                loadDirectory(destination)
            },
            onCancelled = ::exitSelectionMode
        )
    }

    private fun prepareArchiveExtraction(
        archive: File,
        destination: File,
        password: String? = null,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (ArchiveManager.requiresStreamingConflictResolution(archive)) {
            executeArchiveExtraction(
                archive = archive,
                destination = destination,
                password = password,
                conflictPolicy = ArchiveManager.ConflictPolicy.FAIL,
                conflictPolicies = emptyMap(),
                onCompleted = onCompleted,
                onCancelled = onCancelled,
                onConflict = ::awaitArchiveConflictResolution
            )
            return
        }
        val scanProgress = showArchiveProgress(
            "正在解压",
            "正在检查压缩包..."
        )
        lifecycleScope.launch {
            val passwordChars = password?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        ArchiveManager.findDestinationConflicts(
                            archive = archive,
                            destination = destination,
                            password = passwordChars,
                            onProgress = { _, completed, total ->
                                updateArchiveProgress(
                                    scanProgress,
                                    ArchiveManager.ProgressPhase.SCANNING,
                                    completed,
                                    total
                                )
                            }
                        )
                    }
                }
            } finally {
                passwordChars?.fill('\u0000')
                scanProgress.dialog.dismiss()
            }
            result.onSuccess { conflicts ->
                if (conflicts.isEmpty()) {
                    executeArchiveExtraction(
                        archive,
                        destination,
                        password,
                        ArchiveManager.ConflictPolicy.FAIL,
                        emptyMap(),
                        onCompleted,
                        onCancelled
                    )
                } else {
                    resolveArchiveConflictChoices(
                        archive,
                        destination,
                        password,
                        conflicts,
                        onCompleted,
                        onCancelled = onCancelled
                    )
                }
            }.onFailure { error ->
                if (needsArchivePassword(archive, error, password != null)) {
                    showArchivePasswordDialog(
                        archive = archive,
                        onPassword = { enteredPassword ->
                            prepareArchiveExtraction(
                                archive,
                                destination,
                                enteredPassword,
                                onCompleted,
                                onCancelled
                            )
                        },
                        onCancelled = onCancelled
                    )
                } else {
                    onCancelled()
                    showOperationError("解压失败", error)
                }
            }
        }
    }

    private fun resolveArchiveConflictChoices(
        archive: File,
        destination: File,
        password: String?,
        conflicts: List<ArchiveManager.DestinationConflict>,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit = {},
        index: Int = 0,
        policies: MutableMap<String, ArchiveManager.ConflictPolicy> = linkedMapOf()
    ) {
        if (index >= conflicts.size) {
            executeArchiveExtraction(
                archive,
                destination,
                password,
                ArchiveManager.ConflictPolicy.FAIL,
                policies.toMap(),
                onCompleted,
                onCancelled
            )
            return
        }
        showExtractionConflictDialog(
            conflict = conflicts[index],
            onPolicySelected = { selectedPolicy, applyToAll ->
                if (applyToAll) {
                    executeArchiveExtraction(
                        archive,
                        destination,
                        password,
                        selectedPolicy,
                        policies.toMap(),
                        onCompleted,
                        onCancelled
                    )
                } else {
                    policies[conflicts[index].entryName] = selectedPolicy
                    resolveArchiveConflictChoices(
                        archive,
                        destination,
                        password,
                        conflicts,
                        onCompleted,
                        onCancelled,
                        index + 1,
                        policies
                    )
                }
            },
            onCancelled = onCancelled
        )
    }

    private fun executeArchiveExtraction(
        archive: File,
        destination: File,
        password: String?,
        conflictPolicy: ArchiveManager.ConflictPolicy,
        conflictPolicies: Map<String, ArchiveManager.ConflictPolicy>,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit = {},
        onConflict: ((ArchiveManager.DestinationConflict) -> ArchiveManager.ConflictResolution)? = null
    ) {
        val archiveProgress = showArchiveProgress("正在解压", "目标：${destination.absolutePath}")
        lifecycleScope.launch {
            val passwordChars = password?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        ArchiveManager.extractArchive(
                            archive = archive,
                            destination = destination,
                            password = passwordChars,
                            conflictPolicy = conflictPolicy,
                            conflictPolicies = conflictPolicies,
                            conflictsPrechecked = true,
                            onProgress = { phase, completed, total ->
                                updateArchiveProgress(archiveProgress, phase, completed, total)
                            },
                            onConflict = onConflict
                        )
                    }
                }
            } finally {
                passwordChars?.fill('\u0000')
                archiveProgress.dialog.dismiss()
            }
            result.onSuccess { extracted ->
                showExtractionCompleted(extracted)
                onCompleted()
            }.onFailure { error ->
                if (isArchiveOperationCancelled(error)) {
                    onCancelled()
                } else if (isDestinationConflict(error)) {
                    prepareArchiveExtraction(archive, destination, password, onCompleted, onCancelled)
                } else if (needsArchivePassword(archive, error, password != null)) {
                    showArchivePasswordDialog(
                        archive = archive,
                        onPassword = { enteredPassword ->
                            prepareArchiveExtraction(
                                archive,
                                destination,
                                enteredPassword,
                                onCompleted,
                                onCancelled
                            )
                        },
                        onCancelled = onCancelled
                    )
                } else {
                    onCancelled()
                    showOperationError("解压失败", error)
                }
            }
        }
    }

    private fun awaitArchiveConflictResolution(
        conflict: ArchiveManager.DestinationConflict
    ): ArchiveManager.ConflictResolution {
        val decision = AtomicReference<ArchiveManager.ConflictResolution?>()
        val cancelled = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                cancelled.set(true)
                completed.countDown()
                return@runOnUiThread
            }
            showExtractionConflictDialog(
                conflict = conflict,
                onPolicySelected = { policy, applyToAll ->
                    decision.set(ArchiveManager.ConflictResolution(policy, applyToAll))
                    completed.countDown()
                },
                onCancelled = {
                    cancelled.set(true)
                    completed.countDown()
                }
            )
        }

        try {
            while (!completed.await(CONFLICT_WAIT_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (isFinishing || isDestroyed || Thread.currentThread().isInterrupted) {
                    throw CancellationException("解压已取消")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("解压已取消")
        }
        if (cancelled.get()) throw CancellationException("用户取消解压")
        return decision.get() ?: throw CancellationException("解压已取消")
    }

    private fun showExtractionConflictDialog(
        conflict: ArchiveManager.DestinationConflict,
        onPolicySelected: (ArchiveManager.ConflictPolicy, Boolean) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        val model = ArchiveConflictDialogModel(
            entryName = conflict.entryName,
            source = ArchiveConflictFileMetadata(
                sizeBytes = conflict.sourceSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.sourceModifiedTimeMillis.takeIf { it > 0L }
            ),
            existing = ArchiveConflictFileMetadata(
                sizeBytes = conflict.existingSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.existingModifiedTimeMillis.takeIf { it > 0L }
            )
        )
        val dialogBinding = DialogArchiveConflictBinding.inflate(layoutInflater)
        dialogBinding.tvConflictTitle.text = "覆盖文件？"
        dialogBinding.tvConflictFileName.text = if (conflict.archiveInternal) {
            "压缩包内重复条目：${model.entryName}"
        } else {
            "（${model.entryName}）已存在"
        }
        dialogBinding.tvConflictSourceSize.text =
            "大小：${ArchiveConflictDialogFormatter.size(model.source.sizeBytes)}"
        dialogBinding.tvConflictSourceModified.text =
            "最后修改：${ArchiveConflictDialogFormatter.modifiedTime(model.source.modifiedAtMillis)}"
        dialogBinding.tvConflictReplacementSize.text =
            "大小：${ArchiveConflictDialogFormatter.size(model.existing.sizeBytes)}"
        dialogBinding.tvConflictReplacementModified.text =
            "最后修改：${ArchiveConflictDialogFormatter.modifiedTime(model.existing.modifiedAtMillis)}"
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        fun choose(policy: ArchiveManager.ConflictPolicy) {
            val applyToAll = dialogBinding.cbApplyToAll.isChecked
            dialog.dismiss()
            onPolicySelected(policy, applyToAll)
        }
        dialogBinding.btnConflictCancel.setOnClickListener {
            dialog.dismiss()
            onCancelled()
        }
        dialogBinding.btnConflictRename.setOnClickListener {
            choose(ArchiveManager.ConflictPolicy.RENAME)
        }
        dialogBinding.btnConflictSkip.setOnClickListener {
            choose(ArchiveManager.ConflictPolicy.SKIP)
        }
        dialogBinding.btnConflictReplace.setOnClickListener {
            choose(ArchiveManager.ConflictPolicy.OVERWRITE)
        }
        dialog.show()
    }

    private fun showExtractionCompleted(result: ArchiveManager.ExtractResult) {
        val message = if (result.skippedCount > 0) {
            "解压完成：${result.entryCount} 项，跳过 ${result.skippedCount} 项"
        } else {
            "解压完成：${result.entryCount} 项"
        }
        showShortToast(message)
    }

    private fun isDestinationConflict(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { it is ArchiveManager.DestinationConflictException }

    private fun isArchiveOperationCancelled(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { it is CancellationException }

    private fun showArchivePasswordDialog(
        archive: File,
        onPassword: (String) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        val passwordBinding = DialogArchivePasswordBinding.inflate(layoutInflater)
        passwordBinding.btnPasswordBook.setOnClickListener {
            showPasswordBook(passwordBinding.etArchivePassword)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("输入压缩包密码")
            .setMessage(archive.name)
            .setView(passwordBinding.root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnCancelListener { onCancelled() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                onCancelled()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordBinding.etArchivePassword.text?.toString().orEmpty()
                if (password.isEmpty()) {
                    passwordBinding.etArchivePassword.error = "请输入密码"
                } else {
                    dialog.dismiss()
                    onPassword(password)
                }
            }
        }
        dialog.show()
    }

    private fun showPasswordBook(target: android.widget.EditText) {
        val vault = ArchivePasswordVault(this)
        val passwords = runCatching(vault::getPasswords).getOrElse {
            showShortToast("无法读取密码本")
            return
        }
        val labels = passwords.mapIndexed { index, password ->
            "密码 ${index + 1}（${"•".repeat(password.length.coerceIn(1, 8))}）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("密码本")
            .apply {
                if (labels.isEmpty()) {
                    setMessage("密码本为空，可保存当前输入的密码。")
                } else {
                    setItems(labels) { _, which ->
                        target.setText(passwords[which])
                        target.setSelection(target.text?.length ?: 0)
                    }
                }
            }
            .setPositiveButton("保存当前密码") { _, _ ->
                val password = target.text?.toString().orEmpty()
                if (password.isEmpty()) {
                    showShortToast("请先输入密码")
                } else {
                    runCatching { vault.savePassword(password) }
                        .onSuccess { showShortToast("密码已保存") }
                        .onFailure { showShortToast("密码保存失败") }
                }
            }
            .apply {
                if (passwords.isNotEmpty()) {
                    setNeutralButton("清空密码本") { _, _ ->
                        runCatching(vault::clear)
                        showShortToast("密码本已清空")
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showBlockingProgress(title: String, message: String): AlertDialog =
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also(AlertDialog::show)

    private fun showArchiveProgress(
        title: String,
        message: String,
        showCancel: Boolean = false
    ): ArchiveProgressUi {
        val progressBinding = DialogArchiveProgressBinding.inflate(layoutInflater)
        progressBinding.tvProgressMessage.text = message
        progressBinding.progressBar.isIndeterminate = true
        progressBinding.tvProgressPercent.visibility = View.GONE
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(progressBinding.root)
            .setCancelable(false)
        if (showCancel) builder.setNegativeButton("取消", null)
        val dialog = builder.create()
            .also(AlertDialog::show)
        return ArchiveProgressUi(dialog, progressBinding)
    }

    private fun updateArchiveProgress(
        progress: ArchiveProgressUi,
        phase: ArchiveManager.ProgressPhase,
        completed: Long,
        total: Long
    ) {
        runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            progress.binding.tvProgressMessage.text = when (phase) {
                ArchiveManager.ProgressPhase.SCANNING -> "正在检查压缩包..."
                ArchiveManager.ProgressPhase.EXTRACTING -> "正在解压..."
            }
            if (total > 0L) {
                val ratio = completed.coerceIn(0L, total).toDouble() / total.toDouble()
                val percent = (ratio * 100.0).toInt().coerceIn(0, 100)
                progress.binding.progressBar.isIndeterminate = false
                progress.binding.progressBar.max = 1000
                progress.binding.progressBar.progress = (ratio * 1000.0).toInt().coerceIn(0, 1000)
                progress.binding.tvProgressPercent.text = "$percent%"
                progress.binding.tvProgressPercent.visibility = View.VISIBLE
            } else {
                progress.binding.progressBar.isIndeterminate = true
                if (phase == ArchiveManager.ProgressPhase.EXTRACTING && completed > 0L) {
                    progress.binding.tvProgressPercent.text =
                        "已处理 ${FileUtils.formatFileSize(completed)}"
                    progress.binding.tvProgressPercent.visibility = View.VISIBLE
                } else {
                    progress.binding.tvProgressPercent.visibility = View.GONE
                }
            }
        }
    }

    private fun needsArchivePassword(
        archive: File,
        error: Throwable,
        passwordAttempted: Boolean
    ): Boolean {
        if (error.message?.contains("无法清理") == true ||
            error.message?.contains("未能恢复") == true) return false
        val causes = generateSequence(error as Throwable?) { it.cause }
        return causes.any { cause ->
                cause is ArchivePasswordRequiredException ||
                cause.message?.contains("password", ignoreCase = true) == true ||
                cause.message?.contains("passphrase", ignoreCase = true) == true ||
                cause.message?.contains("decrypt", ignoreCase = true) == true ||
                (passwordAttempted && archive.extension.equals("7z", ignoreCase = true) &&
                    cause.message?.contains("checksum verification failed", ignoreCase = true) == true)
        }
    }

    private fun showOperationError(title: String, error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(error.message ?: "未知错误")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun defaultArchiveName(sources: List<File>): String =
        if (sources.size == 1) {
            sources.first().let { source ->
                if (source.isDirectory) source.name else source.nameWithoutExtension.ifBlank { source.name }
            }
        } else {
            "archive-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())}"
        }

    private fun stripArchiveExtension(name: String): String =
        listOf(".tar.bz2", ".tar.gz", ".tar.xz", ".zip", ".7z", ".tar")
            .firstOrNull { name.endsWith(it, ignoreCase = true) }
            ?.let { name.dropLast(it.length) }
            ?: name

    private fun isValidFileName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

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

    private fun navigateDestinationInto(directory: File) {
        val current = currentDirectory ?: return
        val state = DestinationNavigationState(current, directoryHistory.toList())
        if (loadDirectory(directory)) {
            destinationNavigationHistory += state
            directoryHistory += current
        }
    }

    private fun navigateDestinationUp() {
        val current = currentDirectory ?: return
        val state = DestinationNavigationState(current, directoryHistory.toList())
        val target: File
        val updatedHistory: List<File>
        if (directoryHistory.isNotEmpty()) {
            target = directoryHistory.last()
            updatedHistory = directoryHistory.dropLast(1)
        } else {
            target = current.parentFile ?: return
            updatedHistory = emptyList()
        }
        if (!target.exists() || !target.canRead()) return
        if (loadDirectory(target)) {
            destinationNavigationHistory += state
            directoryHistory.clear()
            directoryHistory.addAll(updatedHistory)
        }
    }

    private fun navigateBackInDestinationSelection(): Boolean {
        val state = destinationNavigationHistory.lastOrNull() ?: return false
        if (!loadDirectory(state.directory)) return true
        destinationNavigationHistory.removeAt(destinationNavigationHistory.lastIndex)
        directoryHistory.clear()
        directoryHistory.addAll(state.directoryHistory)
        return true
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
        if (pendingFileOperation != null) {
            if (!navigateBackInDestinationSelection()) {
                cancelDestinationSelection()
            }
        } else if (selectedPaths.isNotEmpty()) {
            exitSelectionMode()
        } else if (directoryHistory.isNotEmpty()) {
            goUpLevel()
        } else {
            super.onBackPressed()
        }
    }

}
