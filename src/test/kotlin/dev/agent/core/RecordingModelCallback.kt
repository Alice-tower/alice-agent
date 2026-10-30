package dev.agent.core

import dev.agent.core.bean.AssistantMessageEvent
import dev.agent.core.model.ModelCallback
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

open class RecordingModelCallback : ModelCallback {
    val events = CopyOnWriteArrayList<AssistantMessageEvent>()
    val completed = AtomicInteger()
    private val finished = CountDownLatch(1)
    @Volatile var failure: Throwable? = null

    override fun onEvent(event: AssistantMessageEvent) { events.add(event) }
    override fun onComplete() {
        completed.incrementAndGet()
        finished.countDown()
    }
    override fun onFailure(error: Throwable) {
        failure = error
        finished.countDown()
    }
    fun await() {
        assertTrue(finished.await(5, TimeUnit.SECONDS), "模型结束回调超时")
    }
}
