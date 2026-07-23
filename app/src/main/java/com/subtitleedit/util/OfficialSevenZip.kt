package com.subtitleedit.util

import androidx.annotation.Keep

@Keep
internal object OfficialSevenZip {
    init { System.loadLibrary("subtitleedit_7zip") }

    @Keep
    private external fun executeNative(
        arguments: Array<String>,
        capturePath: String?,
        workingDirectory: String?,
        stdoutFd: Int
    ): Int

    @Keep
    private external fun duplicateFdAboveStandardNative(fd: Int): Int

    @Synchronized
    fun execute(arguments: List<String>, capturePath: String? = null, workingDirectory: String? = null): Int =
        executeNative(arguments.toTypedArray(), capturePath, workingDirectory, -1)

    @Synchronized
    fun executeToFd(arguments: List<String>, stdoutFd: Int, capturePath: String? = null): Int {
        require(stdoutFd >= 3) { "stdout 文件描述符必须避开标准流" }
        return executeNative(arguments.toTypedArray(), capturePath, null, stdoutFd)
    }

    fun duplicateFdAboveStandard(fd: Int): Int {
        require(fd >= 0) { "文件描述符无效" }
        return duplicateFdAboveStandardNative(fd)
    }
}
