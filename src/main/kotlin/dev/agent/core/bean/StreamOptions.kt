package dev.agent.core.bean

import dev.agent.core.api.CancellationToken
import dev.agent.core.model.PayloadHook
import dev.agent.core.model.ResponseHook
import dev.agent.core.model.ProviderEventHook
import kotlinx.serialization.json.JsonObject

/** 模型请求选项；本地凭据、取消令牌和回调不会进入代理 JSON。 */
class StreamOptions {
    var signal: CancellationToken? = null
    var apiKey: String? = null
    var reasoning: ThinkingLevel? = null
    var temperature: Double? = null
    var maxTokens: Int? = null
    var samplingParams: JsonObject? = null
    var cacheRetention: String? = null
    var sessionId: String? = null
    var headers: Map<String, String> = emptyMap()
    var metadata: JsonObject? = null
    var transport: String = "auto"
    var thinkingBudgets: Map<String, Int>? = null
    var maxRetryDelayMs: Long? = null
    var identity: JsonObject? = null
    var onPayload: PayloadHook? = null
    var onResponse: ResponseHook? = null
    var onProviderStreamEvent: ProviderEventHook? = null
}
