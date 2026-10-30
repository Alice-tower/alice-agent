package dev.agent.core.bean

/** 准备 Hook 返回的局部更新；未指定字段保持现状。 */
class AgentLoopTurnUpdate {
    var context: AgentContext? = null
    var messages: List<AgentMessage> = emptyList()
    var model: Model? = null
    var thinkingLevel: ThinkingLevel? = null
}
