package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 执行后、完成事件之前的上下文快照。 */
class AfterToolCallContext {
    var assistantMessage: AssistantMessage = AssistantMessage()
    var toolCall: ToolCall = ToolCall()
    var args: JsonElement = JsonObject(emptyMap())
    var result: AgentToolResult = AgentToolResult()
    var isError: Boolean = false
    var context: AgentContext = AgentContext()
}
