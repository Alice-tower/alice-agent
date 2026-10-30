package dev.agent.core

import dev.agent.core.api.*
import dev.agent.core.bean.*
import dev.agent.core.model.*
import dev.agent.core.runtime.AgentHooks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test
import kotlin.test.*

class AgentLifecycleTest : CoreTestSupport() {
    @Test fun runCommitsMessagesAndPublishesLifecycleInOrder() {
        val agent = create(ScriptedModelClient(reply()))
        try {
            val callback = run(agent)
            assertEquals(listOf("user", "assistant"), agent.getState().messages.map { it.role })
            assertEquals(AgentEventType.AGENT_START, callback.events.first().type)
            assertEquals(AgentEventType.AGENT_END, callback.events.last().type)
            assertEquals(1, callback.events.count { it.type == AgentEventType.TURN_START })
            assertEquals(1, callback.events.count { it.type == AgentEventType.TURN_END })
            assertEquals(1, callback.terminalCount.get())
            assertNotNull(callback.result)
            assertFalse(agent.getState().isStreaming)
            assertNull(agent.getState().streamingMessage)
            assertTrue(agent.getState().pendingToolCalls.isEmpty())
        } finally { agent.close() }
    }

    @Test fun taskCompletionWaitsForFinalEventAndCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val agent = create(ScriptedModelClient(reply()))
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.AGENT_END) {
                    entered.countDown()
                    waitFor(release)
                }
            }
            override fun onSuccess(result: AgentResult) {
                assertFalse(agent.getState().isStreaming)
                callbackEntered.countDown()
                waitFor(callbackRelease)
                super.onSuccess(result)
            }
        }
        try {
            val task = agent.run(AgentRequest("hello"), callback)
            waitFor(entered)
            assertTrue(agent.getState().isStreaming)
            assertFalse(task.await(20, TimeUnit.MILLISECONDS))
            release.countDown()
            waitFor(callbackEntered)
            assertFalse(task.await(20, TimeUnit.MILLISECONDS))
            callbackRelease.countDown()
            assertTrue(task.await(5, TimeUnit.SECONDS))
            callback.await()
        } finally { release.countDown(); callbackRelease.countDown(); agent.close() }
    }

    @Test fun concurrentRunContinueResetAndConfigUpdateAreRejected() {
        val entered = CountDownLatch(1)
        val pending = AtomicReference<ModelCallback>()
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                pending.set(callback)
                entered.countDown()
                return NoOpCancellable()
            }
        }
        val agent = create(model)
        try {
            val callback = RecordingAgentCallback()
            agent.run(AgentRequest("first"), callback)
            waitFor(entered)
            assertFailsWith<IllegalStateException> { agent.run(AgentRequest("second"), RecordingAgentCallback()) }
            assertFailsWith<IllegalStateException> { agent.continueRun(RecordingAgentCallback()) }
            assertFailsWith<IllegalStateException> { agent.reset() }
            assertFailsWith<IllegalStateException> { agent.updateConfig(config(model)) }
            assertFailsWith<IllegalStateException> { agent.restoreHistory(listOf(UserMessage("restored"))) }
            val done = AssistantMessageEvent()
            done.type = "done"
            done.message = reply()
            pending.get().onEvent(done)
            pending.get().onComplete()
            callback.await()
            assertEquals(listOf("first"), agent.getState().messages.filterIsInstance<UserMessage>().map { text(it) })
        } finally { agent.close() }
    }

    @Test fun cancelDuringFinalEventPreservesSuccessfulResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val agent = create(ScriptedModelClient(reply("completed")))
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.AGENT_END) {
                    entered.countDown()
                    waitFor(release)
                }
            }
        }
        try {
            val task = agent.run(AgentRequest("hello"), callback)
            waitFor(entered)
            task.cancel()
            release.countDown()
            callback.await()
            assertNotNull(callback.result)
            assertFalse(callback.cancelled)
            assertEquals(1, callback.events.count { it.type == AgentEventType.AGENT_END })
            assertEquals("completed", text(agent.getState().messages.last()))
        } finally { release.countDown(); agent.close() }
    }

    @Test fun thrownModelFailureBecomesFinalizedAssistantError() {
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                throw IllegalArgumentException("provider unavailable")
            }
        }
        val agent = create(model)
        try {
            val callback = run(agent)
            assertEquals("provider unavailable", callback.failure?.message)
            val result = agent.getState().messages.filterIsInstance<AssistantMessage>().last()
            assertEquals(StopReason.ERROR, result.stopReason)
            assertEquals("provider unavailable", agent.getState().errorMessage)
            assertEquals(AgentEventType.AGENT_END, callback.events.last().type)
            assertFalse(agent.getState().isStreaming)
        } finally { agent.close() }
    }

    @Test fun completionWithoutTerminalEventFailsOnce() {
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                callback.onComplete()
                callback.onComplete()
                return NoOpCancellable()
            }
        }
        val agent = create(model)
        try {
            val callback = run(agent)
            assertNotNull(callback.failure)
            assertEquals(1, callback.terminalCount.get())
            assertEquals(1, callback.events.count { it.type == AgentEventType.AGENT_END })
        } finally { agent.close() }
    }

    @Test fun cancellationReachesModelAndIgnoresLateCallbacks() {
        val entered = CountDownLatch(1)
        val adapterCancelled = CountDownLatch(1)
        val token = AtomicReference<CancellationToken>()
        val pending = AtomicReference<ModelCallback>()
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                token.set(request.options.signal)
                pending.set(callback)
                entered.countDown()
                return object : Cancellable {
                    override fun cancel() { adapterCancelled.countDown() }
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
            waitFor(adapterCancelled)
            assertTrue(token.get().cancelled)
            assertTrue(callback.cancelled)
            assertEquals(TaskStatus.CANCELLED, task.getStatus())
            val snapshot = agent.getState().messages.size
            val late = AssistantMessageEvent()
            late.type = "done"
            late.message = reply("late")
            pending.get().onEvent(late)
            pending.get().onFailure(IllegalStateException("late error"))
            assertEquals(snapshot, agent.getState().messages.size)
            assertEquals(1, callback.terminalCount.get())
        } finally { agent.close() }
    }

    @Test fun successCallbackCanStartNextRun() {
        val agent = create(ScriptedModelClient(reply("first"), reply("second")))
        val second = RecordingAgentCallback()
        val first = object : RecordingAgentCallback() {
            override fun onSuccess(result: AgentResult) {
                assertFalse(agent.getState().isStreaming)
                agent.run(AgentRequest("next"), second)
                super.onSuccess(result)
            }
        }
        try {
            agent.run(AgentRequest("hello"), first)
            first.await()
            second.await()
            assertEquals(listOf("hello", "next"), agent.getState().messages.filterIsInstance<UserMessage>().map { text(it) })
        } finally { agent.close() }
    }

    @Test fun observerExceptionsDoNotLeaveTaskRunning() {
        val agent = create(ScriptedModelClient(reply()))
        val finished = CountDownLatch(1)
        val callback = object : AgentCallbackAdapter() {
            override fun onEvent(event: AgentEvent) { throw IllegalStateException("observer failure") }
            override fun onSuccess(result: AgentResult) {
                finished.countDown()
                throw IllegalStateException("final observer failure")
            }
        }
        try {
            val task = agent.run(AgentRequest("hello"), callback)
            waitFor(finished)
            assertTrue(task.await(5, TimeUnit.SECONDS))
            assertFalse(agent.getState().isStreaming)
            assertEquals(TaskStatus.SUCCEEDED, task.getStatus())
        } finally { agent.close() }
    }

    @Test fun oneAtATimeSteeringPrecedesFollowups() {
        val model = ScriptedModelClient(reply(), reply(), reply())
        val agent = create(model)
        try {
            agent.steer(UserMessage("steer-1"))
            agent.steer(UserMessage("steer-2"))
            agent.followUp(UserMessage("later"))
            run(agent, "initial")
            assertEquals(listOf(
                listOf("initial", "steer-1"),
                listOf("initial", "steer-1", "steer-2"),
                listOf("initial", "steer-1", "steer-2", "later")
            ), model.requests.map { request -> request.context.messages.filterIsInstance<UserMessage>().map { text(it) } })
            assertFalse(agent.hasQueuedMessages())
        } finally { agent.close() }
    }

    @Test fun finishTurnEndPreservesQueuedWorkAndSkipsNextPreparation() {
        val prepared = AtomicInteger()
        val config = config(ScriptedModelClient(reply()))
        config.hooks = object : AgentHooks() {
            override fun finishTurn(context: AgentTurnContext, signal: CancellationToken): AgentTurnDecision = AgentTurnDecision.END
            override fun prepareNextTurn(context: AgentTurnContext, signal: CancellationToken): AgentLoopTurnUpdate? {
                prepared.incrementAndGet()
                return null
            }
        }
        val agent = AgentFactory().create(config)
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.MESSAGE_END && event.message is AssistantMessage) {
                    agent.steer(UserMessage("pending steer"))
                    agent.followUp(UserMessage("pending follow"))
                }
            }
        }
        try {
            agent.run(AgentRequest("hello"), callback)
            callback.await()
            assertTrue(agent.hasQueuedMessages())
            assertEquals("pending steer", text(agent.peekQueuedMessages().single()))
            assertEquals(0, prepared.get())
        } finally { agent.close() }
    }

    @Test fun continueFromAssistantTailConsumesQueuedFollowup() {
        val agent = create(ScriptedModelClient(reply(), reply()))
        try {
            run(agent, "one")
            agent.followUp(UserMessage("two"))
            val callback = RecordingAgentCallback()
            agent.continueRun(callback)
            callback.await()
            assertEquals(listOf("one", "two"), agent.getState().messages.filterIsInstance<UserMessage>().map { text(it) })
        } finally { agent.close() }
    }

    @Test fun emptyContinuationRetainsQueuedMessages() {
        val agent = create(ScriptedModelClient(reply()))
        try {
            agent.followUp(UserMessage("queued"))
            assertFailsWith<IllegalStateException> { agent.continueRun(RecordingAgentCallback()) }
            assertEquals("queued", text(agent.peekQueuedMessages().single()))
        } finally { agent.close() }
    }

    @Test fun queuePreviewIsCopiedAndClearOperationsAreIndependent() {
        val config = config(ScriptedModelClient(reply()))
        config.steeringMode = QueueMode.ALL
        val agent = AgentFactory().create(config)
        try {
            val source = UserMessage("a")
            agent.steer(source)
            (source.content.single() as TextContent).text = "external"
            agent.steer(UserMessage("b"))
            agent.followUp(UserMessage("c"))
            val peek = agent.peekQueuedMessages()
            assertEquals(listOf("a", "b"), peek.map { text(it) })
            (peek.first() as UserMessage).content = listOf(TextContent("changed"))
            assertEquals("a", text(agent.peekQueuedMessages().first()))
            agent.clearSteeringQueue()
            assertEquals("c", text(agent.peekQueuedMessages().single()))
            agent.clearAllQueues()
            assertFalse(agent.hasQueuedMessages())
        } finally { agent.close() }
    }

    @Test fun customMessagesAreConvertedAfterContextTransform() {
        val order = ArrayList<String>()
        val model = ScriptedModelClient(reply())
        val config = config(model)
        config.hooks = object : AgentHooks() {
            override fun transformContext(messages: List<AgentMessage>, signal: CancellationToken): List<AgentMessage> {
                order.add("transform")
                return messages + TestNoteMessage("note content")
            }
            override fun convertToLlm(messages: List<AgentMessage>): List<AgentMessage> {
                order.add("convert")
                return messages.map { if (it is TestNoteMessage) UserMessage(it.text) else it }
            }
        }
        val agent = AgentFactory().create(config)
        try {
            run(agent)
            assertEquals(listOf("transform", "convert"), order)
            assertEquals("note content", text(model.requests.single().context.messages.last()))
            assertTrue(agent.getState().messages.none { it is TestNoteMessage })
        } finally { agent.close() }
    }

    @Test fun sourceConfigurationStateAndEventsAreIndependentSnapshots() {
        val source = UserMessage("saved")
        val history = arrayListOf<AgentMessage>(source)
        val model = ScriptedModelClient(reply())
        val config = config(model)
        config.messages = history
        config.model.id = "original"
        config.systemPrompt = "base"
        val agent = AgentFactory().create(config)
        try {
            history.clear()
            (source.content.single() as TextContent).text = "external"
            config.model.id = "external"
            val snapshot = agent.getState()
            assertEquals("saved", text(snapshot.messages.filterIsInstance<UserMessage>().single()))
            snapshot.model.id = "changed"
            snapshot.messages.filterIsInstance<UserMessage>().single().content = listOf(TextContent("changed"))
            assertEquals("original", agent.getState().model.id)
            assertEquals("saved", text(agent.getState().messages.filterIsInstance<UserMessage>().single()))
            val callback = run(agent)
            val eventMessage = callback.events.first { it.type == AgentEventType.MESSAGE_END && it.message is AssistantMessage }.message as AssistantMessage
            eventMessage.content = listOf(TextContent("mutated event"))
            assertEquals("ok", text(agent.getState().messages.last()))
            agent.followUp(UserMessage("queued"))
            agent.reset()
            assertFalse(agent.hasQueuedMessages())
            assertEquals(1, agent.getState().messages.size)
            assertEquals("base", agent.getState().systemPrompt)
        } finally { agent.close() }
    }

    @Test fun dynamicConfigurationCopiesOptionsAndResolvesCredentials() {
        val model = ScriptedModelClient(reply(), reply())
        val config = config(model)
        config.streamOptions.sessionId = "first"
        config.hooks = object : AgentHooks() {
            override fun getApiKey(provider: String): String = "resolved"
        }
        val agent = AgentFactory().create(config)
        try {
            run(agent)
            val next = config(model)
            next.streamOptions.sessionId = "second"
            next.hooks = config.hooks
            agent.updateConfig(next)
            next.streamOptions.sessionId = "external"
            run(agent)
            assertEquals(listOf("first", "second"), model.requests.map { it.options.sessionId })
            assertEquals(listOf("resolved", "resolved"), model.requests.map { it.options.apiKey })
        } finally { agent.close() }
    }

    @Test fun restoringHistoryCopiesMessagesClearsQueuesAndAddsSystemBaseline() {
        val config = config(ScriptedModelClient(reply()))
        config.systemPrompt = "base"
        val agent = AgentFactory().create(config)
        try {
            run(agent, "old conversation")
            agent.followUp(UserMessage("discard this queue"))
            val message = UserMessage("restored conversation")
            val history = arrayListOf<AgentMessage>(message)
            agent.restoreHistory(history)
            history.clear()
            message.content = listOf(TextContent("external change"))
            val state = agent.getState()
            assertFalse(agent.hasQueuedMessages())
            assertEquals("base", state.systemPrompt)
            assertIs<SystemMessage>(state.messages.first())
            assertEquals(listOf("restored conversation"), state.messages.filterIsInstance<UserMessage>().map { text(it) })
        } finally { agent.close() }
    }

    @Test fun closingAgentDoesNotCloseSharedWorkerExecutor() {
        val executor = Executors.newFixedThreadPool(2)
        val config = config(ScriptedModelClient(reply()))
        config.workerExecutor = executor
        val agent = AgentFactory().create(config)
        try {
            run(agent)
            agent.close()
            agent.close()
            assertFalse(executor.isShutdown)
            assertEquals("still usable", executor.submit<String> { "still usable" }.get(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { agent.run(AgentRequest("closed"), RecordingAgentCallback()) }
        } finally { agent.close(); executor.shutdownNow() }
    }

    @Test fun closingActiveAgentCancelsTaskAndIsIdempotent() {
        val entered = CountDownLatch(1)
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                entered.countDown()
                return NoOpCancellable()
            }
        }
        val agent = create(model)
        val callback = RecordingAgentCallback()
        agent.run(AgentRequest("hello"), callback)
        waitFor(entered)
        agent.close()
        agent.close()
        callback.await()
        assertTrue(callback.cancelled)
        assertEquals(1, callback.terminalCount.get())
    }
}
