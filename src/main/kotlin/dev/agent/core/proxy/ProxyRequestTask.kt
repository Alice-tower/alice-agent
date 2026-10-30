package dev.agent.core.proxy

import dev.agent.core.api.Cancellable
import dev.agent.core.api.CancellationListener
import dev.agent.core.bean.AssistantMessageEvent
import dev.agent.core.bean.BeanCopier
import dev.agent.core.bean.ModelRequest
import dev.agent.core.bean.StopReason
import dev.agent.core.model.ModelCallback
import dev.agent.core.protocol.ProtocolCodec
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 单次代理 IO 任务。已启动任务由工作线程串行发出回调；尚未启动的取消由取消线程通知。
 *
 * 状态 0 接收事件，1 已开始通知模型终止事件，2 已取消。
 * 终止事件和取消通过同一原子状态竞争，保证完成结果不被迟到取消改写。
 */
internal class ProxyRequestTask(
    private val proxyUrl: String,
    private val authToken: AuthTokenProvider,
    private val request: ModelRequest,
    private val callback: ModelCallback,
    private val httpClient: HttpClient,
    private val owner: ProxyModelClient,
) : Runnable, Cancellable, CancellationListener {
    private val state = AtomicInteger(0)
    // 0 尚未进入工作线程，1 工作线程已接管，2 已在启动前取消。
    private val execution = AtomicInteger(0)
    private val completed = AtomicBoolean()
    private val runningThread = AtomicReference<Thread?>()
    private val responseFuture = AtomicReference<CompletableFuture<HttpResponse<InputStream>>?>()
    private val responseBody = AtomicReference<InputStream?>()
    private val cancellationRegistration = AtomicReference<Cancellable?>()
    private val reducer = ProxyEventReducer(request.model)
    private val copier = BeanCopier()
    private var downstreamFailure: Throwable? = null

    fun start(executor: ExecutorService) {
        try {
            val registration = request.options.signal?.addListener(this)
            cancellationRegistration.set(registration)
            if (execution.get() == 2 || completed.get()) {
                cancellationRegistration.getAndSet(null)?.cancel()
                return
            }
            executor.execute(this)
        } catch (failure: Throwable) {
            cleanup()
            if (state.get() == 2) {
                notifyFailure(cancelledFailure())
            } else {
                notifyFailure(failure)
            }
        }
    }

    override fun run() {
        if (!execution.compareAndSet(0, 1)) {
            return
        }
        runningThread.set(Thread.currentThread())
        var failure: Throwable? = null
        try {
            readResponse()
        } catch (error: Throwable) {
            if (state.get() == 2 || request.options.signal?.cancelled == true && state.get() != 1) {
                failure = cancelledFailure()
            } else if (error === downstreamFailure) {
                failure = error
            } else {
                try {
                    val cause = unwrap(error)
                    emit(reducer.failure(cause.message ?: cause.toString(), StopReason.ERROR))
                } catch (callbackError: Throwable) {
                    if (state.get() == 2) {
                        failure = cancelledFailure()
                    } else {
                        failure = callbackError
                    }
                }
            }
        } finally {
            runningThread.set(null)
            cleanup()
        }
        if (failure == null) {
            notifyComplete()
        } else {
            notifyFailure(failure)
        }
    }

    override fun cancel() {
        val cancelled = state.compareAndSet(0, 2) || state.get() == 2
        closeResources()
        if (cancelled) {
            if (execution.compareAndSet(0, 2)) {
                cleanup()
                notifyFailure(cancelledFailure())
                return
            }
            val thread = runningThread.get()
            if (thread != null && thread !== Thread.currentThread()) {
                thread.interrupt()
            }
        }
    }

    override fun onCancelled() {
        cancel()
    }

    private fun readResponse() {
        checkCancelled()
        val token = authToken.getToken()
        checkCancelled()
        val body = createPayload()
        val httpRequest = HttpRequest.newBuilder(URI(proxyUrl.trimEnd('/') + "/api/stream"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        checkCancelled()
        val pending = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        responseFuture.set(pending)
        if (state.get() == 2) {
            pending.cancel(true)
        }
        val response = pending.get()
        val input = response.body()
        responseBody.set(input)
        checkCancelled()
        request.options.onResponse?.onResponse(response.statusCode(), response.headers().map())
        checkCancelled()
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(httpError(response.statusCode(), input))
        }
        val reader = input.bufferedReader(Charsets.UTF_8)
        while (true) {
            checkCancelled()
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) {
                continue
            }
            val data = line.substring(5).trim()
            if (data.isEmpty()) {
                continue
            }
            val value = Json.parseToJsonElement(data)
            request.options.onProviderStreamEvent?.onEvent(value)
            checkCancelled()
            val event = reducer.process(value.jsonObject)
            if (event != null) {
                emit(event)
                if (event.terminal) {
                    return
                }
            }
        }
        checkCancelled()
        emit(reducer.failure("Connection closed by proxy server before the response completed", StopReason.ERROR))
    }

    private fun createPayload(): JsonElement {
        val codec = ProtocolCodec()
        val values = LinkedHashMap<String, JsonElement>()
        values["model"] = codec.model(request.model)
        values["context"] = codec.context(request.context)
        values["options"] = codec.options(request.options)
        val payload = JsonObject(values)
        val hook = request.options.onPayload
        if (hook == null) {
            return payload
        }
        return hook.onPayload(payload) ?: payload
    }

    private fun httpError(status: Int, input: InputStream): String {
        val text = input.readBytes().toString(Charsets.UTF_8)
        checkCancelled()
        var detail: String? = null
        try {
            detail = Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        } catch (failure: IllegalArgumentException) {
            // 非 JSON 错误页仅报告 HTTP 状态。
        }
        if (detail != null) {
            return "Proxy error: $detail"
        }
        return "Proxy error: $status"
    }

    private fun emit(event: AssistantMessageEvent) {
        if (event.terminal) {
            if (!state.compareAndSet(0, 1)) {
                checkCancelled()
                return
            }
        } else {
            checkCancelled()
        }
        try {
            callback.onEvent(copier.assistantEvent(event))
        } catch (failure: Throwable) {
            downstreamFailure = failure
            throw failure
        }
    }

    private fun checkCancelled() {
        if (state.get() == 2) {
            throw cancelledFailure()
        }
        if (request.options.signal?.cancelled == true) {
            cancel()
            throw cancelledFailure()
        }
    }

    private fun cancelledFailure(): CancellationException {
        return CancellationException("Proxy request cancelled")
    }

    private fun unwrap(failure: Throwable): Throwable {
        if (failure is ExecutionException && failure.cause != null) {
            return failure.cause!!
        }
        return failure
    }

    private fun closeResources() {
        responseFuture.get()?.cancel(true)
        val input = responseBody.getAndSet(null)
        if (input != null) {
            try {
                input.close()
            } catch (failure: Exception) {
                // 取消和 finally 都可能关闭同一响应，关闭异常不覆盖原终态。
            }
        }
    }

    private fun cleanup() {
        closeResources()
        cancellationRegistration.getAndSet(null)?.cancel()
        owner.requestFinished(this)
    }

    private fun notifyComplete() {
        if (!completed.compareAndSet(false, true)) {
            return
        }
        try {
            callback.onComplete()
        } catch (failure: Throwable) {
            // 终态回调异常由调用方处理，不能触发第二次终态。
        }
    }

    private fun notifyFailure(failure: Throwable) {
        if (!completed.compareAndSet(false, true)) {
            return
        }
        try {
            callback.onFailure(failure)
        } catch (callbackFailure: Throwable) {
            // 终态回调异常不能使工作线程泄漏或重复通知。
        }
    }
}
