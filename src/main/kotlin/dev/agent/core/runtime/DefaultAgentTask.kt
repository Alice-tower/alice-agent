package dev.agent.core.runtime

import dev.agent.core.api.AgentTask
import dev.agent.core.bean.TaskStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** 终态在最后事件前确定；await 在结束回调返回后完成。 */
internal class DefaultAgentTask(
    private val id: String,
    private val source: CancellationSource,
    private val cancelAction: Runnable
) : AgentTask {
    private val status = AtomicReference(TaskStatus.RUNNING)
    private val completed = CountDownLatch(1)
    private val cancellationRequested = AtomicBoolean(false)
    private val terminalLock = Any()
    override fun getId(): String = id
    override fun getStatus(): TaskStatus = status.get()
    override fun isDone(): Boolean = completed.count == 0L
    override fun await(timeout: Long, unit: TimeUnit): Boolean = completed.await(timeout, unit)
    override fun cancel() {
        synchronized(terminalLock) {
            if (status.get() != TaskStatus.RUNNING || !cancellationRequested.compareAndSet(false, true)) return
        }
        source.cancel()
        cancelAction.run()
    }
    /** 与取消请求共用一次性终态门，清理资源时不持该锁。 */
    fun settle(value: TaskStatus): TaskStatus? = synchronized(terminalLock) {
        if (status.get() != TaskStatus.RUNNING) return@synchronized null
        val selected = if (cancellationRequested.get()) TaskStatus.CANCELLED else value
        status.set(selected)
        selected
    }
    fun complete() { completed.countDown() }
}
