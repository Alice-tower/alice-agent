package dev.agent.core.bean

import dev.agent.core.tool.AgentTool

/** 运行上下文快照；tools 为执行策略引用，messages 为隔离的消息数据。 */
class AgentContext {
    var messages: List<AgentMessage> = emptyList()
    var tools: List<AgentTool> = emptyList()
}
