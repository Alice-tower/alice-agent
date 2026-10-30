package dev.agent.core.bean

/** 供宿主读取的状态快照；修改该 Bean 不影响 Agent 内部状态。 */
class AgentState {
    var messages: List<AgentMessage> = emptyList()
    var tools: List<ToolDeclaration> = emptyList()
    var model: Model = Model()
    var thinkingLevel: ThinkingLevel = ThinkingLevel.OFF
    var systemPrompt: String = ""
    var isStreaming: Boolean = false
    var streamingMessage: AgentMessage? = null
    var pendingToolCalls: Set<String> = emptySet()
    var errorMessage: String? = null
}
