package dev.agent.core.model

import dev.agent.core.api.Cancellable
import dev.agent.core.api.NoOpCancellable
import dev.agent.core.bean.*

/** 将非流式模型的完整回复适配为普通事件回调。 */
class StaticModelClient(message: AssistantMessage) : ModelClient {
    private val message = BeanCopier().message(message) as AssistantMessage

    override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
        val copier = BeanCopier()
        val initial = copier.message(message) as AssistantMessage
        initial.content = emptyList()
        initial.stopReason = StopReason.PENDING
        val start = AssistantMessageEvent()
        start.type = "start"
        start.partial = initial
        callback.onEvent(start)
        val end = AssistantMessageEvent()
        end.type = if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) "error" else "done"
        end.message = copier.message(message) as AssistantMessage
        end.reason = message.stopReason
        callback.onEvent(end)
        callback.onComplete()
        return NoOpCancellable()
    }
}
