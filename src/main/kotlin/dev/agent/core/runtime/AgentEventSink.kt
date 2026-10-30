package dev.agent.core.runtime

import dev.agent.core.bean.AgentEvent

/** 控制线程提交事件的内部边界，返回时当前事件已经完成通知。 */
internal interface AgentEventSink {
    fun emit(event: AgentEvent)
}
