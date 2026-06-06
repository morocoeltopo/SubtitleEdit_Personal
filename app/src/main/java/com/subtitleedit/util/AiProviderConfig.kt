package com.subtitleedit.util

object AiProviderConfig {
    const val SILICONFLOW = "siliconflow"
    const val DEEPSEEK = "deepseek"
    const val OPENAI = "openai"

    data class Provider(
        val id: String,
        val displayName: String,
        val apiUrl: String,
        val defaultModel: String
    )

    val providers = listOf(
        Provider(
            id = SILICONFLOW,
            displayName = "硅基流动",
            apiUrl = "https://api.siliconflow.cn/v1/chat/completions",
            defaultModel = "deepseek-ai/DeepSeek-V3.2-Exp"
        ),
        Provider(
            id = DEEPSEEK,
            displayName = "DeepSeek",
            apiUrl = "https://api.deepseek.com/chat/completions",
            defaultModel = "deepseek-v4-flash"
        ),
        Provider(
            id = OPENAI,
            displayName = "OpenAI",
            apiUrl = "https://api.openai.com/v1/chat/completions",
            defaultModel = "gpt-5.4-mini"
        )
    )

    fun getProvider(id: String): Provider {
        return providers.firstOrNull { it.id == id } ?: providers.first()
    }

    fun indexOf(id: String): Int {
        return providers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
    }
}
