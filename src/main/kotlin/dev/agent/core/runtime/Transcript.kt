package dev.agent.core.runtime

import dev.agent.core.bean.*
import dev.agent.core.protocol.ProtocolCodec
import dev.agent.core.tool.AgentTool

/** 系统指令、具名 section 及工具声明增量的历史回放服务。 */
class Transcript {
    private val copier = BeanCopier()
    private val codec = ProtocolCodec()

    fun initialSystemMessage(systemPrompt: String?, tools: List<AgentTool>): SystemMessage? {
        if (systemPrompt.isNullOrEmpty() && tools.isEmpty()) return null
        val message = SystemMessage()
        message.content = systemPrompt.orEmpty()
        message.timestamp = 0
        message.toolsAdded = tools.map { it.getDefinition().declaration() }
        return message
    }

    fun currentTools(messages: List<AgentMessage>): List<ToolDeclaration> {
        val tools = LinkedHashMap<String, ToolDeclaration>()
        for (message in messages) {
            if (message !is SystemMessage) continue
            for (name in message.toolsRemoved) tools.remove(name)
            for (tool in message.toolsAdded) tools[tool.name] = copier.toolDeclaration(tool)
        }
        return tools.values.toList()
    }

    /**
     * 回放系统基线。正文块与协议采用同样的优先级，避免 Bean setter 只设置正文块时丢失指令。
     * 合并时保留非空正文的签名块，并用独立的无签名空块表达系统消息之间的双换行。
     */
    fun currentSystemMessage(messages: List<AgentMessage>): SystemMessage? {
        val systems = messages.filterIsInstance<SystemMessage>()
        if (systems.isEmpty()) return null
        val result = SystemMessage()
        val sections = LinkedHashMap<String, String?>()
        val content = ArrayList<String>()
        val contentBlocks = ArrayList<TextContent>()
        var hasContentBlocks = false
        var emptyBlocks: List<TextContent>? = null
        for (message in systems) {
            val sourceBlocks = message.contentBlocks
            val text: String
            if (sourceBlocks == null) {
                text = message.content
            } else {
                hasContentBlocks = true
                val parts = ArrayList<String>()
                for (block in sourceBlocks) parts.add(block.text)
                text = parts.joinToString("\n")
                if (text.isEmpty() && emptyBlocks == null) emptyBlocks = sourceBlocks
            }
            if (text.isNotEmpty()) {
                if (content.isNotEmpty()) contentBlocks.add(TextContent(""))
                content.add(text)
                if (sourceBlocks == null) {
                    contentBlocks.add(TextContent(text))
                } else {
                    for (block in sourceBlocks) contentBlocks.add(copier.textContent(block))
                }
            }
            for ((name, value) in message.sections) {
                if (value == null) sections.remove(name) else sections[name] = value
            }
        }
        result.content = content.joinToString("\n\n")
        if (hasContentBlocks) {
            val blocks = emptyBlocks
            if (contentBlocks.isEmpty() && blocks != null) {
                for (block in blocks) contentBlocks.add(copier.textContent(block))
            }
            result.contentBlocks = contentBlocks
        }
        result.sections = sections
        result.toolsAdded = currentTools(messages)
        result.timestamp = systems.first().timestamp
        return result
    }

    fun currentSystemPrompt(messages: List<AgentMessage>): String {
        val system = currentSystemMessage(messages) ?: return ""
        val parts = ArrayList<String>()
        if (system.content.isNotEmpty()) parts.add(system.content)
        for (section in system.sections.values) {
            if (!section.isNullOrEmpty()) parts.add(section)
        }
        return parts.joinToString("\n\n")
    }

    fun declareToolChanges(context: AgentContext, pending: List<AgentMessage>): List<AgentMessage> {
        val result = copier.messages(pending).toMutableList()
        val systemIndex = result.indexOfLast { it is SystemMessage }
        val system = result.getOrNull(systemIndex) as? SystemMessage
        val hadDeclarations = system != null && (system.toolsAdded.isNotEmpty() || system.toolsRemoved.isNotEmpty())
        if (system != null) {
            system.toolsAdded = emptyList()
            system.toolsRemoved = emptyList()
        }
        val old = currentTools(context.messages + result).associateBy { it.name }
        val desired = context.tools.map { it.getDefinition().declaration() }
        val desiredMap = desired.associateBy { it.name }
        val added = ArrayList<ToolDeclaration>()
        val removed = ArrayList<String>()
        for (tool in desired) {
            if (old[tool.name] == null || codec.tool(old.getValue(tool.name)) != codec.tool(tool)) added.add(tool)
        }
        for (tool in old.values) {
            if (desiredMap[tool.name] == null || codec.tool(desiredMap.getValue(tool.name)) != codec.tool(tool)) removed.add(tool.name)
        }
        if (system != null) {
            if (added.isNotEmpty() || removed.isNotEmpty() || hadDeclarations) {
                system.toolsAdded = added
                system.toolsRemoved = removed
            }
        } else if (added.isNotEmpty() || removed.isNotEmpty()) {
            val update = SystemMessage()
            update.toolsAdded = added
            update.toolsRemoved = removed
            val index = result.indexOfFirst { it !is SystemMessage }
            result.add(if (index < 0) result.size else index, update)
        }
        return result
    }
}
