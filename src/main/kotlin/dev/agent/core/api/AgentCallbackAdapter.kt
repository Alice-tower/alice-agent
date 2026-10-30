package dev.agent.core.api

import dev.agent.core.bean.AgentEvent
import dev.agent.core.bean.AgentResult

/** 可按需覆盖的普通回调适配器。 */
open class AgentCallbackAdapter : AgentCallback {
    override fun onEvent(event: AgentEvent) {}
    override fun onSuccess(result: AgentResult) {}
    override fun onFailure(error: Throwable) {}
    override fun onCancelled() {}
}
