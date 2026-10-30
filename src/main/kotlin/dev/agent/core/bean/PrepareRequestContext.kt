package dev.agent.core.bean

/** 每次模型请求前的准备上下文，包含首轮。 */
class PrepareRequestContext {
    var context: AgentContext = AgentContext()
    var model: Model = Model()
    var thinkingLevel: ThinkingLevel = ThinkingLevel.OFF
}
