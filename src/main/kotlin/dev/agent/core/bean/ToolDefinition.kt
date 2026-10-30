package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 工具声明及宿主执行策略；输出 Schema 仅作为元数据保留。 */
class ToolDefinition {
    var name: String = ""
    var description: String = ""
    var parameters: JsonObject = JsonObject(emptyMap())
    var label: String = ""
    var outputSchema: JsonObject? = null
    var constrainedSampling: JsonElement? = null
    var executionMode: ToolExecutionMode? = null
    var replay: String? = null

    fun declaration(): ToolDeclaration {
        val result = ToolDeclaration()
        result.name = name
        result.description = description
        result.parameters = BeanCopier().json(parameters) as JsonObject
        result.constrainedSampling = constrainedSampling?.let { BeanCopier().json(it) }
        return result
    }
}
