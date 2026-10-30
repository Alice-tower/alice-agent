package dev.agent.core.bean

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 明确复制数据对象与集合，隔离宿主、回调和运行时持有的可变数据。
 * 工具、模型客户端、Hook、线程池和取消令牌属于策略或资源，保留引用。
 * 自定义消息和内容块由其 snapshot 方法负责复制。
 */
class BeanCopier {
    fun json(value: JsonElement): JsonElement = Json.parseToJsonElement(value.toString())

    fun model(value: Model): Model {
        val result = Model()
        result.id = value.id
        result.provider = value.provider
        result.api = value.api
        result.name = value.name
        result.baseUrl = value.baseUrl
        result.reasoning = value.reasoning
        result.input = ArrayList(value.input)
        result.cost = json(value.cost) as JsonObject
        result.contextWindow = value.contextWindow
        result.maxTokens = value.maxTokens
        result.extra = json(value.extra) as JsonObject
        return result
    }

    fun usage(value: Usage): Usage {
        val result = Usage()
        result.input = value.input
        result.output = value.output
        result.cacheRead = value.cacheRead
        result.cacheWrite = value.cacheWrite
        result.totalTokens = value.totalTokens
        result.cost = json(value.cost) as JsonObject
        return result
    }

    fun textContent(value: TextContent): TextContent {
        val result = TextContent(value.text)
        result.textSignature = value.textSignature
        return result
    }

    fun imageContent(value: ImageContent): ImageContent = ImageContent(value.data, value.mimeType)

    fun thinkingContent(value: ThinkingContent): ThinkingContent {
        val result = ThinkingContent(value.thinking)
        result.thinkingSignature = value.thinkingSignature
        result.redacted = value.redacted
        return result
    }

    fun toolCall(value: ToolCall): ToolCall {
        val result = ToolCall(value.id, value.name)
        result.arguments = json(value.arguments)
        result.thoughtSignature = value.thoughtSignature
        result.argumentsError = value.argumentsError
        result.extra = json(value.extra) as JsonObject
        return result
    }

    fun content(value: ContentBlock): ContentBlock {
        return when (value) {
            is TextContent -> textContent(value)
            is ImageContent -> imageContent(value)
            is ThinkingContent -> thinkingContent(value)
            is ToolCall -> toolCall(value)
            else -> value.snapshot()
        }
    }

    fun contents(values: List<ContentBlock>): List<ContentBlock> {
        val result = ArrayList<ContentBlock>()
        for (value in values) result.add(content(value))
        return result
    }

    fun systemMessage(value: SystemMessage): SystemMessage {
        val result = SystemMessage(value.content)
        result.timestamp = value.timestamp
        result.sections = LinkedHashMap(value.sections)
        val declarations = ArrayList<ToolDeclaration>()
        for (definition in value.toolsAdded) declarations.add(toolDeclaration(definition))
        result.toolsAdded = declarations
        result.toolsRemoved = ArrayList(value.toolsRemoved)
        val blocks = value.contentBlocks
        if (blocks != null) {
            val copied = ArrayList<TextContent>()
            for (block in blocks) copied.add(textContent(block))
            result.contentBlocks = copied
        }
        return result
    }

    fun userMessage(value: UserMessage): UserMessage {
        val result = UserMessage()
        result.timestamp = value.timestamp
        result.content = contents(value.content)
        return result
    }

    fun assistantMessage(value: AssistantMessage): AssistantMessage {
        val result = AssistantMessage()
        result.timestamp = value.timestamp
        result.content = contents(value.content)
        result.api = value.api
        result.provider = value.provider
        result.model = value.model
        result.usage = usage(value.usage)
        result.stopReason = value.stopReason
        result.errorMessage = value.errorMessage
        result.thinkingLevel = value.thinkingLevel
        result.providerThinkingLevel = value.providerThinkingLevel
        result.extra = json(value.extra) as JsonObject
        return result
    }

    fun toolResultMessage(value: ToolResultMessage): ToolResultMessage {
        val result = ToolResultMessage()
        result.timestamp = value.timestamp
        result.toolCallId = value.toolCallId
        result.toolName = value.toolName
        result.content = contents(value.content)
        result.details = json(value.details)
        result.isError = value.isError
        result.usage = value.usage?.let { usage(it) }
        result.durationMs = value.durationMs
        return result
    }

    fun message(value: AgentMessage): AgentMessage {
        return when (value) {
            is SystemMessage -> systemMessage(value)
            is UserMessage -> userMessage(value)
            is AssistantMessage -> assistantMessage(value)
            is ToolResultMessage -> toolResultMessage(value)
            else -> value.snapshot()
        }
    }

    fun messages(values: List<AgentMessage>): List<AgentMessage> {
        val result = ArrayList<AgentMessage>()
        for (value in values) result.add(message(value))
        return result
    }

    fun toolDeclaration(value: ToolDeclaration): ToolDeclaration {
        val result = ToolDeclaration()
        result.name = value.name
        result.description = value.description
        result.parameters = json(value.parameters) as JsonObject
        result.constrainedSampling = value.constrainedSampling?.let { json(it) }
        return result
    }

