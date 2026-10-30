package dev.agent.core.runtime

import dev.agent.core.bean.AgentToolCallOutcome
import dev.agent.core.bean.ToolCall
import dev.agent.core.tool.AgentTool
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 单个工具的准备状态，最终回调门跨线程共享，其余字段只由控制线程提交。 */
internal class ToolExecution {
    var call: ToolCall = ToolCall()
    var tool: AgentTool? = null
    var args: JsonElement = JsonObject(emptyMap())
    var outcome: AgentToolCallOutcome? = null
    val terminal = AtomicBoolean(false)
}
