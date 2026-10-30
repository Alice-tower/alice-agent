package dev.agent.core.api

import dev.agent.core.bean.AgentEvent
import dev.agent.core.bean.AgentResult

/**
 * 同一 Agent 的回调在其控制线程串行执行。onEvent 同步返回形成背压，应快速返回。
 * 最后事件返回、Agent 进入空闲后，才通知三种结束回调中的一种。
 * 业务回调抛出的异常被隔离并记录，不改变任务结果。
 * 同一 Agent 的回调共用控制线程，不能在其中等待本任务或该 Agent 的下一任务完成。
 */
interface AgentCallback {
    fun onEvent(event: AgentEvent)
    fun onSuccess(result: AgentResult)
    fun onFailure(error: Throwable)
    fun onCancelled()
}
