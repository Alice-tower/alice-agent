package dev.agent.core

import dev.agent.core.api.Agent
import dev.agent.core.api.AgentFactory
import dev.agent.core.api.AgentTask
import dev.agent.core.api.CancellationToken
import dev.agent.core.bean.*
import dev.agent.core.runtime.AgentHooks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 下一轮准备完成前取得的输入，在取消或准备失败时仍应保留原先顺序。 */
class QueuedInputRecoveryTest : CoreTestSupport() {
    @Test fun cancelledPreparationReturnsSteeringInputsBeforeNewArrivals() {
        exerciseRecovery(true, true)
    }

    @Test fun cancelledPreparationReturnsFollowUpsBeforeNewArrivals() {
        exerciseRecovery(false, true)
    }

    @Test fun failedPreparationReturnsSteeringInputsBeforeNewArrivals() {
        exerciseRecovery(true, false)
    }

    @Test fun failedPreparationReturnsFollowUpsBeforeNewArrivals() {
        exerciseRecovery(false, false)
    }

    @Test fun snapshotFailureReturnsOnlyTheUncommittedQueueSuffix() {
        val fail = AtomicBoolean()
        val configuration = config(ScriptedModelClient(reply()))
        configuration.steeringMode = QueueMode.ALL
        val agent = AgentFactory().create(configuration)
        val queued = AtomicBoolean()
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type != AgentEventType.MESSAGE_END) return
                if (event.message is AssistantMessage && queued.compareAndSet(false, true)) {
                    agent.steer(UserMessage("committed-prefix"))
                    agent.steer(ThrowingSnapshotMessage(fail))
                }
                val message = event.message
                if (message is UserMessage && text(message) == "committed-prefix") {
                    fail.set(true)
                    agent.steer(UserMessage("arrived-after-prefix"))
                }
            }
        }
        try {
            val task = agent.run(AgentRequest("initial"), callback)
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            fail.set(false)
            assertNotNull(callback.failure)
            assertEquals(TaskStatus.FAILED, task.getStatus())
            val history = agent.getState().messages.filterIsInstance<UserMessage>()
            assertEquals(listOf("initial", "committed-prefix"), history.map { text(it) })
            val pending = agent.peekQueuedMessages()
            assertEquals(2, pending.size)
            assertTrue(pending.first() is ThrowingSnapshotMessage)
            assertEquals("arrived-after-prefix", text(pending.last()))
            assertEquals(1, callback.terminalCount.get())
            val end = callback.events.single { it.type == AgentEventType.AGENT_END }
            assertEquals(listOf("initial", "committed-prefix"),
                end.messages.filterIsInstance<UserMessage>().map { text(it) })
        } finally {
            fail.set(false)
            agent.close()
        }
    }

    @Test fun clearingSelectedInputsBeforeCancellationDoesNotReviveThem() {
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val configuration = config(ScriptedModelClient(reply()))
        configuration.steeringMode = QueueMode.ALL
        configuration.hooks = object : AgentHooks() {
            override fun prepareNextTurn(context: AgentTurnContext, signal: CancellationToken): AgentLoopTurnUpdate? {
                preparing.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "等待准备阶段释放超时" }
                return null
            }
        }
        val agent = AgentFactory().create(configuration)
        val queued = AtomicBoolean()
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.MESSAGE_END && event.message is AssistantMessage && queued.compareAndSet(false, true)) {
                    agent.steer(UserMessage("selected-before-clear"))
                }
            }
        }
        try {
            val task = agent.run(AgentRequest("initial"), callback)
            waitFor(preparing)
            agent.clearSteeringQueue()
            agent.steer(UserMessage("arrived-after-clear"))
            task.cancel()
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertTrue(callback.cancelled)
            assertEquals(listOf("arrived-after-clear"), agent.peekQueuedMessages().map { text(it) })
            assertEquals(listOf("initial"),
                agent.getState().messages.filterIsInstance<UserMessage>().map { text(it) })
            assertEquals(1, callback.terminalCount.get())
        } finally {
            release.countDown()
            agent.close()
        }
    }

    @Test fun cancellationFromTurnEndLeavesNewInputQueued() {
        val taskReference = AtomicReference<AgentTask>()
        val taskPublished = CountDownLatch(1)
        val cancelOnce = AtomicBoolean()
        val agent = create(ScriptedModelClient(reply()))
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.TURN_END && cancelOnce.compareAndSet(false, true)) {
                    agent.steer(UserMessage("queued-at-turn-end"))
                    waitFor(taskPublished)
                    taskReference.get().cancel()
                }
            }
        }
        try {
            val task = agent.run(AgentRequest("initial"), callback)
            taskReference.set(task)
            taskPublished.countDown()
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertTrue(callback.cancelled)
            assertEquals(listOf("queued-at-turn-end"), agent.peekQueuedMessages().map { text(it) })
            assertEquals(listOf("initial"),
                agent.getState().messages.filterIsInstance<UserMessage>().map { text(it) })
            assertEquals(1, callback.terminalCount.get())
        } finally {
            taskPublished.countDown()
            agent.close()
        }
    }

    private fun exerciseRecovery(steering: Boolean, cancel: Boolean) {
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val configuration = config(ScriptedModelClient(reply()))
        configuration.steeringMode = QueueMode.ALL
        configuration.followUpMode = QueueMode.ALL
        configuration.hooks = object : AgentHooks() {
            override fun prepareNextTurn(context: AgentTurnContext, signal: CancellationToken): AgentLoopTurnUpdate? {
                preparing.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "等待准备阶段释放超时" }
                if (!cancel) throw IllegalStateException("下一轮准备失败")
                return null
            }
        }
        val agent = AgentFactory().create(configuration)
        val queued = AtomicBoolean()
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.MESSAGE_END && event.message is AssistantMessage && queued.compareAndSet(false, true)) {
                    enqueue(agent, steering, "reserved-first")
                    enqueue(agent, steering, "reserved-second")
                }
            }
        }
        try {
            val task = agent.run(AgentRequest("initial"), callback)
            waitFor(preparing)
            enqueue(agent, steering, "arrived-during-preparation")
            if (cancel) task.cancel() else release.countDown()
            callback.await()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            if (cancel) assertTrue(callback.cancelled) else assertNotNull(callback.failure)
            assertEquals(1, callback.terminalCount.get())
            assertFalse(agent.getState().isStreaming)
            val pending = agent.peekQueuedMessages().map { text(it) }
            assertEquals(listOf("reserved-first", "reserved-second", "arrived-during-preparation"), pending)
            assertEquals(listOf("initial"), agent.getState().messages.filterIsInstance<UserMessage>().map { text(it) })
        } finally {
            release.countDown()
            agent.close()
        }
    }

    private fun enqueue(agent: Agent, steering: Boolean, value: String) {
        if (steering) agent.steer(UserMessage(value)) else agent.followUp(UserMessage(value))
    }
}
