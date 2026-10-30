package dev.agent.core.runtime

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.ArrayDeque

/** 有界控制队列；生产者等待消费，禁止无界堆积。关闭后排空已接受任务。 */
internal class SerialDispatcher(name: String) : AutoCloseable {
    private val queue = ArrayBlockingQueue<Runnable>(128)
    private val intake = Object()
    private val local = ArrayDeque<Runnable>()
    @Volatile private var closed = false
    private val thread = Thread(Runnable { drain() }, name)

    init { thread.isDaemon = true; thread.start() }

    fun post(action: Runnable): Boolean {
        if (Thread.currentThread() === thread) {
            if (closed) return false
            local.addLast(action)
            return true
        }
        synchronized(intake) {
            while (!closed) {
                if (queue.offer(action)) return true
                intake.wait()
            }
        }
        return false
    }

    fun invoke(action: Runnable) {
        if (Thread.currentThread() === thread) { action.run(); return }
        val task = FutureTask<Unit>(action, Unit)
        if (!post(task)) throw RejectedExecutionException("Agent dispatcher is closed")
        try { task.get() } catch (error: ExecutionException) { throw error.cause ?: error }
    }

    private fun drain() {
        while (!closed || queue.isNotEmpty() || local.isNotEmpty()) {
            try {
                val action = if (local.isEmpty()) queue.poll(100, TimeUnit.MILLISECONDS) else local.removeFirst()
                if (action == null) continue
                synchronized(intake) { intake.notifyAll() }
                try { action.run() } catch (error: Throwable) { RuntimeLog().callbackFailure(error) }
            } catch (interrupted: InterruptedException) {
                // close 唤醒 poll；继续排空已经接受的控制任务。
            }
        }
    }

    override fun close() {
        synchronized(intake) { closed = true; intake.notifyAll() }
        thread.interrupt()
    }
}
