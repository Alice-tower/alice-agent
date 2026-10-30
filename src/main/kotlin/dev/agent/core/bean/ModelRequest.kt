package dev.agent.core.bean

/** 提交给模型客户端的模型、上下文和请求选项快照。 */
class ModelRequest {
    var model: Model = Model()
    var context: AgentContext = AgentContext()
    var options: StreamOptions = StreamOptions()
}
