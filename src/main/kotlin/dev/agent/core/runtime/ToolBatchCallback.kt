package dev.agent.core.runtime

/** 工具批次只在控制线程完成，失败表示基础调度异常而非普通工具错误。 */
internal interface ToolBatchCallback {
    fun onComplete(batch: ToolBatch)
    fun onFailure(error: Throwable)
}
