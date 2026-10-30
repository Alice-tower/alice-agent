package dev.agent.core.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** 数字、布尔值或 null 的增量文本；不完整时预览为空值。 */
internal class PartialJsonAtomNode : PartialJsonNode {
    val text = StringBuilder()

    override fun snapshot(): JsonElement {
        try {
            return Json.parseToJsonElement(text.toString())
        } catch (failure: IllegalArgumentException) {
            return JsonNull
        }
    }
}
