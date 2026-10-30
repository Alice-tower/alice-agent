package dev.agent.core

import dev.agent.core.api.*
import dev.agent.core.bean.*
import dev.agent.core.model.ModelCallback
import dev.agent.core.model.ModelClient
import dev.agent.core.runtime.AgentHooks
import dev.agent.core.runtime.Transcript
import dev.agent.core.tool.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LoopTest : CoreTestSupport() {
    private fun execute(config: AgentConfig): RecordingAgentCallback {
        val agent = AgentFactory().create(config)
        try {
            val callback = run(agent, "work")
            assertNotNull(callback.result, callback.failure?.toString())
            return callback
        } finally { agent.close() }
    }

    @Test fun toolsFeedModelAndCommitBeforeTurnEnd() {
        val model = ScriptedModelClient(calls("one"), reply("done"))
        val config = config(model)
        config.tools = listOf(textTool("one"))
        val callback = execute(config)
        assertEquals("done", text(callback.result!!.messages.last()))
        assertEquals(1, callback.result!!.messages.filterIsInstance<ToolResultMessage>().size)
        val resultEnd = callback.events.indexOfFirst { it.type == AgentEventType.MESSAGE_END && it.message is ToolResultMessage }
        val turnEnd = callback.events.indexOfFirst { it.type == AgentEventType.TURN_END }
        assertTrue(resultEnd < turnEnd)
        assertEquals("one", model.requests.last().context.messages.filterIsInstance<ToolResultMessage>().single().toolName)
    }

    @Test fun parallelPreflightPrecedesExecutionAndResultsRetainSourceOrder() {
        val order = CopyOnWriteArrayList<String>()
        val slowCallback = AtomicReference<ToolCallback>()
        val slow = object : AbstractAgentTool(definition("slow")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                order.add("execute-slow")
                slowCallback.set(callback)
                return NoOpCancellable()
            }
        }
        val fast = object : AbstractAgentTool(definition("fast")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                order.add("execute-fast")
                callback.onSuccess(ToolResults().text("fast"))
                return NoOpCancellable()
            }
        }
        val config = config(ScriptedModelClient(calls("slow", "fast"), reply()))
        config.tools = listOf(slow, fast)
        config.hooks = object : AgentHooks() {
            override fun beforeToolCall(context: BeforeToolCallContext, signal: CancellationToken): BeforeToolCallResult? {
                order.add("before-${context.toolCall.name}")
                return null
            }
        }
        val agent = AgentFactory().create(config)
        val callback = object : RecordingAgentCallback() {
            override fun onEvent(event: AgentEvent) {
                super.onEvent(event)
                if (event.type == AgentEventType.TOOL_EXECUTION_END && event.toolName == "fast") {
                    slowCallback.get().onSuccess(ToolResults().text("slow"))
                }
            }
        }
        try {
            agent.run(AgentRequest("work"), callback)
            callback.await()
            assertNotNull(callback.result, callback.failure?.toString())
            assertEquals(listOf("before-slow", "before-fast"), order.take(2))
            assertEquals(listOf("fast", "slow"), callback.events.filter { it.type == AgentEventType.TOOL_EXECUTION_END }.map { it.toolName })
            assertEquals(listOf("slow", "fast"), callback.result!!.messages.filterIsInstance<ToolResultMessage>().map { it.toolName })
        } finally { agent.close() }
    }

    @Test fun oneSequentialToolMakesWholeBatchSequential() {
        val order = CopyOnWriteArrayList<String>()
        val definitionA = definition("a")
        definitionA.executionMode = ToolExecutionMode.SEQUENTIAL
        val a = object : AbstractAgentTool(definitionA) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                order.add("a")
                callback.onSuccess(ToolResults().text("a"))
                return NoOpCancellable()
            }
        }
        val b = object : AbstractAgentTool(definition("b")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                order.add("b")
                callback.onSuccess(ToolResults().text("b"))
                return NoOpCancellable()
            }
        }
        val config = config(ScriptedModelClient(calls("a", "b"), reply()))
        config.tools = listOf(a, b)
        config.hooks = object : AgentHooks() {
            override fun beforeToolCall(context: BeforeToolCallContext, signal: CancellationToken): BeforeToolCallResult? {
                order.add("before-${context.toolCall.name}")
                return null
            }
        }
        execute(config)
        assertEquals(listOf("before-a", "a", "before-b", "b"), order)
    }

    @Test fun globalSequentialPolicyOverridesParallelTools() {
        val model = ScriptedModelClient(calls("a", "b"), reply())
        val config = config(model)
        config.toolExecution = ToolExecutionMode.SEQUENTIAL
        config.tools = listOf(textTool("a"), textTool("b"))
        val callback = execute(config)
        assertEquals(listOf("a", "b"), callback.events.filter { it.type == AgentEventType.TOOL_EXECUTION_END }.map { it.toolName })
    }

    @Test fun truncatedOutputNeverExecutesTools() {
        val executions = AtomicInteger()
        val tool = object : AbstractAgentTool(definition("a")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                executions.incrementAndGet()
                callback.onSuccess(ToolResults().text("a"))
                return NoOpCancellable()
            }
        }
        val config = config(ScriptedModelClient(calls("a", reason = StopReason.LENGTH), reply()))
        config.tools = listOf(tool)
        val callback = execute(config)
        assertEquals(0, executions.get())
        assertTrue(callback.result!!.messages.filterIsInstance<ToolResultMessage>().single().isError)
    }

    @Test fun unknownToolsAndSchemaFailuresAreModelVisible() {
        val definition = definition("known")
        definition.parameters = Json.parseToJsonElement("""{"type":"object","properties":{"x":{"type":"integer"}},"required":["x"]}""").jsonObject
        val tool = object : AbstractAgentTool(definition) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                fail("invalid arguments must never execute")
            }
        }
        val config = config(ScriptedModelClient(calls("missing", "known"), reply()))
        config.tools = listOf(tool)
        val results = execute(config).result!!.messages.filterIsInstance<ToolResultMessage>()
        assertEquals(2, results.size)
        assertTrue(results.all { it.isError })
        assertContains(text(results.first()), "not found")
    }

    @Test fun argumentPreparationRunsBeforeValidationAndOptionalNullsAreRemoved() {
        val definition = definition("known")
        definition.parameters = Json.parseToJsonElement("""{"type":"object","properties":{"x":{"type":"integer"},"optional":{"type":"string"}},"required":["x"]}""").jsonObject
        val received = AtomicReference<JsonElement>()
        val tool = object : AbstractAgentTool(definition) {
            override fun prepareArguments(arguments: JsonElement): JsonElement {
                return Json.parseToJsonElement("""{"x":"42","optional":null}""")
            }
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                received.set(context.arguments)
                callback.onSuccess(ToolResults().text("ok"))
                return NoOpCancellable()
            }
        }
        val config = config(ScriptedModelClient(calls("known"), reply()))
        config.tools = listOf(tool)
        execute(config)
        assertEquals(JsonPrimitive(42), received.get().jsonObject["x"])
        assertFalse("optional" in received.get().jsonObject)
    }

    @Test fun beforeHookMayReplaceValidatedArgumentsWithoutRevalidation() {
        val definition = definition("a")
        definition.parameters = Json.parseToJsonElement("""{"type":"object","properties":{"x":{"type":"integer"}},"required":["x"]}""").jsonObject
        val received = AtomicReference<JsonElement>()
        val tool = object : AbstractAgentTool(definition) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                received.set(context.arguments)
                callback.onSuccess(ToolResults().text("ok"))
                return NoOpCancellable()
            }
        }
        val message = calls("a")
        (message.content.single() as ToolCall).arguments = buildJsonObject { put("x", 1) }
        val replaced = buildJsonObject { put("x", "trusted hook override") }
        val config = config(ScriptedModelClient(message, reply()))
        config.tools = listOf(tool)
        config.hooks = object : AgentHooks() {
            override fun beforeToolCall(context: BeforeToolCallContext, signal: CancellationToken): BeforeToolCallResult? {
                context.args = replaced
                return null
            }
        }
        execute(config)
        assertEquals(replaced, received.get())
    }

    @Test fun schemaCoercionAcceptsPrefixesAndPreservesValidUnion() {
        val definition = definition("a")
        definition.parameters = Json.parseToJsonElement("""{"type":"object","properties":{"hex":{"type":"integer"},"bin":{"type":"integer"},"oct":{"type":"integer"},"bool":{"type":"boolean"},"union":{"type":["number","string"]}},"required":["hex","bin","oct","bool","union"]}""").jsonObject
        val args = Json.parseToJsonElement("""{"hex":"0x10","bin":"0b10","oct":"0o10","bool":1.0,"union":"42"}""")
        val result = SchemaValidator().validate(definition, call("1", "a", args)).jsonObject
        assertEquals(JsonPrimitive(16), result["hex"])
        assertEquals(JsonPrimitive(2), result["bin"])
        assertEquals(JsonPrimitive(8), result["oct"])
        assertEquals(JsonPrimitive(true), result["bool"])
        assertEquals(JsonPrimitive("42"), result["union"])
    }

    @Test fun parsedArgumentFailureDoesNotExecuteTool() {
        val callMessage = calls("a")
        (callMessage.content.single() as ToolCall).argumentsError = "invalid JSON"
        val config = config(ScriptedModelClient(callMessage, reply()))
        config.tools = listOf(textTool("a"))
        val results = execute(config).result!!.messages.filterIsInstance<ToolResultMessage>()
        assertTrue(results.single().isError)
        assertContains(text(results.single()), "invalid JSON")
    }

    @Test fun blockedToolCanTerminateBatchAndSkipModelFollowup() {
        val model = ScriptedModelClient(calls("a"))
        val config = config(model)
        config.tools = listOf(textTool("a"))
        config.hooks = object : AgentHooks() {
            override fun beforeToolCall(context: BeforeToolCallContext, signal: CancellationToken): BeforeToolCallResult {
                val decision = BeforeToolCallResult()
                decision.block = true
                decision.reason = "no permission"
                decision.terminate = true
                return decision
            }
        }
        val callback = execute(config)
        assertEquals(1, model.count.get())
        assertIs<ToolResultMessage>(callback.result!!.messages.last())
    }

    @Test fun mixedTerminateResultsStillContinue() {
        val a = object : AbstractAgentTool(definition("a")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                val result = ToolResults().text("a")
                result.terminate = true
                callback.onSuccess(result)
                return NoOpCancellable()
            }
        }
        val config = config(ScriptedModelClient(calls("a", "b"), reply("continued")))
        config.tools = listOf(a, textTool("b"))
        assertEquals("continued", text(execute(config).result!!.messages.last()))
    }

    @Test fun allTerminateResultsEndWithoutAnotherModelRequest() {
        val result = ToolResults().text("final")
        result.terminate = true
        val a = object : AbstractAgentTool(definition("a")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                callback.onSuccess(result)
                return NoOpCancellable()
            }
        }
        val model = ScriptedModelClient(calls("a"))
        val config = config(model)
        config.tools = listOf(a)
        assertIs<ToolResultMessage>(execute(config).result!!.messages.last())
        assertEquals(1, model.count.get())
    }

    @Test fun afterHookReplacingContentInvalidatesStructuredResult() {
        val a = object : AbstractAgentTool(definition("a")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                val result = ToolResults().text("old")
                result.structuredContent = buildJsonObject { put("x", 1) }
                callback.onSuccess(result)
                return NoOpCancellable()
            }
        }
        val config = config(ScriptedModelClient(calls("a"), reply()))
        config.tools = listOf(a)
        config.hooks = object : AgentHooks() {
            override fun afterToolCall(context: AfterToolCallContext, signal: CancellationToken): AfterToolCallResult {
                val result = AfterToolCallResult()
                result.content = listOf(TextContent("new"))
                return result
            }
        }
        val event = execute(config).events.single { it.type == AgentEventType.TOOL_EXECUTION_END }
        assertNull(event.result!!.structuredContent)
        assertEquals("new", (event.result!!.content.single() as TextContent).text)
    }

    @Test fun thrownToolAndHookFailuresBecomeToolErrors() {
        val bad = object : AbstractAgentTool(definition("bad")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                throw IllegalArgumentException("tool failed")
            }
        }
        val config = config(ScriptedModelClient(calls("bad", "after"), reply()))
        config.tools = listOf(bad, textTool("after"))
        config.hooks = object : AgentHooks() {
            override fun afterToolCall(context: AfterToolCallContext, signal: CancellationToken): AfterToolCallResult? {
                if (context.toolCall.name == "after") throw IllegalStateException("after failed")
                return null
            }
        }
        val results = execute(config).result!!.messages.filterIsInstance<ToolResultMessage>()
        assertTrue(results.all { it.isError })
        assertContains(text(results[0]), "tool failed")
        assertContains(text(results[1]), "after failed")
    }

    @Test fun prepareRequestRunsFirstAndTransformPrecedesConvert() {
        val order = CopyOnWriteArrayList<String>()
        val model = object : ScriptedModelClient(reply()) {
            override fun respond(request: ModelRequest, index: Int): AssistantMessage {
                order.add("stream")
                assertEquals("next", request.model.id)
                assertEquals(ThinkingLevel.HIGH, request.options.reasoning)
                return super.respond(request, index)
            }
        }
        val config = config(model)
        config.hooks = object : AgentHooks() {
            override fun prepareRequest(context: PrepareRequestContext, signal: CancellationToken): AgentLoopTurnUpdate {
                order.add("prepare")
                val update = AgentLoopTurnUpdate()
                update.model = Model()
                update.model!!.id = "next"
                update.thinkingLevel = ThinkingLevel.HIGH
                return update
            }
            override fun transformContext(messages: List<AgentMessage>, signal: CancellationToken): List<AgentMessage> {
                order.add("transform")
                return messages
            }
            override fun convertToLlm(messages: List<AgentMessage>): List<AgentMessage> {
                order.add("convert")
                return messages
            }
        }
        execute(config)
        assertEquals(listOf("prepare", "transform", "convert", "stream"), order)
    }

    @Test fun explicitContinueAddsExactlyOneContextOnlyRequest() {
        val turns = AtomicInteger()
        val model = ScriptedModelClient(reply(), reply())
        val config = config(model)
        config.hooks = object : AgentHooks() {
            override fun finishTurn(context: AgentTurnContext, signal: CancellationToken): AgentTurnDecision? {
                return if (turns.incrementAndGet() == 1) AgentTurnDecision.CONTINUE else null
            }
        }
        assertEquals(2, execute(config).result!!.messages.filterIsInstance<AssistantMessage>().size)
        assertEquals(2, model.count.get())
    }

    @Test fun prepareNextTurnCanReplaceToolsAndAppendMessages() {
        val model = ScriptedModelClient(reply(), calls("new"), reply())
        val turns = AtomicInteger()
        val config = config(model)
        config.hooks = object : AgentHooks() {
            override fun finishTurn(context: AgentTurnContext, signal: CancellationToken): AgentTurnDecision? {
                return if (turns.incrementAndGet() == 1) AgentTurnDecision.CONTINUE else null
            }
            override fun prepareNextTurn(context: AgentTurnContext, signal: CancellationToken): AgentLoopTurnUpdate {
                val update = AgentLoopTurnUpdate()
                val replacement = AgentContext()
                replacement.messages = context.context.messages
                replacement.tools = listOf(textTool("new"))
                update.context = replacement
                update.messages = listOf(UserMessage("prepared"))
                return update
            }
        }
        val callback = execute(config)
        assertEquals("new", callback.result!!.messages.filterIsInstance<ToolResultMessage>().single().toolName)
        assertTrue(model.requests[1].context.messages.filterIsInstance<UserMessage>().any { text(it) == "prepared" })
        assertTrue(Transcript().currentTools(model.requests[1].context.messages).any { it.name == "new" })
    }

    @Test fun modelErrorStopsWithoutConsumingFollowupsEvenIfHookRequestsContinue() {
        val config = config(ScriptedModelClient(reply(reason = StopReason.ERROR)))
        val finished = AtomicInteger()
        config.hooks = object : AgentHooks() {
            override fun finishTurn(context: AgentTurnContext, signal: CancellationToken): AgentTurnDecision {
                finished.incrementAndGet()
                return AgentTurnDecision.CONTINUE
            }
        }
        val agent = AgentFactory().create(config)
        try {
            agent.followUp(UserMessage("pending"))
            val callback = run(agent)
            assertNotNull(callback.failure)
            assertEquals(1, finished.get())
            assertTrue(agent.hasQueuedMessages())
        } finally { agent.close() }
    }

    @Test fun lateAndDuplicateToolCallbacksAreIgnored() {
        val stored = AtomicReference<ToolCallback>()
        val a = object : AbstractAgentTool(definition("a")) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                stored.set(callback)
                callback.onProgress(ToolResults().text("during"))
                callback.onSuccess(ToolResults().text("done"))
                callback.onSuccess(ToolResults().text("duplicate"))
                callback.onFailure(IllegalStateException("late failure"))
                callback.onProgress(ToolResults().text("late"))
                return NoOpCancellable()
            }
        }
        val config = config(ScriptedModelClient(calls("a"), reply()))
        config.tools = listOf(a)
        val callback = execute(config)
        stored.get().onProgress(ToolResults().text("after run"))
        assertEquals(1, callback.events.count { it.type == AgentEventType.TOOL_EXECUTION_UPDATE })
        assertEquals(1, callback.events.count { it.type == AgentEventType.TOOL_EXECUTION_END })
        assertEquals("done", text(callback.result!!.messages.filterIsInstance<ToolResultMessage>().single()))
    }

    @Test fun duplicateModelTerminalDoesNotCreateAnotherTurnOrEnding() {
        val model = object : ModelClient {
            override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
                val event = AssistantMessageEvent()
                event.type = "done"
                event.message = reply()
                callback.onEvent(event)
                callback.onEvent(event)
                callback.onComplete()
                callback.onFailure(IllegalStateException("late failure"))
                return NoOpCancellable()
            }
        }
        val callback = execute(config(model))
        assertEquals(1, callback.events.count { it.type == AgentEventType.AGENT_END })
        assertEquals(1, callback.result!!.messages.filterIsInstance<AssistantMessage>().size)
    }

    @Test fun finalEventContainsAllNewMessagesAndSystemReplayMatchesLoadout() {
        val a = textTool("a")
        val b = textTool("b")
        val first = SystemMessage()
        first.content = "base"
        first.sections = mapOf("memory" to "old")
        first.toolsAdded = listOf(a.getDefinition().declaration())
        val second = SystemMessage()
        second.content = "extra"
        second.sections = mapOf("memory" to "new")
        val history = listOf(first, UserMessage("hello"), second)
        val transcript = Transcript()
        assertEquals("base\n\nextra\n\nnew", transcript.currentSystemPrompt(history))
        val context = AgentContext()
        context.messages = history
        context.tools = listOf(b)
        val pending = transcript.declareToolChanges(context, listOf(UserMessage("next")))
        assertEquals(listOf("b"), transcript.currentTools(history + pending).map { it.name })
        assertIs<SystemMessage>(pending.first())
        val callback = execute(config(ScriptedModelClient(reply())))
        assertEquals(2, callback.events.last().messages.size)
    }
}
