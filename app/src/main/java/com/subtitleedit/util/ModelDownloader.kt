package com.subtitleedit.util

import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ModelDownloader {
    const val SENSEVOICE_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
    const val DEMIX_GENERAL_MODEL_URL =
        "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx"

    const val SENSEVOICE_DIRECTORY_NAME =
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    const val SEPARATION_DIRECTORY_NAME = "separation"
    private const val SENSEVOICE_ARCHIVE_NAME = "$SENSEVOICE_DIRECTORY_NAME.tar.bz2"
    private const val DEMIX_MODEL_NAME = "htdemucs_fp16weights.onnx"
    private const val MIN_ONNX_SIZE = 1024L * 1024L
    private const val BUFFER_SIZE = 1024 * 1024
    private const val EXTRACTION_INPUT_BUFFER_SIZE = 64 * 1024
    private const val EXTRACTION_BUFFER_SIZE = 64 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 10_000
    private const val MAX_EXTRACTED_BYTES = 8L * 1024L * 1024L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val senseVoiceMutex = Mutex()
    private val whisperMutex = Mutex()
    private val demixMutex = Mutex()

    data class Progress(
        val message: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L
    )

    data class SenseVoiceFiles(
        val model: File,
        val tokens: File
    )

    data class WhisperFiles(
        val encoder: File,
        val decoder: File,
        val tokens: File
    )

    data class WhisperModelOption(
        val id: String,
        val displayName: String,
        val directoryName: String,
        val url: String,
        val sizeLabel: String
    )

    val WHISPER_MODELS = listOf(
        WhisperModelOption(
            id = "tiny",
            displayName = "Tiny",
            directoryName = "sherpa-onnx-whisper-tiny",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            sizeLabel = "约 257 MB"
        ),
        WhisperModelOption(
            id = "small",
            displayName = "Small",
            directoryName = "sherpa-onnx-whisper-small",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            sizeLabel = "约 1.25 GB"
        ),
        WhisperModelOption(
            id = "large-v3",
            displayName = "Large v3",
            directoryName = "sherpa-onnx-whisper-large-v3",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-large-v3.tar.bz2",
            sizeLabel = "约 1.8 GB"
        ),
        WhisperModelOption(
            id = "turbo",
            displayName = "Turbo",
            directoryName = "sherpa-onnx-whisper-turbo",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2",
            sizeLabel = "约 1 GB"
        )
    )

    @Suppress("DEPRECATION")
    fun modelsDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "SubtitleEdit/models"
    )

    suspend fun downloadSenseVoice(
        onProgress: (Progress) -> Unit
    ): SenseVoiceFiles = senseVoiceMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelsDir = requireModelsDirectory()
            val targetDir = File(modelsDir, SENSEVOICE_DIRECTORY_NAME)
            recoverSenseVoiceBackup(targetDir)
            findSenseVoiceFiles(targetDir)?.let {
                onProgress(Progress("检测到本地 SenseVoice 模型，跳过下载并直接导入"))
                return@withContext it
            }

            val archive = File(modelsDir, SENSEVOICE_ARCHIVE_NAME)
            if (!archive.isFile || archive.length() == 0L) {
                downloadFile(SENSEVOICE_MODEL_URL, archive, "正在下载 SenseVoice 模型", onProgress)
            }

            val stagingDir = File(modelsDir, ".sensevoice_extracting")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (!stagingDir.mkdirs()) throw IOException("无法创建 SenseVoice 解压临时目录")

            try {
                onProgress(Progress("正在解压 SenseVoice 模型", 0L, archive.length()))
                extractTarBz2(
                    archive = archive,
                    outputDir = stagingDir,
                    progressMessage = "正在解压 SenseVoice 模型",
                    shouldWrite = ::isSenseVoiceRequiredFile,
                    onProgress = onProgress
                )
                val stagedFiles = findSenseVoiceFiles(stagingDir)
                    ?: throw IOException("压缩包中未找到可用的 SenseVoice ONNX 模型和 tokens.txt")

                val modelRoot = directChildContaining(stagingDir, stagedFiles.model)
                val tokensRoot = directChildContaining(stagingDir, stagedFiles.tokens)
                val sourceRoot = if (modelRoot == tokensRoot) modelRoot else stagingDir
                installSenseVoiceDirectory(sourceRoot, targetDir)
                if (stagingDir.exists()) stagingDir.deleteRecursively()

                val installedFiles = findSenseVoiceFiles(targetDir)
                    ?: throw IOException("SenseVoice 模型解压完成，但模型文件校验失败")
                archive.delete()
                onProgress(Progress("SenseVoice 模型已下载并解压"))
                installedFiles
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                archive.delete()
                throw e
            }
        }
    }

    suspend fun downloadWhisper(
        option: WhisperModelOption,
        onProgress: (Progress) -> Unit
    ): WhisperFiles = whisperMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelsDir = requireModelsDirectory()
            val targetDir = File(modelsDir, option.directoryName)
            recoverWhisperBackup(targetDir, option.id)
            findWhisperFiles(targetDir, option.id)?.let {
                onProgress(Progress("检测到本地 ${option.displayName} 模型，跳过下载并直接导入"))
                return@withContext it
            }

            val archive = File(modelsDir, "${option.directoryName}.tar.bz2")
            if (!archive.isFile || archive.length() == 0L) {
                downloadFile(
                    option.url,
                    archive,
                    "正在下载 Whisper ${option.displayName} 模型",
                    onProgress
                )
            }

            val stagingDir = File(modelsDir, ".whisper_${option.id}_extracting")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (!stagingDir.mkdirs()) throw IOException("无法创建 Whisper 解压临时目录")

            try {
                val progressMessage = "正在解压 Whisper ${option.displayName} 模型"
                onProgress(Progress(progressMessage, 0L, archive.length()))
                extractTarBz2(
                    archive = archive,
                    outputDir = stagingDir,
                    progressMessage = progressMessage,
                    shouldWrite = ::isWhisperRequiredFile,
                    onProgress = onProgress
                )
                val stagedFiles = findWhisperFiles(stagingDir, option.id)
                    ?: throw IOException("压缩包中未找到可用的 Whisper encoder、decoder 和 tokens.txt")

                val roots = listOf(
                    directChildContaining(stagingDir, stagedFiles.encoder),
                    directChildContaining(stagingDir, stagedFiles.decoder),
                    directChildContaining(stagingDir, stagedFiles.tokens)
                ).distinct()
                val sourceRoot = roots.singleOrNull() ?: stagingDir
                installWhisperDirectory(sourceRoot, targetDir, option.id)
                if (stagingDir.exists()) stagingDir.deleteRecursively()

                val installedFiles = findWhisperFiles(targetDir, option.id)
                    ?: throw IOException("Whisper 模型解压完成，但模型文件校验失败")
                archive.delete()
                onProgress(Progress("Whisper ${option.displayName} 模型已下载并解压"))
                installedFiles
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                archive.delete()
                throw e
            }
        }
    }

    suspend fun downloadDemixGeneralModel(
        onProgress: (Progress) -> Unit
    ): File = demixMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelsDir = requireModelsDirectory()
            val separationDir = File(modelsDir, SEPARATION_DIRECTORY_NAME)
            if ((!separationDir.exists() && !separationDir.mkdirs()) || !separationDir.isDirectory) {
                throw IOException("无法创建人声分离模型目录：${separationDir.absolutePath}")
            }
            val target = File(separationDir, DEMIX_MODEL_NAME)
            if (target.isFile && target.length() >= MIN_ONNX_SIZE) {
                onProgress(Progress("检测到本地人声分离模型，跳过下载并直接导入"))
                return@withContext target
            }
            val legacyTarget = File(modelsDir, DEMIX_MODEL_NAME)
            if (legacyTarget.isFile && legacyTarget.length() >= MIN_ONNX_SIZE) {
                if (!legacyTarget.renameTo(target)) {
                    legacyTarget.copyTo(target, overwrite = true)
                    legacyTarget.delete()
                }
                onProgress(Progress("已将本地人声分离模型迁移到 separation 目录并直接导入"))
                return@withContext target
            }
            downloadFile(
                DEMIX_GENERAL_MODEL_URL,
                target,
                "正在下载人声分离模型",
                onProgress,
                minimumSize = MIN_ONNX_SIZE
            )
            if (!target.isFile || target.length() < MIN_ONNX_SIZE) {
                target.delete()
                throw IOException("下载的人声分离模型文件无效")
            }
            onProgress(Progress("人声分离模型下载完成"))
            target
        }
    }

    private fun requireModelsDirectory(): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            throw IOException("请先授予应用“所有文件访问权限”，再下载模型")
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IOException("外部存储当前不可写")
        }
        val directory = modelsDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建 ${directory.absolutePath}")
        }
        if (!directory.isDirectory || !directory.canWrite()) {
            throw IOException("模型目录不可写：${directory.absolutePath}")
        }
        return directory
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        message: String,
        onProgress: (Progress) -> Unit,
        minimumSize: Long = 1L
    ) {
        destination.parentFile?.listFiles { file ->
            file.name.startsWith("${destination.name}.part.")
        }?.forEach { it.delete() }
        val partFile = File(
            destination.parentFile,
            "${destination.name}.part.${System.currentTimeMillis()}.${System.nanoTime()}"
        )
        val backupFile = File(destination.parentFile, "${destination.name}.backup")
        if (backupFile.exists()) {
            if (destination.exists()) backupFile.delete() else backupFile.renameTo(destination)
        }
        var completed = false
        var backupCreated = false
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SubtitleEdit-Android")
                .build()
            val call = client.newCall(request)
            executeDownload(call) { response ->
                    if (!response.isSuccessful) {
                        throw IOException("模型下载失败：HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("模型下载响应为空")
                    val total = body.contentLength()
                    var downloaded = 0L
                    var lastReportAt = 0L
                    body.byteStream().use { input ->
                        BufferedOutputStream(FileOutputStream(partFile), BUFFER_SIZE).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                if (call.isCanceled()) throw CancellationException("模型下载已取消")
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastReportAt >= 250L || downloaded == total) {
                                    onProgress(Progress(message, downloaded, total))
                                    lastReportAt = now
                                }
                            }
                        }
                    }
                    if (downloaded <= 0L || total > 0L && downloaded != total) {
                        throw IOException("模型文件下载不完整")
                    }
            }

            if (partFile.length() < minimumSize) {
                throw IOException("下载的模型文件大小异常")
            }
            if (destination.exists()) {
                backupFile.delete()
                if (!destination.renameTo(backupFile)) {
                    throw IOException("无法备份旧模型文件")
                }
                backupCreated = true
            }
            try {
                if (!partFile.renameTo(destination)) {
                    partFile.copyTo(destination, overwrite = true)
                    partFile.delete()
                }
                if (!destination.isFile || destination.length() < minimumSize) {
                    throw IOException("安装后的模型文件校验失败")
                }
                backupFile.delete()
                completed = true
            } catch (e: Exception) {
                destination.delete()
                if (backupCreated && !backupFile.renameTo(destination)) {
                    e.addSuppressed(IOException("新模型安装失败，旧模型也无法恢复"))
                }
                throw e
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            if (!completed) partFile.delete()
        }
    }

    private suspend fun executeDownload(
        call: Call,
        block: (Response) -> Unit
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    block(response)
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    response.close()
                }
            }
        })
    }

    private suspend fun extractTarBz2(
        archive: File,
        outputDir: File,
        progressMessage: String,
        shouldWrite: (String) -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        val rootPath = outputDir.canonicalFile.path + File.separator
        val archiveSize = archive.length().coerceAtLeast(1L)
        var entryCount = 0
        var extractedBytes = 0L
        var lastCompressedBytes = -1L
        var lastProgressAt = 0L
        FileInputStream(archive).use { fileInput ->
            BufferedInputStream(fileInput, EXTRACTION_INPUT_BUFFER_SIZE).use { bufferedInput ->
                BZip2CompressorInputStream(bufferedInput, true).use { bzipInput ->
                    TarArchiveInputStream(bzipInput).use { tarInput ->
                        fun reportProgress(force: Boolean = false) {
                            val compressedBytes = if (force) {
                                archiveSize
                            } else {
                                fileInput.channel.position().coerceIn(0L, archiveSize)
                            }
                            val now = System.currentTimeMillis()
                            if (force ||
                                compressedBytes != lastCompressedBytes && now - lastProgressAt >= 200L) {
                                onProgress(
                                    Progress(
                                        progressMessage,
                                        compressedBytes,
                                        archiveSize
                                    )
                                )
                                lastCompressedBytes = compressedBytes
                                lastProgressAt = now
                            }
                        }

                        val extractionBuffer = ByteArray(EXTRACTION_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tarInput.nextTarEntry ?: break
                            entryCount++
                            if (entryCount > MAX_ARCHIVE_ENTRIES) {
                                throw IOException("压缩包文件数量异常")
                            }
                            if (entry.isSymbolicLink || entry.isLink) continue
                            val output = File(outputDir, entry.name).canonicalFile
                            if (output != outputDir.canonicalFile && !output.path.startsWith(rootPath)) {
                                throw IOException("压缩包包含不安全路径：${entry.name}")
                            }
                            if (entry.isDirectory) {
                                reportProgress()
                                continue
                            }
                            if (!entry.isFile) continue
                            if (entry.size < 0L || entry.size > MAX_EXTRACTED_BYTES - extractedBytes) {
                                throw IOException("压缩包解压大小异常")
                            }
                            val writeEntry = shouldWrite(output.name)
                            val fileOutput = if (writeEntry) {
                                output.parentFile?.let { parent ->
                                    if (!parent.exists() && !parent.mkdirs()) {
                                        throw IOException("无法创建模型目录：${parent.name}")
                                    }
                                }
                                BufferedOutputStream(FileOutputStream(output), EXTRACTION_BUFFER_SIZE)
                            } else {
                                null
                            }
                            fileOutput.use { destination ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = tarInput.read(extractionBuffer)
                                    if (read < 0) break
                                    destination?.write(extractionBuffer, 0, read)
                                    extractedBytes += read
                                    if (extractedBytes > MAX_EXTRACTED_BYTES) {
                                        throw IOException("压缩包解压内容过大")
                                    }
                                    reportProgress()
                                }
                            }
                            reportProgress()
                        }
                        reportProgress(force = true)
                    }
                }
            }
        }
    }

    private fun isSenseVoiceRequiredFile(fileName: String): Boolean =
        fileName.equals("model.int8.onnx", ignoreCase = true) ||
            fileName.equals("model.onnx", ignoreCase = true) ||
            fileName.equals("tokens.txt", ignoreCase = true)

    private fun isWhisperRequiredFile(fileName: String): Boolean =
        fileName.endsWith("tokens.txt", ignoreCase = true) ||
            fileName.endsWith(".onnx", ignoreCase = true) &&
            (fileName.contains("encoder", ignoreCase = true) ||
                fileName.contains("decoder", ignoreCase = true))

    private fun findSenseVoiceFiles(root: File): SenseVoiceFiles? {
        if (!root.isDirectory) return null
        val files = runCatching { root.walkTopDown().filter { it.isFile }.toList() }.getOrNull()
            ?: return null
        val model = files
            .filter {
                (it.name.equals("model.int8.onnx", ignoreCase = true) ||
                    it.name.equals("model.onnx", ignoreCase = true)) &&
                    it.length() >= MIN_ONNX_SIZE
            }
            .minWithOrNull(
                compareBy<File> { senseVoiceModelPriority(it.name) }
                    .thenBy { it.absolutePath.length }
            ) ?: return null
        val tokens = files
            .filter { it.name.equals("tokens.txt", ignoreCase = true) && it.length() > 0L }
            .minByOrNull { tokenDistance(model, it) }
            ?: return null
        return SenseVoiceFiles(model, tokens)
    }

    private fun senseVoiceModelPriority(fileName: String): Int = when {
        fileName.equals("model.int8.onnx", ignoreCase = true) -> 0
        fileName.equals("model.onnx", ignoreCase = true) -> 1
        else -> 2
    }

    private fun findWhisperFiles(root: File, expectedStem: String): WhisperFiles? {
        if (!root.isDirectory) return null
        val files = runCatching { root.walkTopDown().filter { it.isFile }.toList() }.getOrNull()
            ?: return null
        data class OnnxCandidate(val file: File, val stem: String, val int8: Boolean)
        data class TokenCandidate(val file: File, val stem: String)
        data class ModelPair(val encoder: OnnxCandidate, val decoder: OnnxCandidate)

        fun parseOnnx(file: File, kind: String): OnnxCandidate? {
            if (!file.extension.equals("onnx", ignoreCase = true) || file.length() < MIN_ONNX_SIZE) {
                return null
            }
            val name = file.name.lowercase()
            val markers = listOf("-$kind", "_$kind")
            val marker = markers.firstOrNull { name.contains(it) }
            val stem = when {
                marker != null -> name.substringBefore(marker)
                name.startsWith(kind) -> ""
                else -> return null
            }
            return OnnxCandidate(file, stem, name.contains("int8"))
        }

        fun parseTokens(file: File): TokenCandidate? {
            if (!file.name.endsWith("tokens.txt", ignoreCase = true) || file.length() <= 0L) return null
            val name = file.name.lowercase()
            val stem = when {
                name == "tokens.txt" -> ""
                name.endsWith("-tokens.txt") -> name.removeSuffix("-tokens.txt")
                name.endsWith("_tokens.txt") -> name.removeSuffix("_tokens.txt")
                else -> return null
            }
            return TokenCandidate(file, stem)
        }

        val encoders = files.mapNotNull { parseOnnx(it, "encoder") }
        val decoders = files.mapNotNull { parseOnnx(it, "decoder") }
        val tokens = files.mapNotNull(::parseTokens)
        val samePrecisionPairs = encoders.flatMap { encoder ->
            decoders.filter { decoder ->
                decoder.file.parentFile == encoder.file.parentFile &&
                    decoder.stem == encoder.stem && decoder.int8 == encoder.int8
            }.map { ModelPair(encoder, it) }
        }
        val pairs = if (samePrecisionPairs.isNotEmpty()) {
            samePrecisionPairs
        } else {
            encoders.flatMap { encoder ->
                decoders.filter { decoder ->
                    decoder.file.parentFile == encoder.file.parentFile && decoder.stem == encoder.stem
                }.map { ModelPair(encoder, it) }
            }
        }
        val expected = expectedStem.lowercase()
        val sortedPairs = pairs.sortedWith(
            compareBy<ModelPair> {
                when {
                    it.encoder.stem == expected && it.encoder.int8 && it.decoder.int8 -> 0
                    it.encoder.int8 && it.decoder.int8 -> 1
                    it.encoder.stem == expected -> 2
                    else -> 3
                }
            }.thenBy { it.encoder.file.absolutePath }
        )
        for (pair in sortedPairs) {
            val token = tokens
                .filter {
                    it.file.parentFile == pair.encoder.file.parentFile &&
                        (it.stem == pair.encoder.stem || it.stem.isBlank())
                }
                .minWithOrNull(
                    compareBy<TokenCandidate> { if (it.stem == pair.encoder.stem) 0 else 1 }
                        .thenBy { it.file.absolutePath }
                ) ?: continue
            return WhisperFiles(pair.encoder.file, pair.decoder.file, token.file)
        }
        return null
    }

    private fun tokenDistance(model: File, tokens: File): Int {
        if (model.parentFile == tokens.parentFile) return 0
        return kotlin.math.abs(model.absolutePath.length - tokens.absolutePath.length) + 1
    }

    private fun directChildContaining(root: File, file: File): File {
        var current = file.parentFile ?: return root
        if (current == root) return root
        while (current.parentFile != null && current.parentFile != root) {
            current = current.parentFile
        }
        return if (current.parentFile == root) current else root
    }

    private fun installSenseVoiceDirectory(source: File, destination: File) {
        val backup = File(destination.parentFile, ".sensevoice_backup")
        backup.deleteRecursively()
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("无法备份旧的 SenseVoice 模型目录")
        }
        try {
            moveDirectory(source, destination)
            if (findSenseVoiceFiles(destination) == null) {
                throw IOException("安装后的 SenseVoice 模型校验失败")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            throw e
        }
    }

    private fun recoverSenseVoiceBackup(destination: File) {
        val backup = File(destination.parentFile, ".sensevoice_backup")
        if (!backup.exists()) return
        if (findSenseVoiceFiles(destination) != null) {
            backup.deleteRecursively()
            return
        }
        if (findSenseVoiceFiles(backup) != null) {
            destination.deleteRecursively()
            if (!backup.renameTo(destination)) {
                moveDirectory(backup, destination)
            }
        } else {
            backup.deleteRecursively()
        }
    }

    private fun installWhisperDirectory(source: File, destination: File, modelId: String) {
        val backup = File(destination.parentFile, ".whisper_${modelId}_backup")
        backup.deleteRecursively()
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("无法备份旧的 Whisper 模型目录")
        }
        try {
            moveDirectory(source, destination)
            if (findWhisperFiles(destination, modelId) == null) {
                throw IOException("安装后的 Whisper 模型校验失败")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            throw e
        }
    }

    private fun recoverWhisperBackup(destination: File, modelId: String) {
        val backup = File(destination.parentFile, ".whisper_${modelId}_backup")
        if (!backup.exists()) return
        if (findWhisperFiles(destination, modelId) != null) {
            backup.deleteRecursively()
            return
        }
        if (findWhisperFiles(backup, modelId) != null) {
            destination.deleteRecursively()
            if (!backup.renameTo(destination)) {
                moveDirectory(backup, destination)
            }
        } else {
            backup.deleteRecursively()
        }
    }

    private fun moveDirectory(source: File, destination: File) {
        if (source.renameTo(destination)) return
        if (!source.copyRecursively(destination, overwrite = true)) {
            destination.deleteRecursively()
            throw IOException("无法安装解压后的模型")
        }
        source.deleteRecursively()
    }
}
