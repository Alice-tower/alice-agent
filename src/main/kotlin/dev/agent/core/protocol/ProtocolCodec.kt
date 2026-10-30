package dev.agent.core.protocol

import dev.agent.core.bean.*
import kotlinx.serialization.json.*

/**
 * 模型代理的 JSON 协议编解码边界，保留原有字段、签名及厂商扩展。
 * 本地执行策略、取消令牌、凭据和回调不会进入请求 JSON。
 */
class ProtocolCodec {
    val json: Json = Json { ignoreUnknownKeys = true }

    /** 编码模型能力，显式字段优先于 extra 中的同名字段。 */
    fun model(value: Model): JsonObject {
        return buildJsonObject {
            for ((key, field) in value.extra) put(key, field)
            put("id", value.id)
            put("name", value.name)
            put("api", value.api)
            put("provider", value.provider)
            put("baseUrl", value.baseUrl)
            put("reasoning", value.reasoning)
            val input = ArrayList<JsonElement>()
            for (kind in value.input) input.add(JsonPrimitive(kind))
            put("input", JsonArray(input))
            put("cost", value.cost)
            put("contextWindow", value.contextWindow)
            put("maxTokens", value.maxTokens)
        }
    }

    /** 解码模型身份及未知扩展字段。 */
    fun decodeModel(value: JsonObject): Model {
        val result = Model()
        result.id = string(value, "id", "unknown")
        result.name = string(value, "name", result.id)
        result.api = string(value, "api", "unknown")
        result.provider = string(value, "provider", "unknown")
        result.baseUrl = string(value, "baseUrl", "")
        result.reasoning = boolean(value, "reasoning", false)
        result.input = strings(value["input"])
        result.cost = objectValue(value["cost"])
        result.contextWindow = integer(value, "contextWindow", 0)
        result.maxTokens = integer(value, "maxTokens", 0)
        val known = setOf("id", "name", "api", "provider", "baseUrl", "reasoning", "input", "cost", "contextWindow", "maxTokens")
        result.extra = extra(value, known)
        return result
    }

    /** 编码四种内置消息；自定义消息必须先由宿主 Hook 转换。 */
    fun message(value: AgentMessage): JsonObject {
        return buildJsonObject {
            put("role", value.role)
            put("timestamp", value.timestamp)
            when (value) {
                is SystemMessage -> {
                    val blocks = value.contentBlocks
                    if (blocks == null) put("content", value.content)
                    else {
                        val encoded = ArrayList<JsonElement>()
                        for (block in blocks) encoded.add(content(block))
                        put("content", JsonArray(encoded))
                    }
                    if (value.sections.isNotEmpty()) {
                        put("sections", buildJsonObject {
                            for ((key, field) in value.sections) put(key, field?.let { JsonPrimitive(it) } ?: JsonNull)
                        })
                    }
                    if (value.toolsAdded.isNotEmpty()) {
                        val encoded = ArrayList<JsonElement>()
                        for (tool in value.toolsAdded) encoded.add(tool(tool))
                        put("toolsAdded", JsonArray(encoded))
                    }
                    if (value.toolsRemoved.isNotEmpty()) {
                        val encoded = ArrayList<JsonElement>()
                        for (name in value.toolsRemoved) encoded.add(buildJsonObject { put("name", name) })
                        put("toolsRemoved", JsonArray(encoded))
                    }
                }
                is UserMessage -> put("content", contents(value.content))
                is AssistantMessage -> {
                    for ((key, field) in value.extra) {
                        if (key != "role" && key != "timestamp") put(key, field)
                    }
                    put("content", contents(value.content))
                    put("api", value.api)
                    put("provider", value.provider)
                    put("model", value.model)
                    put("usage", usage(value.usage))
                    put("stopReason", value.stopReason.wire)
                    put("thinkingLevel", value.thinkingLevel.wire)
                    if (value.errorMessage != null) put("errorMessage", value.errorMessage)
                    if (value.providerThinkingLevel != null) put("providerThinkingLevel", value.providerThinkingLevel)
                }
                is ToolResultMessage -> {
                    put("toolCallId", value.toolCallId)
                    put("toolName", value.toolName)
                    put("content", contents(value.content))
                    put("details", value.details)
                    put("isError", value.isError)
                    val usage = value.usage
                    if (usage != null) put("usage", usage(usage))
                    if (value.durationMs != null) put("durationMs", value.durationMs)
                }
                else -> throw IllegalArgumentException("Custom message ${value.role} must be converted before protocol serialization")
            }
        }
    }

