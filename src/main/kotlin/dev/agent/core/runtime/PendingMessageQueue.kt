package dev.agent.core.runtime

import dev.agent.core.bean.*
import java.util.ArrayDeque

/** 队列只在锁内维护自有引用；自定义消息的 snapshot 始终在锁外执行。 */
internal class PendingMessageQueue(@Volatile var mode: QueueMode) {
    private val messages = ArrayDeque<AgentMessage>()
    private val copier = BeanCopier()
    private var generation = 0L

    /** 参数已经由调用方复制，队列取得该副本的所有权。 */
    @Synchronized fun enqueueOwned(message: AgentMessage) { messages.addLast(message) }
    @Synchronized fun hasItems(): Boolean = messages.isNotEmpty()

    fun peek(): List<AgentMessage> {
        val selected: List<AgentMessage>
        synchronized(this) {
            selected = if (mode == QueueMode.ALL) messages.toList() else listOfNotNull(messages.peekFirst())
        }
        return copier.messages(selected)
    }

    /** 暂时取走输入；写入历史前失败时由 reservation 退回。 */
    @Synchronized fun reserve(): PendingInputBatch? {
        val count = if (mode == QueueMode.ALL) messages.size else if (messages.isEmpty()) 0 else 1
        if (count == 0) return null
        val selected = ArrayList<AgentMessage>()
        repeat(count) { selected.add(messages.removeFirst()) }
        return PendingInputBatch(this, generation, selected)
    }

    @Synchronized fun restore(expectedGeneration: Long, values: List<AgentMessage>) {
        if (generation != expectedGeneration) return
        for (index in values.indices.reversed()) messages.addFirst(values[index])
    }

    /** 显式清空也使旧的预留失效，避免取消时重新出现已清理的输入。 */
    @Synchronized fun clear() { generation++; messages.clear() }
}
