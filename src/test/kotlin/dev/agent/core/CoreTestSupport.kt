package dev.agent.core

import dev.agent.core.api.Agent
import dev.agent.core.api.AgentFactory
import dev.agent.core.bean.*
import dev.agent.core.model.ModelClient
import dev.agent.core.tool.AbstractAgentTool
import dev.agent.core.tool.ToolCallback
import dev.agent.core.api.Cancellable
import dev.agent.core.api.NoOpCancellable
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

open class CoreTestSupport {
    fun reply(text: String = "ok", reason: StopReason = StopReason.STOP): AssistantMessage {
        val message = AssistantMessage()
        message.content = listOf(TextContent(text))
        message.stopReason = reason
        return message
    }

    fun calls(vararg names: String, reason: StopReason = StopReason.TOOL_USE): AssistantMessage {
        val message = AssistantMessage()
        val content = ArrayList<ContentBlock>()
        for ((index, name) in names.withIndex()) {
            content.add(call((index + 1).toString(), name))
        }
        message.content = content
        message.stopReason = reason
        return message
    }

    fun call(id: String, name: String, args: JsonElement = JsonObject(emptyMap())): ToolCall {
        val call = ToolCall()
        call.id = id
        call.name = name
        call.arguments = args
        return call
    }

    fun definition(name: String): ToolDefinition {
        val definition = ToolDefinition()
        definition.name = name
        definition.description = "测试 $name"
        definition.parameters = Json.parseToJsonElement("{\"type\":\"object\"}").jsonObject
        return definition
    }

    fun textTool(name: String): AbstractAgentTool {
        return object : AbstractAgentTool(definition(name)) {
            override fun execute(context: ToolContext, callback: ToolCallback): Cancellable {
                callback.onSuccess(ToolResults().text(name))
                return NoOpCancellable()
            }
        }
    }

    fun config(model: ModelClient): AgentConfig {
        val config = AgentConfig()
        config.modelClient = model
        return config
    }

    fun create(model: ModelClient): Agent = AgentFactory().create(config(model))

    fun run(agent: Agent, text: String = "hello"): RecordingAgentCallback {
        val callback = RecordingAgentCallback()
        agent.run(AgentRequest(text), callback)
        callback.await()
        return callback
    }

    fun text(message: AgentMessage): String {
        val blocks = when (message) {
            is UserMessage -> message.content
            is AssistantMessage -> message.content
            is ToolResultMessage -> message.content
            else -> emptyList()
        }
        val text = StringBuilder()
        for (block in blocks) {
            if (block is TextContent) text.append(block.text)
        }
        return text.toString()
    }

    fun waitFor(latch: CountDownLatch) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "同步条件等待超时")
    }
}
