package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject

/** 模型响应快照；stopReason 决定工具执行和后续轮次。 */
class AssistantMessage : AgentMessage {
    override val role: String = "assistant"
    override var timestamp: Long = System.currentTimeMillis()
    var content: List<ContentBlock> = emptyList()
    var api: String = "unknown"
    var provider: String = "unknown"
    var model: String = "unknown"
    var usage: Usage = Usage()
    var stopReason: StopReason = StopReason.STOP
    var errorMessage: String? = null
    var thinkingLevel: ThinkingLevel = ThinkingLevel.OFF
    var providerThinkingLevel: String? = null
    var extra: JsonObject = JsonObject(emptyMap())

    override fun snapshot(): AgentMessage = BeanCopier().message(this)
}
