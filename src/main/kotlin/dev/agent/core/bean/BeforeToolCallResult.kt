package dev.agent.core.bean

/** 执行前决策；阻止执行时 reason 和 terminate 生效。 */
class BeforeToolCallResult {
    var block: Boolean = false
    var reason: String? = null
    var terminate: Boolean = false
}
