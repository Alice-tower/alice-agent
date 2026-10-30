package dev.agent.core

import dev.agent.core.api.*
import dev.agent.core.bean.*
import dev.agent.core.protocol.ProtocolCodec
import dev.agent.core.runtime.CancellationSource
import dev.agent.core.runtime.Transcript
import java.beans.Introspector
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 消息协议、JavaBean 和深拷贝契约均不依赖外部模型服务。 */
class ProtocolContractTest : CoreTestSupport() {
    @Test fun requestsCarryNoImplicitIdentityAndPreserveExplicitMetadata() {
        val identities = listOf(
            null,
            Json.parseToJsonElement("""{"attribution":{"name":"example-app"}}""").jsonObject,
            Json.parseToJsonElement("""{"attribution":null}""").jsonObject
        )
        for (identity in identities) {
            val model = ScriptedModelClient(reply())
            val config = config(model)
            config.streamOptions.identity = identity
            val agent = AgentFactory().create(config)
            try {
                val callback = run(agent)
                assertNotNull(callback.result, callback.failure?.toString())
                assertEquals(identity ?: JsonObject(emptyMap()), model.requests.single().options.identity)
            } finally { agent.close() }
        }
    }

    @Test fun assistantMetadataAndDeferredReasonSurviveRuntimeAndWire() {
        val metadata = Json.parseToJsonElement("""{"responseId":"response-42","responseModel":"resolved-model","deferred":{"id":"batch-42","provider":"test"}}""").jsonObject
        val response = reply(reason = StopReason.DEFERRED)
        response.extra = metadata
        val agent = create(ScriptedModelClient(response))
        try {
            run(agent, "run later")
            val result = agent.getState().messages.filterIsInstance<AssistantMessage>().single()
            assertEquals(StopReason.DEFERRED, result.stopReason)
            val encoded = ProtocolCodec().message(result)
            assertEquals(metadata["responseId"], encoded["responseId"])
            assertEquals(metadata["deferred"], encoded["deferred"])
            assertEquals(encoded, ProtocolCodec().message(ProtocolCodec().decodeMessage(encoded)))
        } finally { agent.close() }
    }

    @Test fun signedSystemTextBlocksKeepWireAndReplayText() {
        val signed = TextContent("one")
        signed.textSignature = "signature"
        val system = SystemMessage()
        system.content = "one\ntwo"
        system.contentBlocks = listOf(signed, TextContent("two"))
        assertEquals("one\ntwo", Transcript().currentSystemPrompt(listOf(system)))
        val codec = ProtocolCodec()
        val encoded = codec.message(system)
        assertEquals(JsonPrimitive("signature"), encoded["content"]!!.jsonArray[0].jsonObject["textSignature"])
        assertEquals(encoded, codec.message(codec.decodeMessage(encoded)))
    }

    @Test fun toolCallRoundtripKeepsSignaturesUnknownFieldsAndArgumentsError() {
        val wire = Json.parseToJsonElement("""{"type":"toolCall","id":"call1","name":"lookup","arguments":{"value":"final"},"namespace":"dynamic_tools","thoughtSignature":"sig","argumentsError":"invalid"}""").jsonObject
        val codec = ProtocolCodec()
        val call = codec.decodeToolCall(wire)
        assertEquals("sig", call.thoughtSignature)
        assertEquals("invalid", call.argumentsError)
        assertEquals("dynamic_tools", call.extra["namespace"]!!.jsonPrimitive.content)
        assertEquals(wire, codec.content(call))
    }

    @Test fun usageModelAndToolDeclarationRoundtripKeepExplicitMetadata() {
        val codec = ProtocolCodec()
        val model = Model()
        model.id = "test"
        model.provider = "provider"
        model.reasoning = true
        model.input = listOf("text", "image")
        model.contextWindow = 123
        model.extra = buildJsonObject { put("future", "kept") }
        val encodedModel = codec.model(model)
        assertEquals(encodedModel, codec.model(codec.decodeModel(encodedModel)))
        val usage = Usage()
        usage.input = 1
        usage.output = 2
        usage.cacheRead = 3
        usage.cacheWrite = 4
        usage.totalTokens = 10
        usage.cost = buildJsonObject { put("total", 0.25) }
        assertEquals(codec.usage(usage), codec.usage(codec.decodeUsage(codec.usage(usage))))
        assertEquals(0L, codec.decodeUsage(null).totalTokens)
        val tool = definition("lookup")
        tool.constrainedSampling = JsonPrimitive(false)
        val encodedTool = codec.tool(tool.declaration())
        assertEquals(encodedTool, codec.tool(codec.decodeToolDeclaration(encodedTool)))
    }

