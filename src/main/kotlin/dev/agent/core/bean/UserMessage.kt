package dev.agent.core.bean

/** 用户输入，支持文本和图片。 */
class UserMessage : AgentMessage {
    override val role: String = "user"
    override var timestamp: Long = System.currentTimeMillis()
    var content: List<ContentBlock> = emptyList()

    constructor()
    constructor(text: String) { content = listOf(TextContent(text)) }

    override fun snapshot(): AgentMessage = BeanCopier().message(this)
}
