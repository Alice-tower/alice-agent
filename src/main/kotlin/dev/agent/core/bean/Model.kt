package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject

/** 模型身份及能力描述；core 仅传递字段，不执行模型发现。 */
class Model {
    var id: String = "unknown"
    var provider: String = "unknown"
    var api: String = "unknown"
    var name: String = "unknown"
    var baseUrl: String = ""
    var reasoning: Boolean = false
    var input: List<String> = emptyList()
    var cost: JsonObject = JsonObject(emptyMap())
    var contextWindow: Int = 0
    var maxTokens: Int = 0
    var extra: JsonObject = JsonObject(emptyMap())
}
