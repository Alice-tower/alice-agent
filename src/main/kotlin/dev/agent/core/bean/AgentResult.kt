package dev.agent.core.bean

/** 一次任务新增消息的隔离快照。 */
class AgentResult {
    var messages: List<AgentMessage> = emptyList()
}
