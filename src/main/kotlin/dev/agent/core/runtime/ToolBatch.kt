package dev.agent.core.runtime

import dev.agent.core.bean.ToolResultMessage

/** 一批工具的最终消息，始终按照模型原调用顺序排列。 */
internal class ToolBatch {
    var messages: List<ToolResultMessage> = emptyList()
    var terminate: Boolean = false
}
