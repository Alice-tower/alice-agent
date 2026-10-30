package dev.agent.core.runtime

import dev.agent.core.api.CancellationToken

/** 取消信号的所有者。 */
class CancellationSource {
    val token = CancellationToken()
    fun cancel() { token.cancel() }
}
