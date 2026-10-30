package dev.agent.core.tool

import dev.agent.core.api.Cancellable
import dev.agent.core.bean.ToolContext
import dev.agent.core.bean.ToolDefinition
import kotlinx.serialization.json.JsonElement

/** 具名业务能力。执行和进度回调发生在工作线程，core 会串行提交事件。 */
interface AgentTool {
    fun getDefinition(): ToolDefinition
    fun prepareArguments(arguments: JsonElement): JsonElement
    fun execute(context: ToolContext, callback: ToolCallback): Cancellable
}