    fun toolDefinition(value: ToolDefinition): ToolDefinition {
        val result = ToolDefinition()
        result.name = value.name
        result.description = value.description
        result.parameters = json(value.parameters) as JsonObject
        result.label = value.label
        result.outputSchema = value.outputSchema?.let { json(it) as JsonObject }
        result.constrainedSampling = value.constrainedSampling?.let { json(it) }
        result.executionMode = value.executionMode
        result.replay = value.replay
        return result
    }

    fun toolResult(value: AgentToolResult): AgentToolResult {
        val result = AgentToolResult()
        result.content = contents(value.content)
        result.details = json(value.details)
        result.structuredContent = value.structuredContent?.let { json(it) }
        result.usage = value.usage?.let { usage(it) }
        result.isError = value.isError
        result.terminate = value.terminate
        return result
    }

    fun context(value: AgentContext): AgentContext {
        val result = AgentContext()
        result.messages = messages(value.messages)
        result.tools = ArrayList(value.tools)
        return result
    }

    fun streamOptions(value: StreamOptions): StreamOptions {
        val result = StreamOptions()
        result.signal = value.signal
        result.apiKey = value.apiKey
        result.reasoning = value.reasoning
        result.temperature = value.temperature
        result.maxTokens = value.maxTokens
        result.samplingParams = value.samplingParams?.let { json(it) as JsonObject }
        result.cacheRetention = value.cacheRetention
        result.sessionId = value.sessionId
        result.headers = LinkedHashMap(value.headers)
        result.metadata = value.metadata?.let { json(it) as JsonObject }
        result.transport = value.transport
        result.thinkingBudgets = value.thinkingBudgets?.let { LinkedHashMap(it) }
        result.maxRetryDelayMs = value.maxRetryDelayMs
        result.identity = value.identity?.let { json(it) as JsonObject }
        result.onPayload = value.onPayload
        result.onResponse = value.onResponse
        result.onProviderStreamEvent = value.onProviderStreamEvent
        return result
    }

    fun config(value: AgentConfig): AgentConfig {
        val result = AgentConfig()
        result.modelClient = value.modelClient
        result.model = model(value.model)
        result.thinkingLevel = value.thinkingLevel
        result.systemPrompt = value.systemPrompt
        result.tools = ArrayList(value.tools)
        result.messages = messages(value.messages)
        result.streamOptions = streamOptions(value.streamOptions)
        result.hooks = value.hooks
        result.toolExecution = value.toolExecution
        result.steeringMode = value.steeringMode
        result.followUpMode = value.followUpMode
        result.workerExecutor = value.workerExecutor
        return result
    }

    fun assistantEvent(value: AssistantMessageEvent): AssistantMessageEvent {
        val result = AssistantMessageEvent()
        result.type = value.type
        result.partial = value.partial?.let { assistantMessage(it) }
        result.message = value.message?.let { assistantMessage(it) }
        result.contentIndex = value.contentIndex
        result.delta = value.delta
        result.content = value.content
        result.toolCall = value.toolCall?.let { toolCall(it) }
        result.reason = value.reason
        return result
    }

    fun event(value: AgentEvent): AgentEvent {
        val result = AgentEvent()
        result.type = value.type
        result.message = value.message?.let { message(it) }
        result.assistantMessageEvent = value.assistantMessageEvent?.let { assistantEvent(it) }
        result.messages = messages(value.messages)
        val tools = ArrayList<ToolResultMessage>()
        for (tool in value.toolResults) tools.add(toolResultMessage(tool))
        result.toolResults = tools
        result.toolCallId = value.toolCallId
        result.toolName = value.toolName
        result.args = json(value.args)
        result.result = value.result?.let { toolResult(it) }
        result.partialResult = value.partialResult?.let { toolResult(it) }
        result.isError = value.isError
        result.durationMs = value.durationMs
        return result
    }

    fun request(value: AgentRequest): AgentRequest {
        val result = AgentRequest()
        result.messages = messages(value.messages)
        return result
    }

    fun modelRequest(value: ModelRequest): ModelRequest {
        val result = ModelRequest()
        result.model = model(value.model)
        result.context = context(value.context)
        result.options = streamOptions(value.options)
        return result
    }

    fun agentState(value: AgentState): AgentState {
        val result = AgentState()
        result.messages = messages(value.messages)
        val tools = ArrayList<ToolDeclaration>()
        for (tool in value.tools) tools.add(toolDeclaration(tool))
        result.tools = tools
        result.model = model(value.model)
        result.thinkingLevel = value.thinkingLevel
        result.systemPrompt = value.systemPrompt
        result.isStreaming = value.isStreaming
        result.streamingMessage = value.streamingMessage?.let { message(it) }
        result.pendingToolCalls = LinkedHashSet(value.pendingToolCalls)
        result.errorMessage = value.errorMessage
        return result
    }
}
