package dev.agent.core

import dev.agent.core.api.AgentFactory
import dev.agent.core.api.AgentTask
import dev.agent.core.api.Cancellable
import dev.agent.core.api.NoOpCancellable
import dev.agent.core.bean.AgentEvent
import dev.agent.core.bean.AgentEventType
import dev.agent.core.bean.AgentRequest
import dev.agent.core.bean.AssistantMessageEvent
import dev.agent.core.bean.ModelRequest
import dev.agent.core.bean.TaskStatus
import dev.agent.core.bean.ToolContext
import dev.agent.core.model.ModelCallback
import dev.agent.core.model.ModelClient
import dev.agent.core.tool.AbstractAgentTool
import dev.agent.core.tool.ToolCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 以明确的同步屏障覆盖取消登记、单工作线程和关闭后的回调竞争。 */
class RuntimeRaceTest : CoreTestSupport() {
    @Test fun oneWorkerCompletesSynchronousModelToolsAndHooks() {
        val workers = Executors.newSingleThreadExecutor()
        val model = ScriptedModelClient(calls("lookup"), reply("finished"))
        val configuration = config(model)
        configuration.workerExecutor = workers
        configuration.tools = listOf(textTool("lookup"))
        val agent = AgentFactory().create(configuration)
        try {
            val callback = run(agent)
            assertNull(callback.failure)
            assertFalse(callback.cancelled)
            assertEquals(2, model.count.get())
            assertEquals(listOf("user", "assistant", "toolResult", "assistant"),
                agent.getState().messages.filter { it.role != "system" }.map { it.role })
        } finally {
            agent.close()
            workers.shutdownNow()
        }
    }

    @Test fun cancellationClosesModelHandleReturnedAfterTaskEnds() {
        val entered = CountDownLatch(1)
        val returnHandle = CountDownLatch(1)
        val cancelledHandle = CountDownLatch(1)
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                entered.countDown()
                awaitReturnGate(returnHandle)
                return object : Cancellable {
                    override fun cancel() { cancelledHandle.countDown() }
                }
            }
        }
        val agent = create(model)
        try {
            val callback = RecordingAgentCallback()
            val task = agent.run(AgentRequest("hello"), callback)
            waitFor(entered)
            task.cancel()
            callback.await()
            assertTrue(callback.cancelled)
            assertEquals(1L, cancelledHandle.count)
            returnHandle.countDown()
            waitFor(cancelledHandle)
            assertEquals(1, callback.terminalCount.get())
        } finally {
            returnHandle.countDown()
            agent.close()
        }
    }

    @Test fun cancellationClosesToolHandleReturnedAfterTaskEnds() {
        val entered = CountDownLatch(1)
        val returnHandle = CountDownLatch(1)
        val cancelledHandle = CountDownLatch(1)
        val tool = object : AbstractAgentTool(definition("lookup")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                entered.countDown()
                awaitReturnGate(returnHandle)
                return object : Cancellable {
                    override fun cancel() { cancelledHandle.countDown() }
                }
            }
        }
        val configuration = config(ScriptedModelClient(calls("lookup")))
        configuration.tools = listOf(tool)
        val agent = AgentFactory().create(configuration)
        try {
            val callback = RecordingAgentCallback()
            val task = agent.run(AgentRequest("hello"), callback)
            waitFor(entered)
            task.cancel()
            callback.await()
            assertTrue(callback.cancelled)
            assertEquals(1L, cancelledHandle.count)
            returnHandle.countDown()
            waitFor(cancelledHandle)
            assertEquals(1, callback.terminalCount.get())
        } finally {
            returnHandle.countDown()
            agent.close()
        }
    }

    @Test fun oldModelCallbacksAreIgnoredAfterIdleAgentIsClosed() {
        val receiver = AtomicReference<ModelCallback>()
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                receiver.set(callback)
                val done = AssistantMessageEvent()
                done.type = "done"
                done.message = reply("completed")
                callback.onEvent(done)
                callback.onComplete()
                return NoOpCancellable()
            }
        }
        val agent = create(model)
        try {
            val callback = RecordingAgentCallback()
            val task = agent.run(AgentRequest("hello"), callback)
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            agent.close()
            val count = agent.getState().messages.size
            val late = AssistantMessageEvent()
            late.type = "done"
            late.message = reply("late")
            receiver.get().onEvent(late)
            receiver.get().onComplete()
            receiver.get().onFailure(IllegalStateException("late failure"))
            assertEquals(count, agent.getState().messages.size)
            assertEquals(1, callback.terminalCount.get())
        } finally {
            agent.close()
        }
    }

    @Test fun cancellationInTurnEndWinsBeforeAgentEnd() {
        val taskReference = AtomicReference<AgentTask>()
        val taskPublished = CountDownLatch(1)
        val cancelledOnce = AtomicBoolean()
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.TURN_END && cancelledOnce.compareAndSet(false, true)) {
                    waitFor(taskPublished)
                    taskReference.get().cancel()
                }
            }
        }
        val agent = create(ScriptedModelClient(reply()))
        try {
            val task = agent.run(AgentRequest("hello"), callback)
            taskReference.set(task)
            taskPublished.countDown()
            callback.await()
            assertTrue(callback.cancelled)
            assertEquals(TaskStatus.CANCELLED, task.getStatus())
            assertEquals(1, callback.terminalCount.get())
            assertEquals(1, callback.events.count { it.type == AgentEventType.AGENT_END })
            assertEquals(callback.events.count { it.type == AgentEventType.TURN_START },
                callback.events.count { it.type == AgentEventType.TURN_END })
        } finally {
            taskPublished.countDown()
            agent.close()
        }
    }

    private fun awaitReturnGate(gate: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "等待返回资源句柄超时" }
            try {
                check(gate.await(remaining, TimeUnit.NANOSECONDS)) { "等待返回资源句柄超时" }
                return
            } catch (interrupted: InterruptedException) {
                // 模拟忽略线程中断但稍后仍返回可取消资源的第三方适配器。
            }
        }
    }
}