    @Test fun optionsWhitelistExcludesCredentialsAndCallbacks() {
        val options = StreamOptions()
        options.apiKey = "provider-secret"
        options.sessionId = "session"
        options.temperature = 0.5
        options.identity = buildJsonObject { put("attribution", JsonNull) }
        options.headers = mapOf("x-example" to "value")
        options.thinkingBudgets = mapOf("high" to 100)
        val encoded = ProtocolCodec().options(options)
        assertFalse(encoded.toString().contains("provider-secret"))
        assertFalse(encoded.containsKey("signal"))
        assertFalse(encoded.containsKey("onPayload"))
        assertEquals("session", encoded["sessionId"]!!.jsonPrimitive.content)
        assertEquals(100, encoded["thinkingBudgets"]!!.jsonObject["high"]!!.jsonPrimitive.int)
    }

    @Test fun transcriptSectionsCanBeUpdatedAndRemoved() {
        val first = SystemMessage()
        first.content = "base"
        first.sections = mapOf("memory" to "old", "policy" to "keep")
        val second = SystemMessage()
        second.sections = mapOf("memory" to "new", "policy" to null)
        val history = listOf(first, UserMessage("hello"), second)
        val current = Transcript().currentSystemMessage(history)!!
        assertEquals(mapOf("memory" to "new"), current.sections)
        assertEquals("base\n\nnew", Transcript().currentSystemPrompt(history))
    }

    @Test fun unchangedDeclarationsDoNotEmitSpuriousUpdates() {
        val tool = textTool("lookup")
        val first = SystemMessage()
        first.toolsAdded = listOf(BeanCopier().toolDeclaration(tool.getDefinition().declaration()))
        val context = AgentContext()
        context.messages = listOf(first)
        context.tools = listOf(tool)
        val pending = Transcript().declareToolChanges(context, listOf(UserMessage("next")))
        assertEquals(1, pending.size)
        assertIs<UserMessage>(pending.single())
    }

    @Test fun customMessagesRequireConversionBeforeEncoding() {
        assertFailsWith<IllegalArgumentException> { ProtocolCodec().message(TestNoteMessage("private UI note")) }
        val copy = BeanCopier().message(TestNoteMessage("original")) as TestNoteMessage
        assertEquals("original", copy.text)
    }

    @Test fun snapshotsCopyNestedBeansCollectionsAndJsonTrees() {
        val source = reply("original")
        source.usage.output = 42
        source.extra = buildJsonObject { put("metadata", "kept") }
        val copy = BeanCopier().message(source) as AssistantMessage
        source.content.filterIsInstance<TextContent>().single().text = "mutated"
        source.usage.output = 0
        assertEquals("original", text(copy))
        assertEquals(42L, copy.usage.output)
        assertEquals(source.extra, copy.extra)
    }

    @Test fun cancellationListenersRunOnceAndCanBeRemoved() {
        val source = CancellationSource()
        val count = AtomicInteger()
        val removed = source.token.addListener(object : CancellationListener {
            override fun onCancelled() { fail("removed listener must not execute") }
        })
        removed.cancel()
        source.token.addListener(object : CancellationListener {
            override fun onCancelled() { count.incrementAndGet() }
        })
        source.cancel()
        source.cancel()
        assertEquals(1, count.get())
        source.token.addListener(object : CancellationListener {
            override fun onCancelled() { count.incrementAndGet() }
        })
        assertEquals(2, count.get())
        assertTrue(source.token.cancelled)
        assertFailsWith<java.util.concurrent.CancellationException> { source.token.throwIfCancelled() }
    }

    @Test fun beansExposeNoArgConstructionAndJavaPropertyAccessors() {
        val types = listOf(
            AgentConfig::class.java, AgentRequest::class.java, AgentResult::class.java,
            AgentState::class.java, Model::class.java, Usage::class.java,
            UserMessage::class.java, AssistantMessage::class.java, SystemMessage::class.java,
            ToolCall::class.java, ToolDefinition::class.java, StreamOptions::class.java,
            AgentEvent::class.java, ToolContext::class.java, ModelRequest::class.java
        )
        for (type in types) {
            assertNotNull(type.getConstructor().newInstance())
            val properties = Introspector.getBeanInfo(type).propertyDescriptors
            assertTrue(properties.any { it.name != "class" && it.readMethod != null }, "${type.simpleName} 应公开 JavaBean 属性")
        }
        val config = AgentConfig::class.java
        assertNotNull(config.getMethod("setModel", Model::class.java))
        assertNotNull(config.getMethod("getModel"))
        assertNotNull(AgentRequest::class.java.getMethod("setMessages", List::class.java))
    }
}
