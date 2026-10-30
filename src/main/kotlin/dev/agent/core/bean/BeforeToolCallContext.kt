package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 参数验证后的执行前上下文；args 可替换且替换后不再验证。 */
class BeforeToolCallContext {
    var assistantMessage: AssistantMessage = AssistantMessage()
    var toolCall: ToolCall = ToolCall()
    var args: JsonElement = JsonObject(emptyMap())
    var context: AgentContext = AgentContext()
}
