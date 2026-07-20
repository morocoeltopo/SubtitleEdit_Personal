package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityVocalSeparationSettingsBinding
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import java.io.File

class VocalSeparationSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVocalSeparationSettingsBinding
    private lateinit var settings: SettingsManager
    private var loading = false
    private var accessWarningShown = false

    private val vocalsModelPicker = modelPicker(VocalSeparationEngine.Stem.VOCALS)
    private val drumsModelPicker = modelPicker(VocalSeparationEngine.Stem.DRUMS)
    private val bassModelPicker = modelPicker(VocalSeparationEngine.Stem.BASS)
    private val otherModelPicker = modelPicker(VocalSeparationEngine.Stem.OTHER)
    private val generalModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::handleSelectedGeneralModel) }

    private fun modelPicker(stem: VocalSeparationEngine.Stem) = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSelectedModel(stem, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocalSeparationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsManager.getInstance(this)

        setupToolbar()
        setupListeners()
        loadSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "人声分离设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupListeners() {
        val modelMimeTypes = arrayOf("application/octet-stream", "application/onnx", "*/*")
        binding.tvModelHelp.setOnClickListener { showModelHelp() }
        binding.btnSelectGeneralModel.setOnClickListener { generalModelPicker.launch(modelMimeTypes) }
        binding.btnSelectVocalsModel.setOnClickListener { vocalsModelPicker.launch(modelMimeTypes) }
        binding.btnSelectDrumsModel.setOnClickListener { drumsModelPicker.launch(modelMimeTypes) }
        binding.btnSelectBassModel.setOnClickListener { bassModelPicker.launch(modelMimeTypes) }
        binding.btnSelectOtherModel.setOnClickListener { otherModelPicker.launch(modelMimeTypes) }
        binding.switchGraphOptimization.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.setDemixOrtGraphOptimizationEnabled(checked)
        }
        binding.switchCpuArena.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.setDemixOrtCpuArenaEnabled(checked)
        }
    }

    private fun loadSettings() {
        discardInaccessibleModelUris()
        updateGeneralModelUi()
        updateModelUi(VocalSeparationEngine.Stem.VOCALS, binding.tvVocalsModel)
        updateModelUi(VocalSeparationEngine.Stem.DRUMS, binding.tvDrumsModel)
        updateModelUi(VocalSeparationEngine.Stem.BASS, binding.tvBassModel)
        updateModelUi(VocalSeparationEngine.Stem.OTHER, binding.tvOtherModel)

        loading = true
        binding.switchGraphOptimization.isChecked = settings.isDemixOrtGraphOptimizationEnabled()
        binding.switchCpuArena.isChecked = settings.isDemixOrtCpuArenaEnabled()
        loading = false
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
            persistAndVerifyModel(uri)
            settings.setDemixModelUri(stem.fileSuffix, uri.toString())
            updateModelUi(stem, modelTextView(stem))
            OverwritingToast.makeText(this, "已选择 ${stem.displayName} 模型", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showError("选择 ${stem.displayName} 模型失败：${e.message}")
        }
    }

    private fun handleSelectedGeneralModel(uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".onnx", ignoreCase = true) || fileName.contains("_ft_", ignoreCase = true)) {
            OverwritingToast.makeText(
                this,
                "请选择通用四轨 HTDemucs ONNX 模型（例如 htdemucs_fp16weights.onnx）",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            persistAndVerifyModel(uri)
            settings.setDemixModelUri("general", uri.toString())
            updateGeneralModelUi()
            OverwritingToast.makeText(this, "已选择通用四轨模型", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showError("选择通用模型失败：${e.message}")
        }
    }

    private fun persistAndVerifyModel(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.statSize == 0L) throw IllegalStateException("模型文件为空")
        } ?: throw IllegalStateException("无法读取模型文件")
    }

    private fun updateGeneralModelUi() {
        val uriString = settings.getDemixModelUri("general")
        binding.tvGeneralModel.text = if (uriString.isBlank()) {
            "未选择通用四轨模型"
        } else {
            "${getFileName(Uri.parse(uriString))}\n支持一次推理输出多个音轨"
        }
    }

    private fun updateModelUi(stem: VocalSeparationEngine.Stem, textView: TextView) {
        val uriString = settings.getDemixModelUri(stem.fileSuffix)
        textView.text = if (uriString.isBlank()) {
            "未选择 ${stem.displayName} specialist 模型"
        } else {
            "${getFileName(Uri.parse(uriString))}\n已保存读取权限，将直接从原位置调用"
        }
    }

    private fun modelTextView(stem: VocalSeparationEngine.Stem): TextView = when (stem) {
        VocalSeparationEngine.Stem.VOCALS -> binding.tvVocalsModel
        VocalSeparationEngine.Stem.DRUMS -> binding.tvDrumsModel
        VocalSeparationEngine.Stem.BASS -> binding.tvBassModel
        VocalSeparationEngine.Stem.OTHER -> binding.tvOtherModel
    }

    private fun discardInaccessibleModelUris() {
        var discarded = false
        val modelKeys = listOf("general") + VocalSeparationEngine.Stem.entries.map { it.fileSuffix }
        modelKeys.forEach { modelKey ->
            val uriString = settings.getDemixModelUri(modelKey)
            if (uriString.isNotBlank() && !isSavedUriReadable(uriString)) {
                settings.setDemixModelUri(modelKey, "")
                discarded = true
            }
        }
        if (discarded && !accessWarningShown) {
            accessWarningShown = true
            OverwritingToast.makeText(this, "模型访问权限已失效，请重新选择模型文件", Toast.LENGTH_LONG).show()
        }
    }

    private fun isSavedUriReadable(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.isFile == true
        } else {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun getFileName(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return@runCatching cursor.getString(index)
        }
        uri.lastPathSegment ?: "unknown"
    }.getOrElse { uri.lastPathSegment ?: "unknown" }

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
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            Linkify.addLinks(this, Linkify.WEB_URLS)
            linksClickable = true
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("人声分离设置失败")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
}
