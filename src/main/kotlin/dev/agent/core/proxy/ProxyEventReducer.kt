package dev.agent.core.proxy

import dev.agent.core.bean.AssistantMessage
import dev.agent.core.bean.AssistantMessageEvent
import dev.agent.core.bean.BeanCopier
import dev.agent.core.bean.ContentBlock
import dev.agent.core.bean.Model
import dev.agent.core.bean.StopReason
import dev.agent.core.bean.TextContent
import dev.agent.core.bean.ThinkingContent
import dev.agent.core.bean.ToolCall
import dev.agent.core.protocol.ProtocolCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 按 contentIndex 归并代理增量，并为每次回调生成独立快照。
 *
 * 一个实例只处理一次模型响应；调用方须串行调用。参数预览保留增量状态，
 * toolcall_end 的完整参数和扩展字段覆盖此前预览。
 */
internal class ProxyEventReducer(model: Model) {
    private val copier = BeanCopier()
    private val codec = ProtocolCodec()
    private val partial = AssistantMessage()
    private val arguments: MutableMap<Int, PartialJsonArguments> = HashMap()

    init {
        partial.api = model.api
        partial.provider = model.provider
        partial.model = model.id
        partial.stopReason = StopReason.PENDING
    }

    fun failure(message: String, reason: StopReason): AssistantMessageEvent {
        partial.stopReason = reason
        partial.errorMessage = message
        val event = AssistantMessageEvent()
        event.type = "error"
        event.message = copier.message(partial) as AssistantMessage
        event.reason = reason
        return event
    }

    fun process(value: JsonObject): AssistantMessageEvent? {
        val type = value.getValue("type").jsonPrimitive.content
        val index = value["contentIndex"]?.jsonPrimitive?.int ?: 0
        val delta = value["delta"]?.jsonPrimitive?.content ?: ""
        when (type) {
            "start" -> Unit
            "text_start" -> set(index, TextContent())
            "thinking_start" -> set(index, ThinkingContent())
            "text_delta" -> {
                val block = copyText(index, "text_delta")
                block.text += delta
                set(index, block)
            }
            "thinking_delta" -> {
                val block = copyThinking(index, "thinking_delta")
                block.thinking += delta
                set(index, block)
            }
            "text_end" -> {
                val block = copyText(index, "text_end")
                block.textSignature = value["contentSignature"]?.jsonPrimitive?.contentOrNull
                set(index, block)
            }
            "thinking_end" -> {
                val block = copyThinking(index, "thinking_end")
                block.thinkingSignature = value["contentSignature"]?.jsonPrimitive?.contentOrNull
                set(index, block)
            }
            "toolcall_start" -> {
                val block = ToolCall()
                block.id = value.getValue("id").jsonPrimitive.content
                block.name = value.getValue("toolName").jsonPrimitive.content
                set(index, block)
                arguments[index] = PartialJsonArguments()
            }
            "toolcall_delta" -> {
                val current = partial.content.getOrNull(index)
                check(current is ToolCall) { "Received toolcall_delta for non-toolCall content" }
                val parser = checkNotNull(arguments[index]) { "Received toolcall_delta without start" }
                val block = copier.content(current) as ToolCall
                block.arguments = parser.append(delta)
                set(index, block)
            }
            "toolcall_end" -> {
                if (partial.content.getOrNull(index) !is ToolCall) {
                    return null
                }
                set(index, codec.decodeToolCall(value.getValue("toolCall").jsonObject))
                arguments.remove(index)
            }
            "done", "error" -> return terminal(type, value)
            else -> return null
        }

        val event = AssistantMessageEvent()
        event.type = type
        event.partial = copier.message(partial) as AssistantMessage
        if (type != "start") {
            event.contentIndex = index
        }
        if (type.endsWith("_delta")) {
            event.delta = delta
        }
        val block = partial.content.getOrNull(index)
        if (type == "text_end") {
            event.content = (block as TextContent).text
        } else if (type == "thinking_end") {
            event.content = (block as ThinkingContent).thinking
        } else if (type == "toolcall_end") {
            event.toolCall = copier.content(block as ToolCall) as ToolCall
        }
        return event
    }

    private fun set(index: Int, block: ContentBlock) {
        require(index >= 0 && index <= partial.content.size) { "Invalid content index $index" }
        val content = ArrayList(partial.content)
        if (index == content.size) {
            content.add(block)
        } else {
            content[index] = block
        }
        partial.content = content
    }

    private fun copyText(index: Int, type: String): TextContent {
        val block = partial.content.getOrNull(index)
        check(block is TextContent) { "Received $type for non-text content" }
        return copier.content(block) as TextContent
    }

    private fun copyThinking(index: Int, type: String): ThinkingContent {
        val block = partial.content.getOrNull(index)
        check(block is ThinkingContent) { "Received $type for non-thinking content" }
        return copier.content(block) as ThinkingContent
    }

    private fun terminal(type: String, value: JsonObject): AssistantMessageEvent {
        val reasonValue = value["reason"]?.jsonPrimitive?.content
            ?: if (type == "error") "error" else "stop"
        var reason: StopReason? = null
        for (candidate in StopReason.values()) {
            if (candidate.wire == reasonValue) {
                reason = candidate
                break
            }
        }
        require(reason != null) { "Unknown stop reason $reasonValue" }
        partial.stopReason = reason
        partial.usage = codec.decodeUsage(value["usage"])
        partial.errorMessage = value["errorMessage"]?.jsonPrimitive?.contentOrNull
        partial.providerThinkingLevel = value["providerThinkingLevel"]?.jsonPrimitive?.contentOrNull
        val event = AssistantMessageEvent()
        event.type = type
        event.message = copier.message(partial) as AssistantMessage
        event.reason = reason
        return event
    }
}
