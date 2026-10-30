package dev.agent.core

import dev.agent.core.api.Agent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 快照复制期间从其他线程读取状态与输入队列，检查是否持有业务可见的内部锁。 */
class SnapshotLockProbe : AutoCloseable {
    val agent = AtomicReference<Agent>()
    val attempts = AtomicInteger()
    val blocked = AtomicInteger()
    @Volatile var enabled = false
    private val checking = AtomicBoolean()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun checkAccess() {
        if (!enabled || !checking.compareAndSet(false, true)) return
        attempts.incrementAndGet()
        try {
            val action = executor.submit(Runnable {
                agent.get().getState()
                agent.get().peekQueuedMessages()
            })
            try {
                action.get(2, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                blocked.incrementAndGet()
            }
        } finally {
            checking.set(false)
        }
    }

    override fun close() {
        enabled = false
        executor.shutdownNow()
    }
}
