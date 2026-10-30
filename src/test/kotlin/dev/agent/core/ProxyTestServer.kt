package dev.agent.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 本地确定性 SSE 服务；暂停读取场景通过 latch 释放，不依赖固定等待。 */
class ProxyTestServer(body: String, status: Int = 200, keepOpen: Boolean = false) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val finish = CountDownLatch(1)
    val started = CountDownLatch(1)
    val request = AtomicReference("")
    val auth = AtomicReference("")
    val url: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/api/stream") { exchange ->
            request.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            auth.set(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(status, if (keepOpen) 0 else bytes.size.toLong())
            try {
                exchange.responseBody.use { output ->
                    output.write(bytes)
                    output.flush()
                    started.countDown()
                    if (keepOpen) finish.await(8, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                // 取消测试允许客户端先关闭响应体。
            } finally { exchange.close() }
        }
        server.start()
    }

    override fun close() {
        finish.countDown()
        server.stop(0)
    }
}
