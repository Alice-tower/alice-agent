package dev.agent.core.model

import dev.agent.core.bean.AssistantMessageEvent

/** onEvent 是同步消费边界；完成后不再发送事件，异常通过 onFailure 上报。 */
interface ModelCallback {
    fun onEvent(event: AssistantMessageEvent)
    fun onComplete()
    fun onFailure(error: Throwable)
}
