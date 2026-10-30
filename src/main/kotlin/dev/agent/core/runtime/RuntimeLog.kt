package dev.agent.core.runtime

/** 回调异常被隔离并交给 JDK 日志系统，避免递归发送错误事件。 */
internal class RuntimeLog {
    fun callbackFailure(error: Throwable) {
        System.getLogger("dev.agent.core").log(System.Logger.Level.WARNING, "Agent callback failed", error)
    }
}
