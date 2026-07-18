package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.subtitleedit.databinding.ActivityVocalSeparationBinding
import com.subtitleedit.demix.DemixOutputWriter
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.RuntimeLogManager
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class VocalSeparationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVocalSeparationBinding
    private lateinit var settings: SettingsManager
    private val selectedFiles = mutableListOf<SelectedMediaFile>()
    private var outputDirUri: Uri? = null
    private var separationJob: Job? = null
    private var isRunning = false
    private var isCancelled = false
    private val runtimeText = StringBuilder()

    private data class SelectedMediaFile(val uri: Uri, val fileName: String)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) handleSelectedFiles(uris) }

    private val outputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { handleOutputDirectory(it) } }

    private val vocalsModelPicker = modelPicker(VocalSeparationEngine.Stem.VOCALS)
    private val drumsModelPicker = modelPicker(VocalSeparationEngine.Stem.DRUMS)
    private val bassModelPicker = modelPicker(VocalSeparationEngine.Stem.BASS)
    private val otherModelPicker = modelPicker(VocalSeparationEngine.Stem.OTHER)
    private val generalModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSelectedGeneralModel(it) } }

    private fun modelPicker(stem: VocalSeparationEngine.Stem) = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSelectedModel(stem, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocalSeparationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsManager.getInstance(this)
        setupToolbar()
        setupButtons()
        setupLogScroll()
        loadState()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isRunning) {
                    AlertDialog.Builder(this@VocalSeparationActivity)
                        .setTitle("正在分离中")
                        .setMessage("人声分离正在进行，确定要返回吗？返回后任务将被取消。")
                        .setPositiveButton("返回并取消") { _, _ ->
                            cancelSeparation()
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .setNegativeButton("继续分离", null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!isRunning) loadState()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "人声分离"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupButtons() {
        val modelMimeTypes = arrayOf("application/octet-stream", "application/onnx", "*/*")
        binding.tvModelHelp.setOnClickListener { showModelHelp() }
        binding.btnSelectGeneralModel.setOnClickListener { generalModelPicker.launch(modelMimeTypes) }
        binding.btnSelectVocalsModel.setOnClickListener { vocalsModelPicker.launch(modelMimeTypes) }
        binding.btnSelectDrumsModel.setOnClickListener { drumsModelPicker.launch(modelMimeTypes) }
        binding.btnSelectBassModel.setOnClickListener { bassModelPicker.launch(modelMimeTypes) }
        binding.btnSelectOtherModel.setOnClickListener { otherModelPicker.launch(modelMimeTypes) }
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
        }
        binding.btnSelectOutputDir.setOnClickListener { outputDirLauncher.launch(outputDirUri) }
        binding.btnStart.setOnClickListener { startSeparation() }
        binding.btnCancel.setOnClickListener { confirmCancel() }
        listOf(binding.checkVocals, binding.checkDrums, binding.checkBass, binding.checkOther).forEach {
            it.setOnCheckedChangeListener { button, checked ->
                if (checked && selectedStems().size > 1 && !isModelConfigured("general")) {
                    button.isChecked = false
                    OverwritingToast.makeText(this, "选择两个及以上音轨必须先选择通用四轨模型", Toast.LENGTH_LONG).show()
                }
                updateStartButton()
            }
        }
    }

    private fun setupLogScroll() {
        binding.realtimeResultScroll.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun loadState() {
        updateGeneralModelUi()
        updateModelUi(VocalSeparationEngine.Stem.VOCALS, binding.tvVocalsModel)
        updateModelUi(VocalSeparationEngine.Stem.DRUMS, binding.tvDrumsModel)
        updateModelUi(VocalSeparationEngine.Stem.BASS, binding.tvBassModel)
        updateModelUi(VocalSeparationEngine.Stem.OTHER, binding.tvOtherModel)
        val hasGeneral = isModelConfigured("general")
        binding.checkVocals.isEnabled = hasGeneral || isModelConfigured(VocalSeparationEngine.Stem.VOCALS)
        binding.checkDrums.isEnabled = hasGeneral || isModelConfigured(VocalSeparationEngine.Stem.DRUMS)
        binding.checkBass.isEnabled = hasGeneral || isModelConfigured(VocalSeparationEngine.Stem.BASS)
        binding.checkOther.isEnabled = hasGeneral || isModelConfigured(VocalSeparationEngine.Stem.OTHER)
        if (!binding.checkVocals.isEnabled) binding.checkVocals.isChecked = false
        if (!binding.checkDrums.isEnabled) binding.checkDrums.isChecked = false
        if (!binding.checkBass.isEnabled) binding.checkBass.isChecked = false
        if (!binding.checkOther.isEnabled) binding.checkOther.isChecked = false
        if (!hasGeneral && selectedStems().size > 1) {
            var keptOne = false
            listOf(binding.checkVocals, binding.checkDrums, binding.checkBass, binding.checkOther).forEach { checkbox ->
                if (checkbox.isChecked && !keptOne) keptOne = true
                else if (checkbox.isChecked) checkbox.isChecked = false
            }
        }
        if (outputDirUri == null) setupDefaultOutputDir()
        updateStartButton()
    }

    private fun handleSelectedFiles(uris: List<Uri>) {
        selectedFiles.clear()
        selectedFiles += uris.map { SelectedMediaFile(it, getFileName(it)) }
        binding.tvSelectedFile.text = buildString {
            append("已选择 ${selectedFiles.size} 个文件：")
            selectedFiles.forEachIndexed { index, item -> append("\n${index + 1}. ${item.fileName}") }
        }
        updateStartButton()
    }

    private fun handleOutputDirectory(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { OverwritingToast.makeText(this, "目录权限保存失败：${it.message}", Toast.LENGTH_LONG).show() }
        outputDirUri = uri
        binding.tvOutputDir.text = DirectoryDisplayPath.fromUri(this, uri)
    }

    private fun setupDefaultOutputDir() {
        val path = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SubtitleEdit/Output"
        )
        path.mkdirs()
        outputDirUri = Uri.fromFile(path)
        binding.tvOutputDir.text = path.absolutePath
    }

    private fun handleSelectedModel(stem: VocalSeparationEngine.Stem, uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".onnx", ignoreCase = true) ||
            !fileName.contains(stem.fileSuffix, ignoreCase = true)) {
            OverwritingToast.makeText(
                this,
                "请选择 ${stem.displayName} specialist ONNX 模型（文件名应包含 ${stem.fileSuffix}）",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize == 0L) throw IllegalStateException("模型文件为空")
            } ?: throw IllegalStateException("无法读取模型文件")
            settings.setDemixModelUri(stem.fileSuffix, uri.toString())
            appendRuntimeLog("已选择 ${stem.displayName} 模型：$fileName（直接从文档 URI 调用，不复制）")
            loadState()
        } catch (e: Exception) {
            showError("选择 ${stem.displayName} 模型失败：${e.message}")
        }
    }

    private fun handleSelectedGeneralModel(uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".onnx", ignoreCase = true) || fileName.contains("_ft_", ignoreCase = true)) {
            OverwritingToast.makeText(this, "请选择通用四轨 HTDemucs ONNX 模型（例如 htdemucs_fp16weights.onnx）", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize == 0L) throw IllegalStateException("模型文件为空")
            } ?: throw IllegalStateException("无法读取模型文件")
            settings.setDemixModelUri("general", uri.toString())
            appendRuntimeLog("已选择通用四轨模型：$fileName（直接从文档 URI 调用，不复制）")
            loadState()
        } catch (e: Exception) {
            showError("选择通用模型失败：${e.message}")
        }
    }

    private fun updateStartButton() {
        binding.btnStart.isEnabled = !isRunning && selectedFiles.isNotEmpty() && selectedStems().isNotEmpty()
    }

    private fun startSeparation() {
        val output = outputDirUri
        val stems = selectedStems()
        val hasGeneral = isModelConfigured("general")
        val modelsAvailable = when {
            stems.size > 1 -> hasGeneral
            stems.size == 1 -> isModelConfigured(stems.first()) || hasGeneral
            else -> false
        }
        if (selectedFiles.isEmpty() || output == null || !modelsAvailable) {
            OverwritingToast.makeText(this, "请先选择音视频、模型和输出音轨", Toast.LENGTH_SHORT).show()
            return
        }
        val expected = selectedFiles.flatMap { file ->
            val base = file.fileName.substringBeforeLast(".")
            stems.map { "${base}_${it.fileSuffix}.wav" }
        }
        val conflicts = expected.filter { DemixOutputWriter.exists(this, output, it) }
        if (conflicts.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("文件名冲突")
                .setMessage("输出目录中已有同名音频文件。请选择处理方式。")
                .setPositiveButton("覆盖") { _, _ -> runSeparation(output, stems, true) }
                .setNeutralButton("自动重命名") { _, _ -> runSeparation(output, stems, false) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            runSeparation(output, stems, false)
        }
    }

    private fun runSeparation(
        output: Uri,
        stems: Set<VocalSeparationEngine.Stem>,
        overwrite: Boolean
    ) {
        if (isRunning) return
        isCancelled = false
        isRunning = true
        runtimeText.clear()
        binding.tvRealtimeResult.text = ""
        binding.btnStart.isEnabled = false
        binding.layoutProgress.visibility = View.VISIBLE
        binding.btnCancel.visibility = View.VISIBLE

        separationJob = lifecycleScope.launch {
            var success = 0
            try {
                appendRuntimeLog("开始人声分离")
                appendRuntimeLog("待处理文件：${selectedFiles.size} 个")
                appendRuntimeLog("输出音轨：${stems.joinToString { it.displayName }}")
                appendRuntimeLog("输出目录：${binding.tvOutputDir.text}")
                if (stems.size > 1) {
                    appendRuntimeLog("推理路线：通用四轨模型，一次推理输出 ${stems.size} 条音轨")
                    appendRuntimeLog("通用模型：${getFileName(Uri.parse(settings.getDemixModelUri("general")))}")
                } else {
                    val stem = stems.first()
                    if (isModelConfigured(stem)) {
                        appendRuntimeLog("推理路线：优先使用 ${stem.displayName} FT specialist")
                    } else {
                        appendRuntimeLog("推理路线：未配置 ${stem.displayName} FT，回退通用模型")
                    }
                }
                val overwrittenBaseNames = mutableSetOf<String>()
                for ((index, selected) in selectedFiles.withIndex()) {
                    if (isCancelled) break
                    val baseName = selected.fileName.substringBeforeLast(".").lowercase(Locale.ROOT)
                    val shouldOverwrite = overwrite && overwrittenBaseNames.add(baseName)
                    val result = processOne(selected, index + 1, selectedFiles.size, output, stems, shouldOverwrite)
                    if (result) success++
                }
                if (isCancelled) {
                    appendRuntimeLog("任务已取消，已删除本次任务缓存和未完成输出")
                    OverwritingToast.makeText(this@VocalSeparationActivity, "已取消", Toast.LENGTH_SHORT).show()
                } else {
                    appendRuntimeLog("人声分离完成：成功 $success/${selectedFiles.size}")
                    showProgress("全部处理完成", 100)
                    OverwritingToast.makeText(this@VocalSeparationActivity, "分离完成：成功 $success/${selectedFiles.size}", Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                appendRuntimeLog("协程已取消")
            } catch (e: Exception) {
                if (!isCancelled) {
                    appendRuntimeLog("任务失败：${e.message}")
                    showError(e.message ?: "人声分离失败")
                }
            } finally {
                isRunning = false
                binding.layoutProgress.visibility = View.GONE
                binding.btnCancel.visibility = View.GONE
                updateStartButton()
            }
        }
    }

    private suspend fun processOne(
        selected: SelectedMediaFile,
        index: Int,
        count: Int,
        output: Uri,
        stems: Set<VocalSeparationEngine.Stem>,
        overwrite: Boolean
    ): Boolean {
        val taskCache = File(cacheDir, "vocal_separation_${System.currentTimeMillis()}_${System.nanoTime()}").apply { mkdirs() }
        val prefix = "[$index/$count]"
        return try {
            showProgress("$prefix 正在准备", 0)
            appendRuntimeLog("$prefix 开始处理：${selected.fileName}")
            val input = withContext(Dispatchers.IO) { copyUriToCache(selected.uri, selected.fileName, taskCache) }
                ?: throw IllegalStateException("复制输入文件失败")
            appendRuntimeLog("$prefix 输入缓存：${input.name}（${formatBytes(input.length())}）")
            if (isCancelled) return false

            showProgress("$prefix 正在提取 44.1kHz 双声道音频", 5)
            appendRuntimeLog("$prefix 使用 FFmpeg 提取 44.1kHz、双声道、32-bit float PCM")
            val pcm = withContext(Dispatchers.IO) { convertToPcm(input, taskCache) }
                ?: throw IllegalStateException("FFmpeg 音频预处理失败")
            appendRuntimeLog("$prefix PCM 缓存：${pcm.name}（${formatBytes(pcm.length())}）")
            if (isCancelled) return false

            val tempOutput = File(taskCache, "outputs").apply { mkdirs() }
            showProgress("$prefix 正在运行 ONNX 分离", 10)
            val useGeneral = stems.size > 1
            val singleStem = stems.firstOrNull()
            val modelKey = if (useGeneral) "general"
            else if (singleStem != null && isModelConfigured(singleStem)) singleStem.fileSuffix else "general"
            val modelUri = Uri.parse(settings.getDemixModelUri(modelKey))
            val modelLabel = if (useGeneral) "通用四轨模型" else if (modelKey == "general") "通用模型回退" else "${singleStem?.displayName} specialist"
            appendRuntimeLog("$prefix 使用$modelLabel：${getFileName(modelUri)}")
            val result = withContext(Dispatchers.IO) {
                withDirectModelPath(modelUri) { directPath, size ->
                    val engine = VocalSeparationEngine(
                        directPath,
                        getFileName(modelUri),
                        size,
                        log = { message -> appendRuntimeLog("$prefix $modelLabel: $message") }
                    )
                    engine.separate(
                        pcm,
                        tempOutput,
                        selected.fileName.substringBeforeLast("."),
                        stems,
                        { isCancelled }
                    ) { done, total ->
                        val progress = 10 + (done.toDouble() / total * 85).toInt()
                        runOnUiThread { showProgress("$prefix $modelLabel：$done/$total", progress) }
                    }
                }
            }
            for (stem in stems) {
                val temp = result.outputFiles.getValue(stem)
                val name = withContext(Dispatchers.IO) {
                    DemixOutputWriter.copy(this@VocalSeparationActivity, temp, output, temp.name, overwrite)
                }
                appendRuntimeLog("$prefix 已保存 ${stem.displayName}：$name")
            }
            appendRuntimeLog("$prefix 完成：${result.chunkCount} 个分块，耗时 ${formatDuration(result.elapsedMs)}")
            showProgress("$prefix 输出完成", 100)
            true
        } catch (e: InterruptedException) {
            appendRuntimeLog("$prefix 已取消：${e.message}")
            false
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { taskCache.deleteRecursively() }
        }
    }

    private fun convertToPcm(input: File, cache: File): File? {
        val output = File(cache, "${input.nameWithoutExtension}_44k_stereo.f32le")
        if (output.exists()) output.delete()
        val command = "-y -i \"${input.absolutePath}\" -vn -ar 44100 -ac 2 -f f32le -c:a pcm_f32le \"${output.absolutePath}\""
        val session = FFmpegKit.execute(command)
        return if (session.returnCode.isValueSuccess && output.isFile && output.length() > 0) output else null
    }

    private fun copyUriToCache(uri: Uri, name: String, cache: File): File? = runCatching {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(cache, "input_$safeName")
        contentResolver.openInputStream(uri)?.use { input -> output.outputStream().use { input.copyTo(it, 1024 * 1024) } }
            ?: throw IllegalStateException("无法读取输入文件")
        output
    }.getOrNull()

    private fun selectedStems(): LinkedHashSet<VocalSeparationEngine.Stem> = linkedSetOf<VocalSeparationEngine.Stem>().apply {
        if (binding.checkVocals.isChecked) add(VocalSeparationEngine.Stem.VOCALS)
        if (binding.checkDrums.isChecked) add(VocalSeparationEngine.Stem.DRUMS)
        if (binding.checkBass.isChecked) add(VocalSeparationEngine.Stem.BASS)
        if (binding.checkOther.isChecked) add(VocalSeparationEngine.Stem.OTHER)
    }

    private fun modelUri(stem: VocalSeparationEngine.Stem): String =
        settings.getDemixModelUri(stem.fileSuffix)

    private fun updateModelUi(stem: VocalSeparationEngine.Stem, textView: android.widget.TextView) {
        val uriString = modelUri(stem)
        textView.text = if (uriString.isBlank()) {
            "未选择 ${stem.displayName} specialist 模型"
        } else {
            val uri = Uri.parse(uriString)
            "${getFileName(uri)}\n已保存读取权限，将直接从原位置调用"
        }
    }

    private fun updateGeneralModelUi() {
        val uriString = settings.getDemixModelUri("general")
        binding.tvGeneralModel.text = if (uriString.isBlank()) {
            "未选择通用四轨模型"
        } else {
            "${getFileName(Uri.parse(uriString))}\n支持一次推理输出多个音轨"
        }
    }

    private fun isModelConfigured(stem: VocalSeparationEngine.Stem): Boolean {
        return isModelConfigured(stem.fileSuffix)
    }

    private fun isModelConfigured(modelKey: String): Boolean {
        val uriString = settings.getDemixModelUri(modelKey)
        if (uriString.isBlank()) return false
        return runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).isFile
            } else {
                contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }
        }.getOrDefault(false)
    }

    private fun <T> withDirectModelPath(uri: Uri, block: (path: String, size: Long?) -> T): T {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            return block(file.absolutePath, file.length())
        }
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开模型：${getFileName(uri)}")
        descriptor.use {
            val size = it.statSize.takeIf { value -> value >= 0L }
            return block("/proc/self/fd/${it.fd}", size)
        }
    }

    private fun confirmCancel() {
        if (!isRunning) return
        AlertDialog.Builder(this)
            .setTitle("确认取消")
            .setMessage("人声分离正在进行，确定要取消吗？")
            .setPositiveButton("取消分离") { _, _ -> cancelSeparation() }
            .setNegativeButton("继续分离", null)
            .show()
    }

    private fun cancelSeparation() {
        if (!isRunning) return
        isCancelled = true
        FFmpegKit.cancel()
        separationJob?.cancel()
        appendRuntimeLog("收到取消请求，正在停止当前处理")
    }

    private fun showProgress(status: String, progress: Int) {
        binding.tvProgressStatus.text = status
        binding.progressIndicator.progress = progress.coerceIn(0, 100)
    }

    private fun appendRuntimeLog(message: String) {
        RuntimeLogManager.i("VocalSeparation", message)
        val render: () -> Unit = {
            val line = "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())}] $message"
            runtimeText.appendLine(line)
            if (runtimeText.length > 16000) runtimeText.delete(0, runtimeText.length - 16000)
            binding.tvRealtimeResult.text = runtimeText.toString()
            binding.realtimeResultScroll.post { binding.realtimeResultScroll.fullScroll(View.FOCUS_DOWN) }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) render() else runOnUiThread(render)
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("人声分离失败").setMessage(message).setPositiveButton("确定", null).show()
    }

    private fun showModelHelp() {
        val message = """
            模型分为两类：

            1. 通用模型
            htdemucs_fp16weights.onnx，一次推理可以输出 Vocals、Drums、Bass、Other 多个音轨。选择两个或以上音轨时必须使用通用模型；单音轨没有对应 FT 模型时也会回退到通用模型。

            下载地址：
            https://huggingface.co/StemSplitio/htdemucs-onnx/tree/main

            2. FT 单音轨模型
            Vocals、Drums、Bass、Other 各有一个 specialist 模型。单音轨时优先使用对应 FT 模型，通常具有更好的对应音轨质量，但每个模型只能作为对应音轨使用。

            下载地址：
            https://huggingface.co/StemSplitio/htdemucs-ft-onnx/tree/main

            模型只保存文件访问权限，应用会直接从原文件位置读取，不复制到缓存。
        """.trimIndent()
        val dialog = AlertDialog.Builder(this)
            .setTitle("人声分离模型帮助")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.apply {
            Linkify.addLinks(this, Linkify.WEB_URLS)
            linksClickable = true
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "unknown"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        bytes >= 1024L * 1024L -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(bytes / 1024.0)} KB"
    }

    private fun formatDuration(ms: Long): String = "${"%.1f".format(ms / 1000.0)} 秒"

    override fun onDestroy() {
        if (isRunning) {
            isCancelled = true
            FFmpegKit.cancel()
        }
        separationJob?.cancel()
        super.onDestroy()
    }
}
