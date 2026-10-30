package dev.agent.core.api

/** 用于已经同步完成或不需要额外清理的操作。 */
class NoOpCancellable : Cancellable { override fun cancel() {} }