    /** 解码历史消息；系统文本块的签名与聚合文本同时保留。 */
    fun decodeMessage(value: JsonObject): AgentMessage {
        val role = string(value, "role", "")
        val timestamp = (value["timestamp"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
        when (role) {
            "system" -> {
                val result = SystemMessage()
                result.timestamp = timestamp
                val encodedContent = value["content"]
                if (encodedContent is JsonArray) {
                    val blocks = ArrayList<TextContent>()
                    val text = ArrayList<String>()
                    for (encoded in encodedContent) {
                        val block = decodeContent(encoded.jsonObject)
                        require(block is TextContent) { "System content must contain only text blocks" }
                        blocks.add(block)
                        text.add(block.text)
                    }
                    result.contentBlocks = blocks
                    result.content = text.joinToString("\n")
                } else result.content = (encodedContent as? JsonPrimitive)?.contentOrNull ?: ""
                val sections = LinkedHashMap<String, String?>()
                val fields = value["sections"] as? JsonObject
                if (fields != null) for ((key, field) in fields) sections[key] = (field as? JsonPrimitive)?.contentOrNull
                result.sections = sections
                val tools = ArrayList<ToolDeclaration>()
                val declarations = value["toolsAdded"] as? JsonArray
                if (declarations != null) for (tool in declarations) tools.add(decodeToolDeclaration(tool.jsonObject))
                result.toolsAdded = tools
                val removed = ArrayList<String>()
                val removals = value["toolsRemoved"] as? JsonArray
                if (removals != null) {
                    for (removal in removals) {
                        if (removal is JsonObject) removed.add(string(removal, "name", ""))
                        else removed.add(removal.jsonPrimitive.content)
                    }
                }
                result.toolsRemoved = removed
                return result
            }
            "user" -> {
                val result = UserMessage()
                result.timestamp = timestamp
                result.content = decodeContents(value["content"])
                return result
            }
            "assistant" -> {
                val result = AssistantMessage()
                result.timestamp = timestamp
                result.content = decodeContents(value["content"])
                result.api = string(value, "api", "unknown")
                result.provider = string(value, "provider", "unknown")
                result.model = string(value, "model", "unknown")
                result.usage = decodeUsage(value["usage"])
                result.stopReason = stopReason(string(value, "stopReason", "stop"))
                result.thinkingLevel = thinkingLevel(string(value, "thinkingLevel", "off"))
                result.errorMessage = nullableString(value, "errorMessage")
                result.providerThinkingLevel = nullableString(value, "providerThinkingLevel")
                val known = setOf("role", "timestamp", "content", "api", "provider", "model", "usage", "stopReason", "thinkingLevel", "errorMessage", "providerThinkingLevel")
                result.extra = extra(value, known)
                return result
            }
            "toolResult" -> {
                val result = ToolResultMessage()
                result.timestamp = timestamp
                result.toolCallId = string(value, "toolCallId", "")
                result.toolName = string(value, "toolName", "")
                result.content = decodeContents(value["content"])
                result.details = value["details"] ?: JsonObject(emptyMap())
                result.isError = boolean(value, "isError", false)
                if (value["usage"] != null && value["usage"] != JsonNull) result.usage = decodeUsage(value["usage"])
                result.durationMs = (value["durationMs"] as? JsonPrimitive)?.longOrNull
                return result
            }
            else -> throw IllegalArgumentException("Unsupported message role: $role")
        }
    }

    /** 编码消息内容，保留签名、参数解析错误及扩展字段。 */
    fun content(value: ContentBlock): JsonObject {
        return buildJsonObject {
            when (value) {
                is TextContent -> {
                    put("type", "text")
                    put("text", value.text)
                    if (value.textSignature != null) put("textSignature", value.textSignature)
                }
                is ImageContent -> {
                    put("type", "image")
                    put("data", value.data)
                    put("mimeType", value.mimeType)
                }
                is ThinkingContent -> {
                    put("type", "thinking")
                    put("thinking", value.thinking)
                    if (value.thinkingSignature != null) put("thinkingSignature", value.thinkingSignature)
                    if (value.redacted) put("redacted", true)
                }
                is ToolCall -> {
                    for ((key, field) in value.extra) put(key, field)
                    put("type", "toolCall")
                    put("id", value.id)
                    put("name", value.name)
                    put("arguments", value.arguments)
                    if (value.thoughtSignature != null) put("thoughtSignature", value.thoughtSignature)
                    if (value.argumentsError != null) put("argumentsError", value.argumentsError)
                }
                else -> throw IllegalArgumentException("Unsupported content type: ${value.javaClass.name}")
            }
        }
    }

    /** 解码内置文本、图片、推理及工具调用内容。 */
    fun decodeContent(value: JsonObject): ContentBlock {
        return when (val type = string(value, "type", "")) {
            "text" -> {
                val result = TextContent(string(value, "text", ""))
                result.textSignature = nullableString(value, "textSignature")
                result
            }
            "image" -> ImageContent(string(value, "data", ""), string(value, "mimeType", ""))
            "thinking" -> {
                val result = ThinkingContent(string(value, "thinking", ""))
                result.thinkingSignature = nullableString(value, "thinkingSignature")
                result.redacted = boolean(value, "redacted", false)
                result
            }
            "toolCall" -> decodeToolCall(value)
            else -> throw IllegalArgumentException("Unsupported content type: $type")
        }
    }

    fun tool(value: ToolDeclaration): JsonObject {
        return buildJsonObject {
            put("name", value.name)
            put("description", value.description)
            put("parameters", value.parameters)
            value.constrainedSampling?.let { put("constrainedSampling", it) }
        }
    }

    fun decodeToolDeclaration(value: JsonObject): ToolDeclaration {
        val result = ToolDeclaration()
        result.name = string(value, "name", "")
        result.description = string(value, "description", "")
        result.parameters = objectValue(value["parameters"])
        result.constrainedSampling = value["constrainedSampling"]
        return result
    }

    /** 网络上下文仅发送消息历史；工具声明位于系统消息中。 */
    fun context(value: AgentContext): JsonObject {
        return buildJsonObject {
            val messages = ArrayList<JsonElement>()
            for (entry in value.messages) messages.add(message(entry))
            put("messages", JsonArray(messages))
        }
    }

    fun decodeContext(value: JsonObject): AgentContext {
        val result = AgentContext()
        val messages = ArrayList<AgentMessage>()
        val encoded = value["messages"] as? JsonArray
        if (encoded != null) for (entry in encoded) messages.add(decodeMessage(entry.jsonObject))
        result.messages = messages
        return result
    }

    fun usage(value: Usage): JsonObject {
        return buildJsonObject {
            put("input", value.input)
            put("output", value.output)
            put("cacheRead", value.cacheRead)
            put("cacheWrite", value.cacheWrite)
            put("totalTokens", value.totalTokens)
            put("cost", value.cost)
        }
    }

    /** 缺失用量按零处理；费用保持 Provider 原始记录。 */
    fun decodeUsage(value: JsonElement?): Usage {
        val result = Usage()
        val fields = value as? JsonObject ?: return result
        result.input = long(fields, "input", 0)
        result.output = long(fields, "output", 0)
        result.cacheRead = long(fields, "cacheRead", 0)
        result.cacheWrite = long(fields, "cacheWrite", 0)
        result.totalTokens = long(fields, "totalTokens", 0)
        result.cost = objectValue(fields["cost"])
        return result
    }

    /** 工具结束事件中的完整参数为权威值，不能以增量预览替代。 */
    fun decodeToolCall(value: JsonObject): ToolCall {
        val result = ToolCall(value.getValue("id").jsonPrimitive.content, value.getValue("name").jsonPrimitive.content)
        result.arguments = value["arguments"] ?: JsonObject(emptyMap())
        result.thoughtSignature = nullableString(value, "thoughtSignature")
        result.argumentsError = nullableString(value, "argumentsError")
        result.extra = extra(value, setOf("type", "id", "name", "arguments", "thoughtSignature", "argumentsError"))
        return result
    }

    /** 白名单编码请求选项，排除本地凭据、取消令牌及回调。 */
    fun options(value: StreamOptions): JsonObject {
        return buildJsonObject {
            if (value.temperature != null) put("temperature", value.temperature)
            value.samplingParams?.let { put("samplingParams", it) }
            if (value.maxTokens != null) put("maxTokens", value.maxTokens)
            if (value.reasoning != null) put("reasoning", value.reasoning!!.wire)
            if (value.cacheRetention != null) put("cacheRetention", value.cacheRetention)
            if (value.sessionId != null) put("sessionId", value.sessionId)
            if (value.headers.isNotEmpty()) {
                put("headers", buildJsonObject {
                    for ((key, field) in value.headers) put(key, field)
                })
            }
            value.metadata?.let { put("metadata", it) }
            put("transport", value.transport)
            val budgets = value.thinkingBudgets
            if (budgets != null) {
                put("thinkingBudgets", buildJsonObject {
                    for ((key, field) in budgets) put(key, field)
                })
            }
            if (value.maxRetryDelayMs != null) put("maxRetryDelayMs", value.maxRetryDelayMs)
        }
    }

    private fun contents(values: List<ContentBlock>): JsonArray {
        val result = ArrayList<JsonElement>()
        for (value in values) result.add(content(value))
        return JsonArray(result)
    }

    private fun decodeContents(value: JsonElement?): List<ContentBlock> {
        val result = ArrayList<ContentBlock>()
        if (value is JsonArray) for (block in value) result.add(decodeContent(block.jsonObject))
        return result
    }

    private fun string(value: JsonObject, key: String, defaultValue: String): String {
        return (value[key] as? JsonPrimitive)?.contentOrNull ?: defaultValue
    }

    private fun nullableString(value: JsonObject, key: String): String? = (value[key] as? JsonPrimitive)?.contentOrNull

    private fun boolean(value: JsonObject, key: String, defaultValue: Boolean): Boolean {
        return (value[key] as? JsonPrimitive)?.booleanOrNull ?: defaultValue
    }

    private fun integer(value: JsonObject, key: String, defaultValue: Int): Int {
        return (value[key] as? JsonPrimitive)?.intOrNull ?: defaultValue
    }

    private fun long(value: JsonObject, key: String, defaultValue: Long): Long {
        return (value[key] as? JsonPrimitive)?.longOrNull ?: defaultValue
    }

    private fun objectValue(value: JsonElement?): JsonObject = value as? JsonObject ?: JsonObject(emptyMap())

    private fun strings(value: JsonElement?): List<String> {
        val result = ArrayList<String>()
        if (value is JsonArray) for (entry in value) result.add(entry.jsonPrimitive.content)
        return result
    }

    private fun extra(value: JsonObject, known: Set<String>): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, field) in value) if (!known.contains(key)) result[key] = field
        return JsonObject(result)
    }

    private fun stopReason(wire: String): StopReason {
        for (reason in StopReason.values()) if (reason.wire == wire) return reason
        throw IllegalArgumentException("Unknown stop reason $wire")
    }

    private fun thinkingLevel(wire: String): ThinkingLevel {
        for (level in ThinkingLevel.values()) if (level.wire == wire) return level
        throw IllegalArgumentException("Unknown thinking level $wire")
    }
}
