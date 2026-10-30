package dev.agent.core.proxy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** 跨增量片段保留字符串和转义字符。 */
internal class PartialJsonStringNode : PartialJsonNode {
    val text = StringBuilder()

    override fun snapshot(): JsonElement {
        return JsonPrimitive(text.toString())
    }
}
