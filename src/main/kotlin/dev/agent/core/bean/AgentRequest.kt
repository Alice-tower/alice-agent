package dev.agent.core.bean

/** 一次任务的输入消息；字符串构造器创建一条用户消息。 */
class AgentRequest {
    var messages: List<AgentMessage> = emptyList()

    constructor()
    constructor(text: String) { messages = listOf(UserMessage(text)) }
}
