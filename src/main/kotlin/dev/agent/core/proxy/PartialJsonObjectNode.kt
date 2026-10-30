package dev.agent.core.proxy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 保存对象字段顺序和正在读取的字段名。 */
internal class PartialJsonObjectNode : PartialJsonNode {
    val fields: MutableMap<String, PartialJsonNode> = LinkedHashMap()
    var key: String? = null
    var expectingKey: Boolean = true

    override fun snapshot(): JsonElement {
        val values = LinkedHashMap<String, JsonElement>()
        for (entry in fields.entries) {
            values[entry.key] = entry.value.snapshot()
        }
        return JsonObject(values)
    }
}
