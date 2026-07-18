package com.subtitleedit.util

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 设置管理器 - 保存和读取用户设置
 */
class SettingsManager private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "subtitle_edit_settings"
        
        private const val KEY_DEFAULT_ENCODING = "default_encoding"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_SOURCE_LANGUAGE = "ai_source_language"
        private const val KEY_AI_TARGET_LANGUAGE = "ai_target_language"
        private const val KEY_AI_TRANSLATION_PROMPT = "ai_translation_prompt"
        private const val KEY_WAVEFORM_CACHE_LOCATION = "waveform_cache_location"
        private const val KEY_LOOP_SELECTED_SUBTITLE = "loop_selected_subtitle"
        private const val KEY_CHECK_UPDATES_ON_STARTUP = "check_updates_on_startup"
        private const val KEY_WHISPER_ENCODER_PATH = "whisper_encoder_path"
        private const val KEY_WHISPER_DECODER_PATH = "whisper_decoder_path"
        private const val KEY_WHISPER_TOKENS_PATH = "whisper_tokens_path"
        private const val KEY_ASR_MODEL_TYPE = "asr_model_type"
        private const val KEY_SENSEVOICE_MODEL_PATH = "sensevoice_model_path"
        private const val KEY_SENSEVOICE_TOKENS_PATH = "sensevoice_tokens_path"
        private const val KEY_VAD_MODEL_PATH = "vad_model_path"
        private const val KEY_VAD_USE_BUILT_IN_MODEL = "vad_use_built_in_model"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
        private const val KEY_VAD_MIN_SILENCE_DURATION = "vad_min_silence_duration"
        private const val KEY_VAD_MIN_SPEECH_DURATION = "vad_min_speech_duration"
        private const val KEY_VAD_MAX_SPEECH_DURATION = "vad_max_speech_duration"
        private const val KEY_STT_FIXED_SEGMENT_SECONDS = "stt_fixed_segment_seconds"
        private const val KEY_STT_WHISPER_THREADS = "stt_whisper_threads"
        private const val KEY_STT_HOTWORDS_ENABLED = "stt_hotwords_enabled"
        private const val KEY_STT_HOTWORDS = "stt_hotwords"
        private const val KEY_STT_HOTWORDS_SCORE = "stt_hotwords_score"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_LANGUAGE = "tts_language"
        private const val KEY_DEMIX_VOCALS_MODEL_URI = "demix_vocals_model_uri"
        private const val KEY_DEMIX_DRUMS_MODEL_URI = "demix_drums_model_uri"
        private const val KEY_DEMIX_BASS_MODEL_URI = "demix_bass_model_uri"
        private const val KEY_DEMIX_OTHER_MODEL_URI = "demix_other_model_uri"
        private const val KEY_DEMIX_GENERAL_MODEL_URI = "demix_general_model_uri"

        const val WAVEFORM_CACHE_APP = "app_cache"
        const val WAVEFORM_CACHE_SOURCE = "source_dir"

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        const val ASR_MODEL_WHISPER = "whisper"
        const val ASR_MODEL_SENSEVOICE = "sensevoice"

        const val TTS_LANGUAGE_SYSTEM = "system"
        const val TTS_LANGUAGE_CHINESE = "zh-CN"
        const val TTS_LANGUAGE_JAPANESE = "ja-JP"
        const val TTS_LANGUAGE_ENGLISH = "en-US"
        const val TTS_LANGUAGE_AUTO = "auto"
        
        @Volatile private var instance: SettingsManager? = null
        
        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * 获取默认编码
     */
    fun getDefaultEncoding(): Charset {
        val encodingName = prefs.getString(KEY_DEFAULT_ENCODING, StandardCharsets.UTF_8.name())
        return try {
            Charset.forName(encodingName ?: StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            StandardCharsets.UTF_8
        }
    }
    
    /**
     * 设置默认编码
     */
    fun setDefaultEncoding(charset: Charset) {
        prefs.edit().putString(KEY_DEFAULT_ENCODING, charset.name()).apply()
    }
    
    /**
     * 获取 AI 平台
     */
    fun getAiProvider(): String {
        return prefs.getString(KEY_AI_PROVIDER, AiProviderConfig.SILICONFLOW) ?: AiProviderConfig.SILICONFLOW
    }

    /**
     * 设置 AI 平台
     */
    fun setAiProvider(provider: String) {
        prefs.edit().putString(KEY_AI_PROVIDER, provider).apply()
    }

    /**
     * 获取 AI API Key
     */
    fun getAiApiKey(): String {
        return getAiApiKey(getAiProvider())
    }
    
    /**
     * 设置 AI API Key
     */
    fun setAiApiKey(apiKey: String) {
        setAiApiKey(getAiProvider(), apiKey)
    }

    fun getAiApiKey(provider: String): String {
        return prefs.getString(providerKey(KEY_AI_API_KEY, provider), null)
            ?: if (provider == AiProviderConfig.SILICONFLOW) prefs.getString(KEY_AI_API_KEY, "") ?: "" else ""
    }

    fun setAiApiKey(provider: String, apiKey: String) {
        prefs.edit().putString(providerKey(KEY_AI_API_KEY, provider), apiKey).apply()
    }

    /**
     * 获取 AI 模型名称
     */
    fun getAiModel(): String {
        return getAiModel(getAiProvider())
    }
    
    /**
     * 设置 AI 模型名称
     */
    fun setAiModel(model: String) {
        setAiModel(getAiProvider(), model)
    }

    fun getAiModel(provider: String): String {
        val defaultModel = AiProviderConfig.getProvider(provider).defaultModel
        val stored = prefs.getString(providerKey(KEY_AI_MODEL, provider), null)
            ?: if (provider == AiProviderConfig.SILICONFLOW) {
                prefs.getString(KEY_AI_MODEL, defaultModel) ?: defaultModel
            } else {
                defaultModel
            }
        return stored.ifBlank { defaultModel }
    }

    fun setAiModel(provider: String, model: String) {
        prefs.edit().putString(providerKey(KEY_AI_MODEL, provider), model).apply()
    }

    /**
     * 获取 AI 翻译源语言
     */
    fun getAiSourceLanguage(): String {
        return prefs.getString(KEY_AI_SOURCE_LANGUAGE, "自动检测") ?: "自动检测"
    }
    
    /**
     * 设置 AI 翻译源语言
     */
    fun setAiSourceLanguage(language: String) {
        prefs.edit().putString(KEY_AI_SOURCE_LANGUAGE, language).apply()
    }
    
    /**
     * 获取 AI 翻译目标语言
     */
    fun getAiTargetLanguage(): String {
        return prefs.getString(KEY_AI_TARGET_LANGUAGE, "中文") ?: "中文"
    }
    
    /**
     * 设置 AI 翻译目标语言
     */
    fun setAiTargetLanguage(language: String) {
        prefs.edit().putString(KEY_AI_TARGET_LANGUAGE, language).apply()
    }

    fun getAiTranslationPrompt(): String =
        prefs.getString(KEY_AI_TRANSLATION_PROMPT, "") ?: ""

    fun setAiTranslationPrompt(prompt: String) {
        prefs.edit().putString(KEY_AI_TRANSLATION_PROMPT, prompt).apply()
    }
    
    /**
     * 获取波形缓存存放位置
     */
    fun getWaveformCacheLocation(): String =
        prefs.getString(KEY_WAVEFORM_CACHE_LOCATION, WAVEFORM_CACHE_APP) ?: WAVEFORM_CACHE_APP
    
    /**
     * 设置波形缓存存放位置
     */
    fun setWaveformCacheLocation(location: String) {
        prefs.edit().putString(KEY_WAVEFORM_CACHE_LOCATION, location).apply()
    }
    
    /**
     * 获取是否启用选中字幕循环播放
     */
    fun isLoopSelectedSubtitleEnabled(): Boolean =
        prefs.getBoolean(KEY_LOOP_SELECTED_SUBTITLE, false)
    
    /**
     * 设置是否启用选中字幕循环播放
     */
    fun setLoopSelectedSubtitleEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_LOOP_SELECTED_SUBTITLE, enabled).apply()

    /**
     * 获取 Whisper Encoder 文件路径
     */
    fun getWhisperEncoderPath(): String {
        return prefs.getString(KEY_WHISPER_ENCODER_PATH, "") ?: ""
    }

    /**
     * 设置 Whisper Encoder 文件路径
     */
    fun setWhisperEncoderPath(path: String) {
        prefs.edit().putString(KEY_WHISPER_ENCODER_PATH, path).apply()
    }

    /**
     * 获取 Whisper Decoder 文件路径
     */
    fun getWhisperDecoderPath(): String {
        return prefs.getString(KEY_WHISPER_DECODER_PATH, "") ?: ""
    }

    /**
     * 设置 Whisper Decoder 文件路径
     */
    fun setWhisperDecoderPath(path: String) {
        prefs.edit().putString(KEY_WHISPER_DECODER_PATH, path).apply()
    }

    /**
     * 获取 Whisper Tokens 文件路径
     */
    fun getWhisperTokensPath(): String {
        return prefs.getString(KEY_WHISPER_TOKENS_PATH, "") ?: ""
    }

    /**
     * 设置 Whisper Tokens 文件路径
     */
    fun setWhisperTokensPath(path: String) {
        prefs.edit().putString(KEY_WHISPER_TOKENS_PATH, path).apply()
    }

    fun getAsrModelType(): String = prefs.getString(KEY_ASR_MODEL_TYPE, ASR_MODEL_WHISPER)
        ?.takeIf { it == ASR_MODEL_WHISPER || it == ASR_MODEL_SENSEVOICE }
        ?: ASR_MODEL_WHISPER

    fun setAsrModelType(type: String) {
        prefs.edit().putString(KEY_ASR_MODEL_TYPE, type).apply()
    }

    fun getSenseVoiceModelPath(): String = prefs.getString(KEY_SENSEVOICE_MODEL_PATH, "") ?: ""

    fun setSenseVoiceModelPath(path: String) {
        prefs.edit().putString(KEY_SENSEVOICE_MODEL_PATH, path).apply()
    }

    fun getSenseVoiceTokensPath(): String = prefs.getString(KEY_SENSEVOICE_TOKENS_PATH, "") ?: ""

    fun setSenseVoiceTokensPath(path: String) {
        prefs.edit().putString(KEY_SENSEVOICE_TOKENS_PATH, path).apply()
    }

    /**
     * 获取 VAD 模型文件路径
     */
    fun getVadModelPath(): String {
        return prefs.getString(KEY_VAD_MODEL_PATH, "") ?: ""
    }

    /**
     * 设置 VAD 模型文件路径
     */
    fun setVadModelPath(path: String) {
        prefs.edit().putString(KEY_VAD_MODEL_PATH, path).apply()
    }

    /**
     * 是否使用内置 VAD 模型
     */
    fun isVadUseBuiltInModel(): Boolean {
        return if (prefs.contains(KEY_VAD_USE_BUILT_IN_MODEL)) {
            prefs.getBoolean(KEY_VAD_USE_BUILT_IN_MODEL, true)
        } else {
            getVadModelPath().isBlank()
        }
    }

    /**
     * 设置是否使用内置 VAD 模型
     */
    fun setVadUseBuiltInModel(useBuiltIn: Boolean) {
        prefs.edit().putBoolean(KEY_VAD_USE_BUILT_IN_MODEL, useBuiltIn).apply()
    }

    /**
     * 获取 VAD 阈值
     */
    fun getVadThreshold(): Float {
        return normalizeVadThreshold(prefs.getFloat(KEY_VAD_THRESHOLD, 0.3f))
    }

    /**
     * 设置 VAD 阈值
     */
    fun setVadThreshold(threshold: Float) {
        prefs.edit().putFloat(KEY_VAD_THRESHOLD, normalizeVadThreshold(threshold)).apply()
    }

    /**
     * 获取 VAD 最小静音时长
     */
    fun getVadMinSilenceDuration(): Float {
        return prefs.getFloat(KEY_VAD_MIN_SILENCE_DURATION, 0.3f)
    }

    /**
     * 设置 VAD 最小静音时长
     */
    fun setVadMinSilenceDuration(duration: Float) {
        prefs.edit().putFloat(KEY_VAD_MIN_SILENCE_DURATION, duration).apply()
    }

    /**
     * 获取 VAD 最小语音时长
     */
    fun getVadMinSpeechDuration(): Float {
        return prefs.getFloat(KEY_VAD_MIN_SPEECH_DURATION, 0.25f)
    }

    /**
     * 设置 VAD 最小语音时长
     */
    fun setVadMinSpeechDuration(duration: Float) {
        prefs.edit().putFloat(KEY_VAD_MIN_SPEECH_DURATION, duration).apply()
    }

    /**
     * 获取 VAD 最大语音时长
     */
    fun getVadMaxSpeechDuration(): Float {
        return prefs.getFloat(KEY_VAD_MAX_SPEECH_DURATION, 10.0f)
    }

    /**
     * 设置 VAD 最大语音时长
     */
    fun setVadMaxSpeechDuration(duration: Float) {
        prefs.edit().putFloat(KEY_VAD_MAX_SPEECH_DURATION, duration).apply()
    }

    fun getSpeechFixedSegmentSeconds(): Int {
        return prefs.getInt(KEY_STT_FIXED_SEGMENT_SECONDS, 30)
    }

    fun setSpeechFixedSegmentSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_STT_FIXED_SEGMENT_SECONDS, seconds.coerceIn(5, 120)).apply()
    }

    fun getSpeechWhisperThreads(): Int {
        return prefs.getInt(KEY_STT_WHISPER_THREADS, 4)
    }

    fun setSpeechWhisperThreads(threads: Int) {
        prefs.edit().putInt(KEY_STT_WHISPER_THREADS, threads.coerceIn(1, 8)).apply()
    }

    fun isSpeechHotwordsEnabled(): Boolean {
        return prefs.getBoolean(KEY_STT_HOTWORDS_ENABLED, false)
    }

    fun setSpeechHotwordsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STT_HOTWORDS_ENABLED, enabled).apply()
    }

    fun getSpeechHotwords(): String {
        return prefs.getString(KEY_STT_HOTWORDS, "") ?: ""
    }

    fun setSpeechHotwords(hotwords: String) {
        prefs.edit().putString(KEY_STT_HOTWORDS, hotwords).apply()
    }

    fun getSpeechHotwordsScore(): Float {
        return prefs.getFloat(KEY_STT_HOTWORDS_SCORE, 1.5f)
    }

    fun setSpeechHotwordsScore(score: Float) {
        prefs.edit().putFloat(KEY_STT_HOTWORDS_SCORE, score.coerceIn(0.5f, 5.0f)).apply()
    }

    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun shouldCheckUpdatesOnStartup(): Boolean {
        return prefs.getBoolean(KEY_CHECK_UPDATES_ON_STARTUP, true)
    }

    fun setCheckUpdatesOnStartup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHECK_UPDATES_ON_STARTUP, enabled).apply()
    }

    /** 空字符串表示跟随系统默认 TTS 引擎。 */
    fun getTtsEngine(): String = prefs.getString(KEY_TTS_ENGINE, "") ?: ""

    fun setTtsEngine(packageName: String) {
        prefs.edit().putString(KEY_TTS_ENGINE, packageName).apply()
    }

    fun getTtsLanguage(): String = prefs.getString(KEY_TTS_LANGUAGE, TTS_LANGUAGE_AUTO)
        ?.takeIf {
            it == TTS_LANGUAGE_SYSTEM || it == TTS_LANGUAGE_CHINESE ||
                it == TTS_LANGUAGE_JAPANESE || it == TTS_LANGUAGE_ENGLISH ||
                it == TTS_LANGUAGE_AUTO
        }
        ?: TTS_LANGUAGE_AUTO

    fun setTtsLanguage(language: String) {
        prefs.edit().putString(KEY_TTS_LANGUAGE, language).apply()
    }

    fun getDemixModelUri(stem: String): String = prefs.getString(demixModelKey(stem), "") ?: ""

    fun setDemixModelUri(stem: String, uri: String) {
        prefs.edit().putString(demixModelKey(stem), uri).apply()
    }

    private fun demixModelKey(stem: String): String = when (stem.lowercase()) {
        "general" -> KEY_DEMIX_GENERAL_MODEL_URI
        "vocals" -> KEY_DEMIX_VOCALS_MODEL_URI
        "drums" -> KEY_DEMIX_DRUMS_MODEL_URI
        "bass" -> KEY_DEMIX_BASS_MODEL_URI
        "other" -> KEY_DEMIX_OTHER_MODEL_URI
        else -> throw IllegalArgumentException("未知的人声分离音轨：$stem")
    }

    private fun providerKey(base: String, provider: String): String {
        return "${base}_$provider"
    }

    private fun normalizeVadThreshold(threshold: Float): Float {
        val clamped = threshold.coerceIn(0.1f, 0.9f)
        val steps = Math.round((clamped - 0.1f) / 0.05f).coerceIn(0, 16)
        return (10 + steps * 5) / 100f
    }
}
