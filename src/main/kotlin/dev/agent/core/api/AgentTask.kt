package dev.agent.core.api

import dev.agent.core.bean.TaskStatus
import java.util.concurrent.TimeUnit

/** 单次任务句柄；await 仅供宿主后台线程使用，不可在本任务回调中等待。 */
interface AgentTask : Cancellable {
    fun getId(): String
    fun isDone(): Boolean
    fun getStatus(): TaskStatus
    fun await(timeout: Long, unit: TimeUnit): Boolean
}
