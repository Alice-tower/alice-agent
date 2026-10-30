package dev.agent.core.bean

/** 普通工具结果构造器，便于 Kotlin 和 Java 宿主调用。 */
class ToolResults {
    fun text(text: String): AgentToolResult {
        val result = AgentToolResult()
        result.content = listOf(TextContent(text))
        return result
    }

    fun error(message: String): AgentToolResult {
        val result = text(message)
        result.isError = true
        return result
    }
}
