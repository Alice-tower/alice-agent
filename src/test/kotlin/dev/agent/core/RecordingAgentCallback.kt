package dev.agent.core

import dev.agent.core.api.AgentCallbackAdapter
import dev.agent.core.bean.AgentEvent
import dev.agent.core.bean.AgentResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/** 测试用的普通回调，所有异步等待均有明确超时。 */
open class RecordingAgentCallback : AgentCallbackAdapter() {
    val events = CopyOnWriteArrayList<AgentEvent>()
    val terminalCount = AtomicInteger()
    private val finished = CountDownLatch(1)
    @Volatile var result: AgentResult? = null
    @Volatile var failure: Throwable? = null
    @Volatile var cancelled: Boolean = false

    override fun onEvent(event: AgentEvent) {
        events.add(event)
    }

    override fun onSuccess(result: AgentResult) {
        this.result = result
        terminalCount.incrementAndGet()
        finished.countDown()
    }

    override fun onFailure(error: Throwable) {
        failure = error
        terminalCount.incrementAndGet()
        finished.countDown()
    }

    override fun onCancelled() {
        cancelled = true
        terminalCount.incrementAndGet()
        finished.countDown()
    }

    fun await() {
        assertTrue(finished.await(5, TimeUnit.SECONDS), "任务结束回调超时")
    }
}
