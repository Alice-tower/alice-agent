package dev.agent.core.bean

import dev.agent.core.api.CancellationToken
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 工具调用 ID、验证后的参数和取消令牌。 */
class ToolContext {
    var toolCallId: String = ""
    var arguments: JsonElement = JsonObject(emptyMap())
    var signal: CancellationToken? = null
}
