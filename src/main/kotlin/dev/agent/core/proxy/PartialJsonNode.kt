package dev.agent.core.proxy

import kotlinx.serialization.json.JsonElement

/** 增量参数解析树节点；每次预览必须生成与内部可变树隔离的 JSON。 */
internal interface PartialJsonNode {
    fun snapshot(): JsonElement
}
