package dev.agent.core

import dev.agent.core.api.Agent
import dev.agent.core.api.AgentFactory
import dev.agent.core.api.AgentTask
import dev.agent.core.api.Cancellable
import dev.agent.core.api.NoOpCancellable
import dev.agent.core.bean.*
import dev.agent.core.protocol.ProtocolCodec
import dev.agent.core.tool.AbstractAgentTool
import dev.agent.core.tool.ToolCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 部分工具结果一旦提交历史，也必须包含在当前任务的结束事件中。 */
class PartialToolHistoryTest : CoreTestSupport() {
    @Test fun cancellingWhileSecondSequentialToolRunsRetainsCommittedFirstResult() {
        val entered = CountDownLatch(1)
        val waitingTool = object : AbstractAgentTool(definition("second")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                entered.countDown()
                return NoOpCancellable()
            }
        }
        val configuration = config(ScriptedModelClient(calls("first", "second")))
        configuration.toolExecution = ToolExecutionMode.SEQUENTIAL
        configuration.tools = listOf(textTool("first"), waitingTool)
        val agent = AgentFactory().create(configuration)
        val initialCount = agent.getState().messages.size
        val callback = RecordingAgentCallback()
        try {
            val task = agent.run(AgentRequest("hello"), callback)
            waitFor(entered)
            task.cancel()
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertTrue(callback.cancelled)
            assertCommittedHistory(agent, initialCount, callback)
        } finally { agent.close() }
    }

    @Test fun cancellingFromParallelToolMessageEndRetainsOnlyCommittedResults() {
        val taskReference = AtomicReference<AgentTask>()
        val published = CountDownLatch(1)
        val cancelOnce = AtomicBoolean()
        val configuration = config(ScriptedModelClient(calls("first", "second")))
        configuration.tools = listOf(textTool("first"), textTool("second"))
        val agent = AgentFactory().create(configuration)
        val initialCount = agent.getState().messages.size
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.MESSAGE_END && event.message is ToolResultMessage && cancelOnce.compareAndSet(false, true)) {
                    waitFor(published)
                    taskReference.get().cancel()
                }
            }
        }
        try {
            val task = agent.run(AgentRequest("hello"), callback)
            taskReference.set(task)
            published.countDown()
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertTrue(callback.cancelled)
            assertCommittedHistory(agent, initialCount, callback)
        } finally { published.countDown(); agent.close() }
    }

    @Test fun laterSchedulingFailureRetainsCommittedToolResultInFailureEvents() {
        val worker = Executors.newSingleThreadExecutor()
        val configuration = config(ScriptedModelClient(calls("first", "second")))
        configuration.workerExecutor = worker
        configuration.toolExecution = ToolExecutionMode.SEQUENTIAL
        configuration.tools = listOf(textTool("first"), textTool("second"))
        val agent = AgentFactory().create(configuration)
        val initialCount = agent.getState().messages.size
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.MESSAGE_END && event.message is ToolResultMessage) worker.shutdown()
            }
        }
        try {
            val task = agent.run(AgentRequest("hello"), callback)
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertNotNull(callback.failure)
            assertEquals(TaskStatus.FAILED, task.getStatus())
            assertCommittedHistory(agent, initialCount, callback)
        } finally { agent.close(); worker.shutdownNow() }
    }

    private fun assertCommittedHistory(agent: Agent, initialCount: Int, callback: RecordingAgentCallback) {
        val codec = ProtocolCodec()
        val committed = agent.getState().messages.drop(initialCount)
        val end = callback.events.single { it.type == AgentEventType.AGENT_END }
        assertEquals(committed.map { codec.message(it) }, end.messages.map { codec.message(it) })
        val toolMessages = committed.filterIsInstance<ToolResultMessage>()
        assertEquals(listOf("first"), toolMessages.map { it.toolName })
        val lastTurn = callback.events.last { it.type == AgentEventType.TURN_END }
        assertEquals(toolMessages.map { codec.message(it) }, lastTurn.toolResults.map { codec.message(it) })
        assertEquals(1, callback.terminalCount.get())
    }
}
