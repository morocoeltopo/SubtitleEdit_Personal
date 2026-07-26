package com.subtitleedit.editor

import android.app.AlertDialog
import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.text.InputType
import android.view.Choreographer
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleHighlightCursor
import com.subtitleedit.util.TimeUtils
import java.io.File
import java.util.Locale

internal class EditorPlaybackController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val isAudioFile: Boolean,
    private val audioFileName: () -> String?,
    private val subtitles: () -> List<SubtitleEntry>,
    private val isSourceViewMode: () -> Boolean,
    private val onPlayingSubtitleChanged: (Int?) -> Unit,
    private val showMessage: (String) -> Unit
) {
    var currentPositionMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    private var isPlaying = false
    private var isUserSeeking = false
    private var playbackSpeed = 1.0f
    private var mediaPlayer: MediaPlayer? = null
    private var limitedPlaybackEntry: SubtitleEntry? = null
    private var isLimitedRangePlaybackActive = false

    private val highlightCursor = SubtitleHighlightCursor()

    // 每个 vsync 刷新一次即可：屏幕最高 120Hz，更高频率只是白烧 CPU 和 Binder IPC。
    private val frameCallback = Choreographer.FrameCallback { onProgressFrame() }
    private var progressScheduled = false

    private var lastPlayPauseShowsPause: Boolean? = null
    private var lastTotalTimeText: String? = null

    private fun onProgressFrame() {
        progressScheduled = false
        val player = mediaPlayer ?: return
        if (!player.isPlaying) return
        isPlaying = true
        renderPlayPauseIcon()

        if (!isUserSeeking) {
            val position = player.currentPosition.toLong()
            if (position >= currentPositionMs || currentPositionMs - position > 200) {
                currentPositionMs = position
            }
        }
        renderProgress(currentPositionMs)
        highlightSubtitleAtTime(currentPositionMs)

        val rangeTarget = limitedPlaybackEntry
        if (isLimitedRangePlaybackActive && rangeTarget != null) {
            when {
                currentPositionMs >= rangeTarget.endTime -> {
                    if (SettingsManager.getInstance(context).isLoopSelectedSubtitleEnabled()) {
                        player.seekTo(rangeTarget.startTime.toInt())
                        updatePlayerUiAtKnownPosition(rangeTarget.startTime)
                    } else {
                        player.pause()
                        player.seekTo(rangeTarget.endTime.toInt())
                        isPlaying = false
                        isLimitedRangePlaybackActive = false
                        stopProgressUpdate()
                        updatePlayerUiAtKnownPosition(rangeTarget.endTime)
                        return
                    }
                }
                currentPositionMs < rangeTarget.startTime -> {
                    player.seekTo(rangeTarget.startTime.toInt())
                    updatePlayerUiAtKnownPosition(rangeTarget.startTime)
                }
            }
        }

        startProgressUpdate()
    }

    fun bind() {
        if (!isAudioFile) return

        mediaPlayer = MediaPlayer().apply {
            setOnCompletionListener {
                this@EditorPlaybackController.isPlaying = false
                stopProgressUpdate()
                updatePlayerUi()
            }
            setOnErrorListener { _, what, extra ->
                showMessage("播放错误：$what, $extra")
                this@EditorPlaybackController.isPlaying = false
                updatePlayerUi()
                true
            }
        }

        audioFileName()?.let { binding.tvAudioFileName.text = it }
        bindTimelinePlaybackCallbacks()
        bindPlayerControls()
        updatePlayerUi()
    }

    fun prepare(audioFile: File) {
        mediaPlayer?.reset()
        mediaPlayer?.setDataSource(audioFile.absolutePath)
        mediaPlayer?.prepare()
        durationMs = mediaPlayer?.duration?.toLong() ?: 0L
        if (playbackSpeed != 1.0f) {
            mediaPlayer?.playbackParams = PlaybackParams().setSpeed(playbackSpeed)
        }
        updatePlayerUi()
    }

    fun seekTo(timeMs: Long) {
        isLimitedRangePlaybackActive = false
        val clampedTime = timeMs.coerceIn(0L, durationMs)

        mediaPlayer?.seekTo(clampedTime.toInt())
        currentPositionMs = clampedTime
        highlightSubtitleAtTime(currentPositionMs)
        updatePlayerUi()

        if (isPlaying) startProgressUpdate()
    }

    fun release() {
        stopProgressUpdate()
        mediaPlayer?.let { player ->
            if (player.isPlaying) player.stop()
            player.release()
        }
        mediaPlayer = null
    }

    private fun bindTimelinePlaybackCallbacks() {
        binding.waveformTimelineView.onTimelineClickListener = { position ->
            seekTo((durationMs * position).toLong())
            updatePlayerUi()
        }
        binding.waveformTimelineView.onDraggedViewportPlayheadCorrection = { positionMs ->
            correctPlaybackAfterViewportDrag(positionMs)
        }
        binding.waveformTimelineView.onLimitedPlaybackRangeChange = { subtitleIndex ->
            limitedPlaybackEntry = subtitleIndex?.let { subtitles().getOrNull(it) }
            isLimitedRangePlaybackActive = false
        }
        binding.waveformTimelineView.onLimitedPlaybackStartRequest = { subtitleIndex ->
            startLimitedRangePlayback(subtitleIndex)
        }
        binding.waveformTimelineView.onSubtitleStartSeekRequest = { positionMs ->
            seekTo(positionMs)
        }
        binding.waveformTimelineView.onLimitedPlaybackRangeOutOfView = {
            if (isLimitedRangePlaybackActive) {
                isLimitedRangePlaybackActive = false
                mediaPlayer?.let { player ->
                    if (player.isPlaying) player.pause()
                }
                isPlaying = false
                stopProgressUpdate()
                updatePlayerUi()
            }
        }
    }

    private fun bindPlayerControls() {
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val targetTime = (durationMs * progress / 1000).toLong()
                    currentPositionMs = targetTime
                    binding.tvCurrentTime.text = TimeUtils.formatForDisplay(targetTime)
                    val wavePosition = if (durationMs > 0) {
                        currentPositionMs.toFloat() / durationMs
                    } else {
                        0f
                    }
                    binding.waveformTimelineView.setCurrentPosition(wavePosition)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekTo(currentPositionMs)
            }
        })
        binding.tvPlaybackSpeed.setOnClickListener { showSpeedInputDialog() }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                stopProgressUpdate()
            } else {
                isLimitedRangePlaybackActive = false
                player.start()
                isPlaying = true
                startProgressUpdate()
            }
            updatePlayerUi()
        }
    }

    private fun correctPlaybackAfterViewportDrag(positionMs: Long) {
        val correctedPositionMs = positionMs.coerceIn(0L, durationMs)
        val wasPlaying = mediaPlayer?.isPlaying == true
        mediaPlayer?.seekTo(correctedPositionMs.toInt())
        isPlaying = wasPlaying
        updatePlayerUiAtKnownPosition(correctedPositionMs)
        if (wasPlaying) startProgressUpdate() else stopProgressUpdate()
    }

    private fun startLimitedRangePlayback(subtitleIndex: Int) {
        val target = subtitles().getOrNull(subtitleIndex) ?: return
        val player = mediaPlayer ?: return
        limitedPlaybackEntry = target
        isLimitedRangePlaybackActive = true
        player.seekTo(target.startTime.toInt())
        if (!player.isPlaying) player.start()
        isPlaying = true
        updatePlayerUiAtKnownPosition(target.startTime)
        startProgressUpdate()
    }

    private fun updatePlayerUiAtKnownPosition(positionMs: Long) {
        val clampedPositionMs = positionMs.coerceIn(0L, durationMs)
        currentPositionMs = clampedPositionMs
        highlightSubtitleAtTime(clampedPositionMs)
        val previousUserSeeking = isUserSeeking
        isUserSeeking = true
        updatePlayerUi()
        isUserSeeking = previousUserSeeking
        binding.seekBar.progress = if (durationMs > 0L) {
            (clampedPositionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }
    }

    /** 字幕列表结构或时间轴变化后调用，否则高亮游标可能停在旧下标上。 */
    fun invalidateHighlightCache() {
        highlightCursor.invalidate()
    }

    private fun highlightSubtitleAtTime(timeMs: Long) {
        if (isSourceViewMode()) return
        val index = highlightCursor.resolve(subtitles(), timeMs)
        onPlayingSubtitleChanged(if (index >= 0) index else null)
    }

    /** 热路径：只写随播放位置变化的三处，不再向 MediaPlayer 多要 duration / isPlaying。 */
    private fun renderProgress(positionMs: Long) {
        binding.tvCurrentTime.text = TimeUtils.formatForDisplay(positionMs)
        if (!isUserSeeking) {
            binding.seekBar.progress = if (durationMs > 0) {
                (positionMs * 1000 / durationMs).toInt().coerceIn(0, 1000)
            } else {
                0
            }
        }
        val wavePosition = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        binding.waveformTimelineView.setCurrentPosition(wavePosition)
    }

    private fun renderPlayPauseIcon() {
        if (lastPlayPauseShowsPause == isPlaying) return
        lastPlayPauseShowsPause = isPlaying
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun renderTotalTime() {
        val text = TimeUtils.formatForDisplay(durationMs)
        if (lastTotalTimeText == text) return
        lastTotalTimeText = text
        binding.tvTotalTime.text = text
    }

    private fun updatePlayerUi() {
        mediaPlayer?.let { player ->
            if (!isUserSeeking) {
                val position = player.currentPosition.toLong()
                if (position >= currentPositionMs || currentPositionMs - position > 200) {
                    currentPositionMs = position
                }
            }
            durationMs = player.duration.toLong().takeIf { it > 0 } ?: durationMs
            isPlaying = player.isPlaying
        }

        renderPlayPauseIcon()
        renderTotalTime()
        renderProgress(currentPositionMs)
    }

    private fun startProgressUpdate() {
        if (progressScheduled) return
        progressScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopProgressUpdate() {
        if (!progressScheduled) return
        progressScheduled = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun showSpeedInputDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatPlaybackSpeedValue(playbackSpeed))
            hint = "例如：0.5、1.0、1.5、2.0"
            selectAll()
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(context)
            .setTitle("设置播放速率")
            .setMessage("请输入倍数（0.25 ~ 4.0）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val speed = input.text?.toString()?.trim()?.toFloatOrNull()
                when {
                    speed == null -> showMessage("请输入有效数字")
                    speed < 0.25f || speed > 4.0f -> showMessage("速率范围：0.25 ~ 4.0")
                    else -> applyPlaybackSpeed(speed)
                }
            }
            .setNegativeButton("取消", null)
            .show()

        input.postDelayed({
            val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        val label = if (speed == speed.toLong().toFloat()) {
            "${speed.toLong()}×"
        } else {
            formatPlaybackSpeedValue(speed) + "×"
        }
        binding.tvPlaybackSpeed.text = label

        mediaPlayer?.let { player ->
            try {
                player.playbackParams = PlaybackParams().setSpeed(speed)
            } catch (e: Exception) {
                showMessage("设置速率失败：${e.message}")
            }
        }
        showMessage("播放速率已设置为 $label")
    }

    private fun formatPlaybackSpeedValue(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
}
