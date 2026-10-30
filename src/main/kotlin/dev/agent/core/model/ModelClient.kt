package dev.agent.core.model

import dev.agent.core.api.Cancellable
import dev.agent.core.bean.ModelRequest

/**
 * 在工作线程调用；实现可以同步或异步发事件，必须以 done/error 事件正常终止。
 * onEvent 返回前必须保持当前快照不变，返回后 core 已完成复制及消费。
 * 资源由返回句柄取消；不得持业务锁调用回调。core 不负责关闭共享客户端。
 */
interface ModelClient {
    fun stream(request: ModelRequest, callback: ModelCallback): Cancellable
}
