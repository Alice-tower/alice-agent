package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** 工具结果或进度快照；只有整批 terminate 均为 true 才停止续跑。 */
class AgentToolResult {
    var content: List<ContentBlock> = emptyList()
    var details: JsonElement = JsonObject(emptyMap())
    var structuredContent: JsonElement? = null
    var usage: Usage? = null
    var isError: Boolean = false
    var terminate: Boolean = false
}
