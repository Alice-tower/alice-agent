package dev.agent.core.api

import dev.agent.core.bean.*

/**
 * 宿主统一入口。run 立即返回句柄，实例同时只接受一个活动任务。
 * getState 返回独立快照，配置更新、历史恢复和 reset 只允许在空闲时使用。
 * updateConfig 保留历史和排队输入；config.messages/systemPrompt 仅用于创建初始历史。
 * core 关闭自己创建的线程池，不关闭 config.workerExecutor 注入的宿主资源。
 */
interface Agent : AutoCloseable {
    fun run(request: AgentRequest, callback: AgentCallback): AgentTask
    fun continueRun(callback: AgentCallback): AgentTask
    fun steer(message: AgentMessage)
    fun followUp(message: AgentMessage)
    fun getState(): AgentState
    fun updateConfig(config: AgentConfig)
    fun restoreHistory(messages: List<AgentMessage>)
    fun reset()
    fun clearSteeringQueue()
    fun clearFollowUpQueue()
    fun clearAllQueues()
    fun hasQueuedMessages(): Boolean
    fun peekQueuedMessages(): List<AgentMessage>
    override fun close()
}
