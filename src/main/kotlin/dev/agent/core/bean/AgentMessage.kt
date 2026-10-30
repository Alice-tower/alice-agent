package dev.agent.core.bean

/** 消息接口；自定义消息必须实现深快照，以隔离宿主和运行时的可变数据。 */
interface AgentMessage {
    val role: String
    val timestamp: Long
    fun snapshot(): AgentMessage
}
