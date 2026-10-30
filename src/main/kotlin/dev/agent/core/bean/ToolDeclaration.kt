package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 模型可见的工具声明，不包含宿主执行函数。 */
class ToolDeclaration {
    var name: String = ""
    var description: String = ""
    var parameters: JsonObject = JsonObject(emptyMap())
    var constrainedSampling: JsonElement? = null
}
