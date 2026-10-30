package dev.agent.core.proxy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** 按接收顺序保存数组元素。 */
internal class PartialJsonArrayNode : PartialJsonNode {
    val items: MutableList<PartialJsonNode> = ArrayList()

    override fun snapshot(): JsonElement {
        val values = ArrayList<JsonElement>()
        for (item in items) {
            values.add(item.snapshot())
        }
        return JsonArray(values)
    }
}
