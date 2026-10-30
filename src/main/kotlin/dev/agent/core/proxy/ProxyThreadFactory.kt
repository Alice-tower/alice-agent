package dev.agent.core.proxy

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** 创建代理拥有的后台线程；关闭客户端时停止接收新的工作。 */
internal class ProxyThreadFactory : ThreadFactory {
    private val nextId = AtomicInteger()

    override fun newThread(task: Runnable): Thread {
        val thread = Thread(task, "agent-proxy-io-" + nextId.incrementAndGet())
        thread.isDaemon = true
        return thread
    }
}
