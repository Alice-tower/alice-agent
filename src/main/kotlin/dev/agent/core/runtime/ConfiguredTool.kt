package dev.agent.core.runtime

import dev.agent.core.api.Cancellable
import dev.agent.core.bean.*
import dev.agent.core.tool.AgentTool
import dev.agent.core.tool.ToolCallback
import kotlinx.serialization.json.JsonElement

/** 固定本次配置中的工具元数据，执行逻辑仍由宿主提供。 */
internal class ConfiguredTool(private val delegate: AgentTool) : AgentTool {
    private val definition = BeanCopier().toolDefinition(delegate.getDefinition())
    override fun getDefinition(): ToolDefinition = BeanCopier().toolDefinition(definition)
    override fun prepareArguments(arguments: JsonElement): JsonElement = delegate.prepareArguments(arguments)
    override fun execute(context: ToolContext, callback: ToolCallback): Cancellable = delegate.execute(context, callback)
}
