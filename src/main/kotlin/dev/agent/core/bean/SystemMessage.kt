package dev.agent.core.bean

/** 系统指令与工具声明增量；sections 中 null 表示删除。 */
class SystemMessage : AgentMessage {
    override val role: String = "system"
    override var timestamp: Long = System.currentTimeMillis()
    var content: String = ""
    var sections: Map<String, String?> = emptyMap()
    var toolsAdded: List<ToolDeclaration> = emptyList()
    var toolsRemoved: List<String> = emptyList()
    var contentBlocks: List<TextContent>? = null

    constructor()
    constructor(content: String) { this.content = content }
    constructor(content: List<TextContent>) {
        val blocks = ArrayList<TextContent>()
        val text = ArrayList<String>()
        for (block in content) {
            blocks.add(BeanCopier().textContent(block))
            text.add(block.text)
        }
        this.contentBlocks = blocks
        this.content = text.joinToString("\n")
    }

    override fun snapshot(): AgentMessage = BeanCopier().message(this)
}
