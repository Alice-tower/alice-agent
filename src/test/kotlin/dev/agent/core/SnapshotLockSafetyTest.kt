package dev.agent.core

import dev.agent.core.api.AgentFactory
import dev.agent.core.bean.AgentRequest
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 自定义消息快照不能在 Agent 状态锁或输入队列锁内执行。 */
class SnapshotLockSafetyTest : CoreTestSupport() {
    @Test fun stateSnapshotExecutesCustomCopyOutsideStateLock() {
        val probe = SnapshotLockProbe()
        val configuration = config(ScriptedModelClient(reply()))
        configuration.messages = listOf(LockCheckingMessage(probe))
        val agent = AgentFactory().create(configuration)
        probe.agent.set(agent)
        try {
            probe.enabled = true
            agent.getState()
            assertUnblocked(probe)
        } finally { probe.close(); agent.close() }
    }

    @Test fun runHistoryCopyExecutesCustomCopyOutsideStateLock() {
        val probe = SnapshotLockProbe()
        val configuration = config(ScriptedModelClient(reply()))
        configuration.messages = listOf(LockCheckingMessage(probe))
        val agent = AgentFactory().create(configuration)
        probe.agent.set(agent)
        try {
            probe.enabled = true
            val callback = RecordingAgentCallback()
            val task = agent.run(AgentRequest("hello"), callback)
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertUnblocked(probe)
        } finally { probe.close(); agent.close() }
    }

    @Test fun committingCustomMessageExecutesCopyOutsideStateLock() {
        val probe = SnapshotLockProbe()
        val agent = create(ScriptedModelClient(reply()))
        probe.agent.set(agent)
        try {
            probe.enabled = true
            val request = AgentRequest()
            request.messages = listOf(LockCheckingMessage(probe))
            val task = agent.run(request, RecordingAgentCallback())
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertUnblocked(probe)
        } finally { probe.close(); agent.close() }
    }

    @Test fun enqueuingCustomMessageExecutesCopyOutsideAgentAndQueueLocks() {
        val probe = SnapshotLockProbe()
        val agent = create(ScriptedModelClient(reply()))
        probe.agent.set(agent)
        try {
            probe.enabled = true
            agent.steer(LockCheckingMessage(probe))
            agent.followUp(LockCheckingMessage(probe))
            assertUnblocked(probe)
        } finally { probe.close(); agent.close() }
    }

    @Test fun queuePreviewExecutesCustomCopyOutsideQueueLock() {
        val probe = SnapshotLockProbe()
        val agent = create(ScriptedModelClient(reply()))
        probe.agent.set(agent)
        try {
            agent.steer(LockCheckingMessage(probe))
            probe.enabled = true
            agent.peekQueuedMessages()
            assertUnblocked(probe)
        } finally { probe.close(); agent.close() }
    }

    private fun assertUnblocked(probe: SnapshotLockProbe) {
        assertTrue(probe.attempts.get() > 0, "必须实际执行自定义快照探针")
        assertEquals(0, probe.blocked.get(), "自定义快照不应阻塞其他线程读取状态或输入队列")
    }
}
