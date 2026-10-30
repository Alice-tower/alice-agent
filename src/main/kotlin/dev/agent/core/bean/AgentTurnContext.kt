package dev.agent.core.bean

/** 完整轮次的消息、工具结果及本次运行新增消息快照。 */
class AgentTurnContext {
    var message: AssistantMessage = AssistantMessage()
    var toolResults: List<ToolResultMessage> = emptyList()
    var context: AgentContext = AgentContext()
    var newMessages: List<AgentMessage> = emptyList()
}
