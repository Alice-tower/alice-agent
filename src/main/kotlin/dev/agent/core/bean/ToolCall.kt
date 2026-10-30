package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 模型请求的工具调用，最终参数经验证后才可执行。 */
class ToolCall : ContentBlock {
    var id: String = ""
    var name: String = ""
    var arguments: JsonElement = JsonObject(emptyMap())
    var thoughtSignature: String? = null
    var argumentsError: String? = null
    var extra: JsonObject = JsonObject(emptyMap())

    constructor()
    constructor(id: String, name: String) { this.id = id; this.name = name }
    constructor(id: String, name: String, arguments: JsonElement) {
        this.id = id
        this.name = name
        this.arguments = BeanCopier().json(arguments)
    }

    override fun snapshot(): ContentBlock = BeanCopier().content(this)
}
