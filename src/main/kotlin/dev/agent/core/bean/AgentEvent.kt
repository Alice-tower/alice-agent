package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 按 type 填充对应字段的生命周期事件 Bean；对外通知前生成独立快照。 */
class AgentEvent {
    var type: AgentEventType = AgentEventType.AGENT_START
    var message: AgentMessage? = null
    var assistantMessageEvent: AssistantMessageEvent? = null
    var messages: List<AgentMessage> = emptyList()
    var toolResults: List<ToolResultMessage> = emptyList()
    var toolCallId: String = ""
    var toolName: String = ""
    var args: JsonElement = JsonObject(emptyMap())
    var result: AgentToolResult? = null
    var partialResult: AgentToolResult? = null
    var isError: Boolean = false
    var durationMs: Long? = null
}
