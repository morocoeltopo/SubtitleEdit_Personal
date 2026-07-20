package com.subtitleedit.util

import androidx.annotation.Keep
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

@Keep
internal object NativeTarBz2Extractor {
    private const val LIBRARY_NAME = "model_archive_jni"
    private const val CANCELLED_RESULT = "__SUBTITLEEDIT_NATIVE_CANCELLED__"

    @Volatile
    private var unavailableCause: Throwable? = try {
        System.loadLibrary(LIBRARY_NAME)
        null
    } catch (e: UnsatisfiedLinkError) {
        e
    } catch (e: SecurityException) {
        e
    }

    val isLibraryLoaded: Boolean
        get() = unavailableCause == null

    internal class NativeUnavailableException(
        message: String,
        cause: Throwable? = null
    ) : IOException(message, cause)

    @Keep
    interface Callback {
        @Keep
        fun shouldExtract(fileName: String): Boolean

        @Keep
        fun onProgress(compressedBytes: Long, totalBytes: Long)
    }

    suspend fun extract(
        archive: File,
        outputDir: File,
        maxEntries: Int,
        maxExtractedBytes: Long,
        callback: Callback
    ) {
        unavailableCause?.let {
            throw NativeUnavailableException("原生解压库不可用", it)
        }
        currentCoroutineContext().ensureActive()

        val task = try {
            nativeCreateTask()
        } catch (e: UnsatisfiedLinkError) {
            throw markUnavailable("创建原生解压任务失败，原生组件不完整", e)
        }
        if (task == 0L) throw IOException("无法创建原生解压任务")

        var primaryFailure: Throwable? = null
        try {
            supervisorScope {
                val nativeCallbackReached = AtomicBoolean(false)
                val guardedCallback = object : Callback {
                    override fun shouldExtract(fileName: String): Boolean {
                        nativeCallbackReached.set(true)
                        return callback.shouldExtract(fileName)
                    }

                    override fun onProgress(compressedBytes: Long, totalBytes: Long) {
                        nativeCallbackReached.set(true)
                        callback.onProgress(compressedBytes, totalBytes)
                    }
                }
                val worker = async(Dispatchers.IO) {
                    try {
                        nativeExtract(
                            task = task,
                            archivePath = archive.absolutePath,
                            outputPath = outputDir.absolutePath,
                            maxEntries = maxEntries,
                            maxExtractedBytes = maxExtractedBytes,
                            callback = guardedCallback
                        )
                    } catch (e: UnsatisfiedLinkError) {
                        if (!nativeCallbackReached.get()) {
                            throw markUnavailable("调用原生解压器失败，原生组件不完整", e)
                        }
                        throw IOException("原生解压过程中发生链接错误", e)
                    }
                }

                val result = try {
                    worker.await()
                } catch (e: CancellationException) {
                    val cancelFailure = runCatching { nativeCancel(task) }.exceptionOrNull()
                    withContext(NonCancellable) { worker.join() }
                    cancelFailure?.let(e::addSuppressed)
                    throw e
                }

                when (result) {
                    null -> Unit
                    CANCELLED_RESULT -> throw CancellationException("模型解压已取消")
                    else -> throw IOException(result)
                }
            }
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            try {
                nativeDestroyTask(task)
            } catch (e: UnsatisfiedLinkError) {
                val destroyFailure = IOException("释放原生解压任务失败，原生组件不完整", e)
                val failure = primaryFailure
                if (failure != null) {
                    failure.addSuppressed(destroyFailure)
                } else {
                    throw destroyFailure
                }
            } catch (destroyFailure: Throwable) {
                val failure = primaryFailure
                if (failure != null) {
                    failure.addSuppressed(destroyFailure)
                } else {
                    throw destroyFailure
                }
            }
        }
    }

    private fun markUnavailable(
        message: String,
        cause: UnsatisfiedLinkError
    ): NativeUnavailableException {
        unavailableCause = cause
        return NativeUnavailableException(message, cause)
    }

    @Keep
    private external fun nativeCreateTask(): Long

    @Keep
    private external fun nativeExtract(
        task: Long,
        archivePath: String,
        outputPath: String,
        maxEntries: Int,
        maxExtractedBytes: Long,
        callback: Callback
    ): String?

    @Keep
    private external fun nativeCancel(task: Long)

    @Keep
    private external fun nativeDestroyTask(task: Long)
}
