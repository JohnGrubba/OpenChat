package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ModelInfo

object ModelCatalog {
    val openCodeGo: List<ModelInfo> = listOf(
        ModelInfo("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", supportsVision = true, builtIn = true),
        ModelInfo("deepseek-v4-pro", "DeepSeek V4 Pro", builtIn = true),
        ModelInfo("gpt-6-luna", "GPT 6 Luna", ApiFamily.OPENAI_RESPONSES, supportsVision = true, builtIn = true),
        ModelInfo("gpt-5.6-luna", "GPT 5.6 Luna", ApiFamily.OPENAI_RESPONSES, supportsVision = true, builtIn = true),
        ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", ApiFamily.ANTHROPIC, supportsVision = true, builtIn = true),
        ModelInfo("gemini-4-argon", "Gemini 4 Argon", ApiFamily.GEMINI, supportsVision = true, builtIn = true),
        ModelInfo("grok-4.7", "Grok 4.7", supportsVision = true, builtIn = true),
        ModelInfo("glm-5.3-flash", "GLM-5.3-Flash", supportsVision = true, builtIn = true),
        ModelInfo("glm-5.3", "GLM-5.3", builtIn = true),
        ModelInfo("glm-5.2", "GLM-5.2", builtIn = true),
        ModelInfo("kimi-k3.1-pro", "Kimi K3.1 Pro", supportsVision = true, builtIn = true),
        ModelInfo("kimi-k3", "Kimi K3", supportsVision = true, builtIn = true),
        ModelInfo("kimi-k2.7-code", "Kimi K2.7 Code", supportsVision = true, builtIn = true),
        ModelInfo("longcat-2.0", "LongCat-2.0", builtIn = true),
        ModelInfo("mimo-v2.6-flash", "MiMo-V2.6-Flash", supportsVision = true, builtIn = true),
        ModelInfo("mimo-v2.6-pro", "MiMo-V2.6-Pro", supportsVision = true, builtIn = true),
        ModelInfo("mimo-v2.5", "MiMo-V2.5", supportsVision = true, builtIn = true),
        ModelInfo("mimo-v2.5-pro", "MiMo-V2.5-Pro", supportsVision = true, builtIn = true),
        ModelInfo("minimax-m3.1-flash", "MiniMax M3.1 Flash", supportsVision = true, builtIn = true),
        ModelInfo("minimax-m3", "MiniMax M3", supportsVision = true, builtIn = true),
        ModelInfo("muse-spark-1.3-contributor", "Muse Spark 1.3 Contributor", supportsVision = true, builtIn = true),
        ModelInfo("muse-spark-1.2-contributor", "Muse Spark 1.2 Contributor", supportsVision = true, builtIn = true),
        ModelInfo("qwen3.8-max", "Qwen3.8 Max", ApiFamily.ANTHROPIC, supportsVision = true, builtIn = true),
        ModelInfo("qwen3.8-flash", "Qwen3.8 Flash", ApiFamily.ANTHROPIC, supportsVision = true, builtIn = true),
        ModelInfo("qwen3.7-plus", "Qwen3.7 Plus", ApiFamily.ANTHROPIC, supportsVision = true, builtIn = true),
        ModelInfo("hy4-preview", "Hy4 preview", builtIn = true),
        ModelInfo("hy3", "Hy3", builtIn = true),
        ModelInfo("step-5-preview", "Step 5 Preview", supportsVision = true, builtIn = true),
        ModelInfo("beam-free", "Beam Free", builtIn = true),
        ModelInfo("iceman-contributor-free", "Iceman Contributor Free", builtIn = true),
    )

    val fallback: List<ModelInfo> = listOf(
        ModelInfo("gpt-4o", "GPT-4o", supportsVision = true),
        ModelInfo("gpt-4o-mini", "GPT-4o mini", supportsVision = true),
        ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", supportsVision = true),
        ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", supportsVision = true),
    )

    fun forEndpoint(isOpenCodeGo: Boolean): List<ModelInfo> =
        if (isOpenCodeGo) openCodeGo else fallback

    fun find(id: String, models: List<ModelInfo> = openCodeGo): ModelInfo? =
        models.firstOrNull { it.id == id }

    fun displayName(id: String, models: List<ModelInfo> = openCodeGo): String =
        find(id, models)?.name ?: id
}
