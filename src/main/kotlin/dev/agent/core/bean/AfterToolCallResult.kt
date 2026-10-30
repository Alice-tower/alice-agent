package dev.agent.core.bean

import kotlinx.serialization.json.JsonElement

/** 执行后结果字段替换；null 保留原值。替换 content 时旧结构化内容应清除。 */
class AfterToolCallResult {
    var content: List<ContentBlock>? = null
    var details: JsonElement? = null
    var structuredContent: JsonElement? = null
    var isError: Boolean? = null
    var usage: Usage? = null
    var terminate: Boolean? = null
}
