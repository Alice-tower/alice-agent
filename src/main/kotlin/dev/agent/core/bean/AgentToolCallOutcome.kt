package dev.agent.core.bean

/** 完成参数准备、验证、执行及 Hook 后的调用结果。 */
class AgentToolCallOutcome {
    var toolCall: ToolCall = ToolCall()
    var result: AgentToolResult = AgentToolResult()
    var isError: Boolean = false
    var durationMs: Long? = null
}
