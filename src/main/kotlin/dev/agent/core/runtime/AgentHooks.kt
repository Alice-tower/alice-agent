package dev.agent.core.runtime

import dev.agent.core.api.CancellationToken
import dev.agent.core.bean.*

/**
 * 可按需覆盖的运行策略。方法在工作线程按阶段执行，允许普通阻塞 IO，需响应取消信号。
 * 上下文为副本；通过返回值表达变更，beforeToolCall 可替换 args，替换后不重复验证。
 */
open class AgentHooks {
    open fun transformContext(messages: List<AgentMessage>, signal: CancellationToken): List<AgentMessage> = messages
    open fun convertToLlm(messages: List<AgentMessage>): List<AgentMessage> {
        val result = ArrayList<AgentMessage>()
        for (message in messages) {
            if (message is SystemMessage || message is UserMessage || message is AssistantMessage || message is ToolResultMessage) result.add(message)
        }
        return result
    }
    open fun getApiKey(provider: String): String? = null
    open fun beforeToolCall(context: BeforeToolCallContext, signal: CancellationToken): BeforeToolCallResult? = null
    open fun afterToolCall(context: AfterToolCallContext, signal: CancellationToken): AfterToolCallResult? = null
    open fun finishTurn(context: AgentTurnContext, signal: CancellationToken): AgentTurnDecision? = null
    open fun prepareRequest(context: PrepareRequestContext, signal: CancellationToken): AgentLoopTurnUpdate? = null
    open fun prepareNextTurn(context: AgentTurnContext, signal: CancellationToken): AgentLoopTurnUpdate? = null
}
