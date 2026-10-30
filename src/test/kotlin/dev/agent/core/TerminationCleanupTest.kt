package dev.agent.core

import dev.agent.core.api.AgentFactory
import dev.agent.core.api.Cancellable
import dev.agent.core.api.CancellationToken
import dev.agent.core.api.NoOpCancellable
import dev.agent.core.bean.AfterToolCallContext
import dev.agent.core.bean.AfterToolCallResult
import dev.agent.core.bean.AgentEvent
import dev.agent.core.bean.AgentEventType
import dev.agent.core.bean.AgentRequest
import dev.agent.core.bean.TaskStatus
import dev.agent.core.bean.AgentToolResult
import dev.agent.core.bean.ToolContext
import dev.agent.core.bean.ToolResultMessage
import dev.agent.core.runtime.AgentHooks
import dev.agent.core.tool.AbstractAgentTool
import dev.agent.core.tool.ToolCallback
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 终结时的数据复制失败也必须完成任务清理与一次性失败通知。 */
class TerminationCleanupTest : CoreTestSupport() {
    @Test fun contextSnapshotFailureAfterAsynchronousToolCompletionStillFinishes() {
        val fail = AtomicBoolean()
        val configuration = config(ScriptedModelClient(calls("first")))
        configuration.tools = listOf(textTool("first"))
        configuration.hooks = object : AgentHooks() {
            override fun afterToolCall(context: AfterToolCallContext, signal: CancellationToken): AfterToolCallResult? {
                fail.set(true)
                return null
            }
        }
        val agent = AgentFactory().create(configuration)
        val request = AgentRequest()
        request.messages = listOf(ThrowingSnapshotMessage(fail))
        try {
            val callback = RecordingAgentCallback()
            val task = agent.run(request, callback)
            assertTrue(task.await(5, TimeUnit.SECONDS))
            fail.set(false)
            assertEquals(TaskStatus.FAILED, task.getStatus())
            assertNotNull(callback.failure)
            assertEquals(1, callback.terminalCount.get())
            assertFalse(agent.getState().isStreaming)
            assertEquals(1, agent.getState().messages.filterIsInstance<ToolResultMessage>().size)
        } finally { fail.set(false); agent.close() }
    }

    @Test fun toolResultSnapshotFailureAfterHookBecomesAnErrorResult() {
        val fail = AtomicBoolean()
        val configuration = config(ScriptedModelClient(calls("first"), reply("recovered")))
        configuration.tools = listOf(object : AbstractAgentTool(definition("first")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                val result = AgentToolResult()
                result.content = listOf(ThrowingSnapshotContent(fail))
                callback.onSuccess(result)
                return NoOpCancellable()
            }
        })
        configuration.hooks = object : AgentHooks() {
            override fun afterToolCall(context: AfterToolCallContext, signal: CancellationToken): AfterToolCallResult? {
                fail.set(true)
                return null
            }
        }
        val agent = AgentFactory().create(configuration)
        try {
            val callback = run(agent)
            assertNull(callback.failure)
            val result = agent.getState().messages.filterIsInstance<ToolResultMessage>().single()
            assertTrue(result.isError)
            assertEquals("测试工具内容无法生成快照", text(result))
            assertEquals(1, callback.terminalCount.get())
            assertFalse(agent.getState().isStreaming)
        } finally { agent.close() }
    }

    @Test fun terminationSnapshotFailureReleasesAgentAndNotifiesFailureOnce() {
        val fail = AtomicBoolean()
        val model = ScriptedModelClient(reply("first"), reply("recovered"))
        val agent = AgentFactory().create(config(model))
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.TURN_END) fail.set(true)
            }
        }
        val request = AgentRequest()
        request.messages = listOf(ThrowingSnapshotMessage(fail))
        try {
            val task = agent.run(request, callback)
            assertTrue(task.await(5, TimeUnit.SECONDS), "终结复制失败仍须完成任务句柄")
            assertEquals(TaskStatus.FAILED, task.getStatus())
            assertNotNull(callback.failure)
            assertNull(callback.result)
            assertFalse(callback.cancelled)
            assertEquals(1, callback.terminalCount.get())
            // 替换损坏的历史后，无需修复已经不再使用的初始配置对象即可再次运行。
            agent.restoreHistory(emptyList())
            assertFalse(agent.getState().isStreaming)
            val recovered = run(agent)
            assertNull(recovered.failure)
            assertEquals("recovered", text(agent.getState().messages.last()))
        } finally {
            fail.set(false)
            agent.close()
        }
    }
}
