package com.subtitleedit.editor

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedAudioFile(
    val file: File,
    val wasFixed: Boolean
)

internal class EditorAudioFilePreparer(
    private val cacheDir: File
) {
    private var tempFixedWavFile: File? = null

    suspend fun prepare(audioFile: File): PreparedAudioFile {
        val preparedFile = withContext(Dispatchers.IO) {
            val session = FFprobeKit.getMediaInformation(audioFile.absolutePath)
            val startTime = session.getMediaInformation()?.getStartTime()?.toDoubleOrNull() ?: 0.0

            if (startTime <= 0.001) {
                return@withContext audioFile
            }

            Log.w(TAG, "音频 start time 不为 0：$startTime，开始转换为 WAV")
            val wavFile = try {
                File.createTempFile("audio_fixed_", ".wav", cacheDir)
            } catch (e: Exception) {
                Log.e(TAG, "创建临时 WAV 文件失败，使用原文件", e)
                return@withContext audioFile
            }

            try {
                val command = "-y -i \"${audioFile.absolutePath}\" " +
                    "-c:a pcm_s16le -ar 44100 -ac 2 \"${wavFile.absolutePath}\""
                val ffmpegSession = FFmpegKit.execute(command)

                if (ffmpegSession.getReturnCode()?.isValueSuccess() == true && wavFile.length() > 44L) {
                    Log.d(TAG, "WAV 转换成功：${wavFile.absolutePath}")
                    wavFile
                } else {
                    Log.e(TAG, "WAV 转换失败或输出为空，使用原文件")
                    if (!wavFile.delete()) {
                        tempFixedWavFile = wavFile
                        Log.w(TAG, "无法删除转换失败的临时 WAV：${wavFile.absolutePath}")
                    }
                    audioFile
                }
            } catch (e: Exception) {
                if (wavFile.exists() && !wavFile.delete()) {
                    tempFixedWavFile = wavFile
                    Log.w(TAG, "无法删除转换失败的临时 WAV：${wavFile.absolutePath}")
                }
                Log.e(TAG, "WAV 转换异常，使用原文件", e)
                audioFile
            }
        }

        val wasFixed = preparedFile != audioFile
        if (wasFixed) {
            tempFixedWavFile = preparedFile
        }
        return PreparedAudioFile(preparedFile, wasFixed)
    }

    fun release() {
        tempFixedWavFile?.let { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "无法删除临时修复 WAV：${file.absolutePath}")
            }
        }
        tempFixedWavFile = null
    }

    private companion object {
        const val TAG = "EditorActivity"
    }
}
