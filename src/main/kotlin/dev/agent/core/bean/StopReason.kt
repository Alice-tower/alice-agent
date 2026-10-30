package dev.agent.core.bean

/** 模型响应的停止原因，wire 保持代理协议兼容。 */
enum class StopReason(val wire: String) {
    PENDING("pending"), STOP("stop"), LENGTH("length"), TOOL_USE("toolUse"),
    ERROR("error"), ABORTED("aborted"), DEFERRED("deferred")
}
