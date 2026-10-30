package dev.agent.core.runtime

import dev.agent.core.bean.AgentMessage

/** 单次控制线程拥有的输入预留，逐条确认已经提交的消息。 */
internal class PendingInputBatch(
    private val queue: PendingMessageQueue,
    private val generation: Long,
    val messages: List<AgentMessage>
) {
    private var committed = 0

    fun acknowledge() { committed++ }

    fun rollback() {
        if (committed == messages.size) return
        queue.restore(generation, messages.drop(committed))
        committed = messages.size
    }
}
