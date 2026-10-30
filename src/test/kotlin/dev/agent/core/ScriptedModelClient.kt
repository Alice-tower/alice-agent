package dev.agent.core

import dev.agent.core.api.Cancellable
import dev.agent.core.api.NoOpCancellable
import dev.agent.core.bean.AssistantMessage
import dev.agent.core.bean.AssistantMessageEvent
import dev.agent.core.bean.BeanCopier
import dev.agent.core.bean.ModelRequest
import dev.agent.core.bean.StopReason
import dev.agent.core.model.ModelCallback
import dev.agent.core.model.ModelClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 同步发出回调，验证 core 不依赖模型适配器的回调线程或异步时机。 */
open class ScriptedModelClient(vararg messages: AssistantMessage) : ModelClient {
    private val responses = messages.toList()
    val requests = CopyOnWriteArrayList<ModelRequest>()
    val count = AtomicInteger()

    override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
        requests.add(request)
        val index = count.getAndIncrement()
        val message = respond(request, index)
        val start = AssistantMessageEvent()
        start.type = "start"
        start.partial = AssistantMessage()
        start.partial!!.stopReason = StopReason.PENDING
        callback.onEvent(start)
        val done = AssistantMessageEvent()
        done.type = if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) "error" else "done"
        done.message = BeanCopier().message(message) as AssistantMessage
        done.reason = message.stopReason
        callback.onEvent(done)
        callback.onComplete()
        return NoOpCancellable()
    }

    open fun respond(request: ModelRequest, index: Int): AssistantMessage {
        check(index < responses.size) { "意外的第 ${index + 1} 次模型请求" }
        return responses[index]
    }
}
