package dev.agent.core.tool

import dev.agent.core.bean.AgentToolResult

/** onSuccess/onFailure 只能调用一次；结束后的进度由 core 忽略。 */
interface ToolCallback {
    fun onProgress(result: AgentToolResult)
    fun onSuccess(result: AgentToolResult)
    fun onFailure(error: Throwable)
}
