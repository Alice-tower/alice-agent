package dev.agent.core.bean

/** 模型流式回调事件；done/error 必须提供最终消息。 */
class AssistantMessageEvent {
    var type: String = "start"
    var partial: AssistantMessage? = null
    var message: AssistantMessage? = null
    var contentIndex: Int? = null
    var delta: String? = null
    var content: String? = null
    var toolCall: ToolCall? = null
    var reason: StopReason? = null

    val terminal: Boolean get() = type == "done" || type == "error"
    val finalMessage: AssistantMessage? get() = message ?: if (terminal) partial else null
}
