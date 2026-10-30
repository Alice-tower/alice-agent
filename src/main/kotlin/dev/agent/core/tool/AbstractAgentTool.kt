package dev.agent.core.tool

import dev.agent.core.bean.BeanCopier
import dev.agent.core.bean.ToolDefinition
import kotlinx.serialization.json.JsonElement

/** 保存工具定义，默认不转换参数；业务只需实现 execute。 */
abstract class AbstractAgentTool(definition: ToolDefinition) : AgentTool {
    private val definition = BeanCopier().toolDefinition(definition)
    override fun getDefinition(): ToolDefinition = BeanCopier().toolDefinition(definition)
    override fun prepareArguments(arguments: JsonElement): JsonElement = arguments
}
