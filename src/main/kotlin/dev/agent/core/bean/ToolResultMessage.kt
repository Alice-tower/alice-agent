package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 工具执行结果，写入历史并交回模型。 */
class ToolResultMessage : AgentMessage {
    override val role: String = "toolResult"
    override var timestamp: Long = System.currentTimeMillis()
    var toolCallId: String = ""
    var toolName: String = ""
    var content: List<ContentBlock> = emptyList()
    var details: JsonElement = JsonObject(emptyMap())
    var isError: Boolean = false
    var usage: Usage? = null
    var durationMs: Long? = null

    override fun snapshot(): AgentMessage = BeanCopier().message(this)
}
