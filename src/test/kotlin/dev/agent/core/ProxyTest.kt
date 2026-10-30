package dev.agent.core

import dev.agent.core.api.AgentFactory
import dev.agent.core.bean.*
import dev.agent.core.protocol.ProtocolCodec
import dev.agent.core.proxy.AuthTokenProvider
import dev.agent.core.proxy.PartialJsonArguments
import dev.agent.core.proxy.ProxyModelClient
import dev.agent.core.runtime.CancellationSource
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProxyTest : CoreTestSupport() {
    private fun event(type: String, vararg values: Pair<String, JsonElement>): JsonObject {
        return buildJsonObject {
            put("type", type)
            for ((key, value) in values) put(key, value)
        }
    }

    private fun line(event: JsonObject): String = "data: $event\n\n"

    private fun done(): JsonObject {
        return event("done", "reason" to JsonPrimitive("stop"), "usage" to buildJsonObject { put("output", 12) })
    }

    private fun client(url: String): ProxyModelClient {
        return ProxyModelClient(url, object : AuthTokenProvider {
            override fun getToken(): String = "proxy-token"
        })
    }

    private fun request(): ModelRequest {
        val request = ModelRequest()
        request.model.id = "test"
        request.model.provider = "test"
        request.model.api = "test-api"
        return request
    }

    private fun collect(url: String, request: ModelRequest = request()): RecordingModelCallback {
        val callback = RecordingModelCallback()
        val model = client(url)
        try {
            model.stream(request, callback)
            callback.await()
            assertNull(callback.failure)
            return callback
        } finally { model.close() }
    }

    @Test fun textUnicodeAndSignatureSurviveStreamingWithIndependentSnapshots() {
        val body = listOf(
            event("start"), event("text_start", "contentIndex" to JsonPrimitive(0)),
            event("text_delta", "contentIndex" to JsonPrimitive(0), "delta" to JsonPrimitive("你好🌏")),
            event("text_delta", "contentIndex" to JsonPrimitive(0), "delta" to JsonPrimitive("！")),
            event("text_end", "contentIndex" to JsonPrimitive(0), "contentSignature" to JsonPrimitive("signed")), done()
        ).joinToString("") { line(it) }
        ProxyTestServer(body).use { server ->
            val callback = collect(server.url)
            assertEquals(listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"), callback.events.map { it.type })
            assertEquals("你好🌏", (callback.events[2].partial!!.content.single() as TextContent).text)
            val final = callback.events.last().finalMessage!!
            assertEquals("你好🌏！", (final.content.single() as TextContent).text)
            assertEquals("signed", (final.content.single() as TextContent).textSignature)
            assertEquals(12L, final.usage.output)
            assertEquals(1, callback.completed.get())
        }
    }

    @Test fun terminalWithoutNewlineIsProcessed() {
        val terminal = buildJsonObject { put("type", "done"); put("reason", "stop"); put("providerThinkingLevel", "high") }
        ProxyTestServer(line(event("start")) + "data: $terminal").use { server ->
            val callback = collect(server.url)
            assertEquals(listOf("start", "done"), callback.events.map { it.type })
            assertEquals("high", callback.events.last().finalMessage!!.providerThinkingLevel)
        }
    }

    @Test fun agentAndProxyCompleteOneSuccessfulRunTogether() {
        val body = line(event("start")) + line(event("text_start", "contentIndex" to JsonPrimitive(0))) +
            line(event("text_delta", "contentIndex" to JsonPrimitive(0), "delta" to JsonPrimitive("integrated"))) + line(done())
        ProxyTestServer(body).use { server ->
            client(server.url).use { model ->
                val agent = AgentFactory().create(config(model))
                try {
                    val callback = run(agent)
                    assertNotNull(callback.result, callback.failure?.toString())
                    assertFalse(agent.getState().isStreaming)
                    assertNull(agent.getState().errorMessage)
                    assertEquals("integrated", text(agent.getState().messages.last()))
                    assertEquals(1, callback.events.count { it.type == AgentEventType.AGENT_END })
                } finally { agent.close() }
            }
        }
    }

    @Test fun terminalStopsReadingWithoutWaitingForServerEof() {
        ProxyTestServer(line(event("start")) + line(done()), keepOpen = true).use { server ->
            val callback = collect(server.url)
            assertEquals(listOf("start", "done"), callback.events.map { it.type })
            assertEquals(1, callback.completed.get())
        }
    }

    @Test fun downstreamCallbackExceptionIsReportedWithoutSyntheticProviderError() {
        val failure = IllegalStateException("consumer failure")
        ProxyTestServer(line(event("start")) + line(done())).use { server ->
            val callback = object : RecordingModelCallback() {
                override fun onEvent(event: AssistantMessageEvent) { throw failure }
            }
            client(server.url).use { model ->
                model.stream(request(), callback)
                callback.await()
                assertSame(failure, callback.failure)
                assertTrue(callback.events.isEmpty())
                assertEquals(0, callback.completed.get())
            }
        }
    }

    @Test fun missingTerminalBecomesExplicitError() {
        ProxyTestServer(line(event("start"))).use { server ->
            val events = collect(server.url).events
            assertEquals(listOf("start", "error"), events.map { it.type })
            assertContains(events.last().finalMessage!!.errorMessage!!, "before the response completed")
        }
    }

    @Test fun httpErrorAndInvalidJsonProduceFinalErrors() {
        ProxyTestServer("{\"error\":\"denied\"}", 403).use { server ->
            val events = collect(server.url).events
            assertEquals("Proxy error: denied", events.single().finalMessage!!.errorMessage)
        }
        ProxyTestServer("data: invalid-json\n").use { server ->
            assertEquals(StopReason.ERROR, collect(server.url).events.last().finalMessage!!.stopReason)
        }
    }

    @Test fun toolCallEndKeepsMetadataAndCanonicalFinalArguments() {
        val call = Json.parseToJsonElement("""{"type":"toolCall","id":"call1","name":"lookup","namespace":"dynamic_tools","thoughtSignature":"sig","arguments":{"value":"final"}}""").jsonObject
        val body = listOf(
            event("start"), event("toolcall_start", "contentIndex" to JsonPrimitive(0), "id" to JsonPrimitive("call1"), "toolName" to JsonPrimitive("lookup")),
            event("toolcall_delta", "contentIndex" to JsonPrimitive(0), "delta" to JsonPrimitive("{\"value\":\"preview")),
            event("toolcall_end", "contentIndex" to JsonPrimitive(0), "toolCall" to call), done()
        ).joinToString("") { line(it) }
        ProxyTestServer(body).use { server ->
            val events = collect(server.url).events
            val preview = events.first { it.type == "toolcall_delta" }.partial!!.content.single() as ToolCall
            assertEquals("preview", preview.arguments.jsonObject["value"]!!.jsonPrimitive.content)
            val final = events.last().finalMessage!!.content.single() as ToolCall
            assertEquals("final", final.arguments.jsonObject["value"]!!.jsonPrimitive.content)
            assertEquals("dynamic_tools", final.extra["namespace"]!!.jsonPrimitive.content)
            assertEquals("sig", final.thoughtSignature)
            assertEquals(call, ProtocolCodec().content(final))
        }
    }

    @Test fun thinkingSignatureAndTerminalErrorMetadataSurvive() {
        val body = listOf(
            event("start"), event("thinking_start", "contentIndex" to JsonPrimitive(0)),
            event("thinking_delta", "contentIndex" to JsonPrimitive(0), "delta" to JsonPrimitive("reason")),
            event("thinking_end", "contentIndex" to JsonPrimitive(0), "contentSignature" to JsonPrimitive("signed-thinking")),
            event("error", "reason" to JsonPrimitive("error"), "errorMessage" to JsonPrimitive("provider failed"), "providerThinkingLevel" to JsonPrimitive("max"))
        ).joinToString("") { line(it) }
        ProxyTestServer(body).use { server ->
            val final = collect(server.url).events.last().finalMessage!!
            val thinking = final.content.single() as ThinkingContent
            assertEquals("reason", thinking.thinking)
            assertEquals("signed-thinking", thinking.thinkingSignature)
            assertEquals("provider failed", final.errorMessage)
            assertEquals("max", final.providerThinkingLevel)
        }
    }

    @Test fun requestUsesTranscriptAndExcludesCredentialsAndCallbacks() {
        ProxyTestServer(line(done())).use { server ->
            val request = request()
            val system = SystemMessage()
            system.content = "system"
            system.toolsRemoved = listOf("old")
            request.context.messages = listOf(system, UserMessage("hello"))
            request.options.apiKey = "provider-secret"
            request.options.sessionId = "session"
            request.options.temperature = 0.5
            request.options.metadata = buildJsonObject { put("tag", "test") }
            collect(server.url, request)
            val body = Json.parseToJsonElement(server.request.get()).jsonObject
            assertEquals("Bearer proxy-token", server.auth.get())
            assertFalse(server.request.get().contains("provider-secret"))
            assertFalse(server.request.get().contains("proxy-token"))
            assertEquals("session", body["options"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
            val wireSystem = body["context"]!!.jsonObject["messages"]!!.jsonArray.first().jsonObject
            assertEquals("old", wireSystem["toolsRemoved"]!!.jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content)
            assertFalse(body["context"]!!.jsonObject.containsKey("tools"))
        }
    }

    @Test fun cancellationBeforeHttpReportsCancellation() {
        val source = CancellationSource()
        source.cancel()
        val request = request()
        request.options.signal = source.token
        client("http://127.0.0.1:1").use { model ->
            val callback = RecordingModelCallback()
            model.stream(request, callback)
            callback.await()
            assertIs<CancellationException>(callback.failure)
            assertEquals(0, callback.completed.get())
        }
    }

    @Test fun cancellationTokenClosesStalledResponseBody() {
        ProxyTestServer(line(event("start")), keepOpen = true).use { server ->
            val source = CancellationSource()
            val request = request()
            request.options.signal = source.token
            val callback = RecordingModelCallback()
            client(server.url).use { model ->
                model.stream(request, callback)
                waitFor(server.started)
                source.cancel()
                callback.await()
                assertIs<CancellationException>(callback.failure)
                assertEquals(0, callback.completed.get())
            }
        }
    }

    @Test fun returnedHandleCancelsStalledResponseWithoutToken() {
        ProxyTestServer(line(event("start")), keepOpen = true).use { server ->
            val callback = RecordingModelCallback()
            client(server.url).use { model ->
                val handle = model.stream(request(), callback)
                waitFor(server.started)
                handle.cancel()
                handle.cancel()
                callback.await()
                assertIs<CancellationException>(callback.failure)
                assertEquals(0, callback.completed.get())
            }
        }
    }

    @Test fun proxyDoesNotCloseInjectedExecutor() {
        val executor = Executors.newSingleThreadExecutor()
        val model = ProxyModelClient("http://127.0.0.1:1", object : AuthTokenProvider {
            override fun getToken(): String = "token"
        }, executor)
        try {
            model.close()
            model.close()
            assertFalse(executor.isShutdown)
            assertEquals("usable", executor.submit<String> { "usable" }.get(5, TimeUnit.SECONDS))
        } finally { model.close(); executor.shutdownNow() }
    }

    @Test fun partialArgumentsHandleNestedContainersAndSplitEscapes() {
        val parser = PartialJsonArguments()
        assertEquals("hel", parser.append("{\"a\":[{\"text\":\"hel").jsonObject["a"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        parser.append("lo\\")
        parser.append("n\\u4")
        val final = parser.append("F60\"}],\"ok\":true,\"n\":1.25}")
        assertEquals(Json.parseToJsonElement("{\"a\":[{\"text\":\"hello\\n你\"}],\"ok\":true,\"n\":1.25}"), final)
    }

    @Test fun largePartialJsonGrowsWithoutReparsingAccumulatedInput() {
        val content = "line \"quoted\" \\ end\n".repeat(20_000)
        val encoded = buildJsonObject { put("path", "a.ts"); put("content", content) }.toString()
        val parser = PartialJsonArguments()
        var shown = 0
        var last: JsonElement = JsonNull
        for ((index, chunk) in encoded.chunked(32).withIndex()) {
            last = parser.append(chunk)
            val partial = last.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            assertTrue(partial.length >= shown)
            if (index % 500 == 0) assertTrue(content.startsWith(partial))
            shown = partial.length
        }
        assertEquals(content.length, shown)
        assertEquals(content, last.jsonObject["content"]!!.jsonPrimitive.content)
    }
}
