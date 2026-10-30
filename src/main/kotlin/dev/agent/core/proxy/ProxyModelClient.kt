package dev.agent.core.proxy

import dev.agent.core.api.Cancellable
import dev.agent.core.bean.BeanCopier
import dev.agent.core.bean.ModelRequest
import dev.agent.core.model.ModelCallback
import dev.agent.core.model.ModelClient
import java.net.http.HttpClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 实现本项目 JSON/SSE 协议的模型客户端，每次 stream 建立独立请求。
 *
 * 正常事件和完成回调在 IO 工作线程同步调用；onEvent 返回前不读取下一条，
 * 从而限制慢消费者的积压。调度被拒绝或启动前被取消时，onFailure 在调用线程通知。
 * 客户端拥有默认的有界线程池；宿主注入的线程池不由 close 关闭。
 */
class ProxyModelClient : ModelClient, AutoCloseable {
    private val proxyUrl: String
    private val authToken: AuthTokenProvider
    private val executor: ExecutorService
    private val ownsExecutor: Boolean
    private val httpClient: HttpClient = HttpClient.newBuilder().build()
    private val lock = Any()
    private val requests: MutableSet<ProxyRequestTask> = HashSet()
    private var closed: Boolean = false

    constructor(proxyUrl: String, authToken: AuthTokenProvider) {
        this.proxyUrl = proxyUrl
        this.authToken = authToken
        this.executor = ThreadPoolExecutor(
            4, 4, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(64), ProxyThreadFactory(),
        )
        this.ownsExecutor = true
    }

    constructor(proxyUrl: String, authToken: AuthTokenProvider, executor: ExecutorService) {
        this.proxyUrl = proxyUrl
        this.authToken = authToken
        this.executor = executor
        this.ownsExecutor = false
    }

    override fun stream(request: ModelRequest, callback: ModelCallback): Cancellable {
        val copy = BeanCopier().modelRequest(request)
        val task = ProxyRequestTask(proxyUrl, authToken, copy, callback, httpClient, this)
        synchronized(lock) {
            check(!closed) { "Proxy model client is closed" }
            requests.add(task)
        }
        task.start(executor)
        return task
    }

    /**
     * 幂等取消所有未完成请求，并停止客户端拥有的 HTTP 与线程资源。
     * 已发出终止事件的请求仍按原结果完成；其余请求通知取消异常。
     */
    override fun close() {
        val active: List<ProxyRequestTask>
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            active = ArrayList(requests)
        }
        for (task in active) {
            task.cancel()
        }
        httpClient.shutdown()
        if (ownsExecutor) {
            // 尚未启动的请求已独立结算，排队的 Runnable 随后运行时只会直接返回。
            executor.shutdown()
        }
    }

    internal fun requestFinished(task: ProxyRequestTask) {
        synchronized(lock) {
            requests.remove(task)
        }
    }
}
