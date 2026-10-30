package dev.agent.core

import dev.agent.core.bean.AssistantMessageEvent
import dev.agent.core.bean.ModelRequest
import dev.agent.core.model.ModelCallback
import dev.agent.core.proxy.AuthTokenProvider
import dev.agent.core.proxy.ProxyModelClient
import dev.agent.core.runtime.CancellationSource
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 排队请求的取消不依赖宿主执行器再次调度，所有等待均通过有超时的同步屏障。 */
class QueuedProxyCancellationTest : CoreTestSupport() {
    @Test fun queuedHandleCancellationCompletesBeforeWorkerIsReleased() {
        val release = CountDownLatch(1)
        val workers = occupiedExecutor(release)
        val reads = AtomicInteger()
        val source = CancellationSource()
        val client = ProxyModelClient("http://127.0.0.1:1", tokenProvider(reads), workers)
        val count = AtomicInteger()
        val thread = AtomicReference<Thread>()
        val callback = cancellationCallback(count, thread)
        val request = ModelRequest()
        request.options.signal = source.token
        try {
            val handle = client.stream(request, callback)
            handle.cancel()
            assertEquals(1, count.get())
            assertSame(Thread.currentThread(), thread.get())
            assertIs<CancellationException>(callback.failure)
            assertTrue(callback.events.isEmpty())
            assertEquals(0, reads.get())
            handle.cancel()
            source.cancel()
            client.close()
            val discarded = workers.shutdownNow()
            assertEquals(1, discarded.size)
            assertEquals(1, count.get())
        } finally {
            release.countDown()
            client.close()
            workers.shutdownNow()
        }
    }

    @Test fun closingClientCompletesAllQueuedRequestsWithoutClosingSharedExecutor() {
        val release = CountDownLatch(1)
        val workers = occupiedExecutor(release)
        val reads = AtomicInteger()
        val client = ProxyModelClient("http://127.0.0.1:1", tokenProvider(reads), workers)
        val firstCount = AtomicInteger()
        val secondCount = AtomicInteger()
        val first = cancellationCallback(firstCount, AtomicReference())
        val second = cancellationCallback(secondCount, AtomicReference())
        try {
            client.stream(ModelRequest(), first)
            client.stream(ModelRequest(), second)
            client.close()
            client.close()
            assertEquals(1, firstCount.get())
            assertEquals(1, secondCount.get())
            assertIs<CancellationException>(first.failure)
            assertIs<CancellationException>(second.failure)
            assertFalse(workers.isShutdown)
            release.countDown()
            workers.submit(Runnable {}).get(5, TimeUnit.SECONDS)
            assertEquals(0, reads.get())
            assertEquals(1, firstCount.get())
            assertEquals(1, secondCount.get())
        } finally {
            release.countDown()
            client.close()
            workers.shutdownNow()
        }
    }

    @Test fun preCancelledTokenCompletesSynchronouslyWithoutSchedulingRequest() {
        val release = CountDownLatch(1)
        val workers = occupiedExecutor(release)
        val reads = AtomicInteger()
        val source = CancellationSource()
        source.cancel()
        val request = ModelRequest()
        request.options.signal = source.token
        val client = ProxyModelClient("http://127.0.0.1:1", tokenProvider(reads), workers)
        val count = AtomicInteger()
        val callback = cancellationCallback(count, AtomicReference())
        try {
            val handle = client.stream(request, callback)
            assertEquals(1, count.get())
            assertIs<CancellationException>(callback.failure)
            assertTrue(workers.queue.isEmpty())
            assertEquals(0, reads.get())
            handle.cancel()
            client.close()
            assertEquals(1, count.get())
        } finally {
            release.countDown()
            client.close()
            workers.shutdownNow()
        }
    }

    @Test fun queuedCallbackExceptionDoesNotPreventOtherRequestsFromClosing() {
        val release = CountDownLatch(1)
        val workers = occupiedExecutor(release)
        val client = ProxyModelClient("http://127.0.0.1:1", tokenProvider(AtomicInteger()), workers)
        val count = AtomicInteger()
        val callback = cancellationCallback(count, AtomicReference())
        val badCallback = object : ModelCallback {
            override fun onEvent(event: AssistantMessageEvent) {}
            override fun onComplete() { error("unexpected completion") }
            override fun onFailure(error: Throwable) { throw IllegalStateException("consumer failure") }
        }
        try {
            client.stream(ModelRequest(), badCallback)
            client.stream(ModelRequest(), callback)
            client.close()
            assertEquals(1, count.get())
            assertIs<CancellationException>(callback.failure)
        } finally {
            release.countDown()
            client.close()
            workers.shutdownNow()
        }
    }

    @Test fun startingWorkerAndCancellingRequestCompeteForOneCompletion() {
        val racers = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val releaseWorker = CountDownLatch(1)
                val releaseAuth = CountDownLatch(1)
                val workers = occupiedExecutor(releaseWorker)
                val provider = object : AuthTokenProvider {
                    override fun getToken(): String {
                        check(releaseAuth.await(5, TimeUnit.SECONDS)) { "认证同步条件超时" }
                        return "local-test"
                    }
                }
                val client = ProxyModelClient("http://127.0.0.1:1", provider, workers)
                val count = AtomicInteger()
                val callback = cancellationCallback(count, AtomicReference())
                try {
                    val handle = client.stream(ModelRequest(), callback)
                    val start = CountDownLatch(1)
                    val cancel = racers.submit(Runnable {
                        check(start.await(5, TimeUnit.SECONDS))
                        handle.cancel()
                    })
                    val schedule = racers.submit(Runnable {
                        check(start.await(5, TimeUnit.SECONDS))
                        releaseWorker.countDown()
                    })
                    start.countDown()
                    cancel.get(5, TimeUnit.SECONDS)
                    schedule.get(5, TimeUnit.SECONDS)
                    callback.await()
                    releaseAuth.countDown()
                    workers.submit(Runnable {}).get(5, TimeUnit.SECONDS)
                    handle.cancel()
                    client.close()
                    assertIs<CancellationException>(callback.failure)
                    assertEquals(1, count.get())
                    assertTrue(callback.events.isEmpty())
                } finally {
                    releaseWorker.countDown()
                    releaseAuth.countDown()
                    client.close()
                    workers.shutdownNow()
                }
            }
        } finally {
            racers.shutdownNow()
        }
    }

    private fun occupiedExecutor(release: CountDownLatch): ThreadPoolExecutor {
        val workers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
        val entered = CountDownLatch(1)
        workers.submit(Runnable {
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS)) { "工作线程同步条件超时" }
            } catch (interrupted: InterruptedException) {
                // shutdownNow 仅用于用例结束时停止占位工作，不影响请求的结束断言。
            }
        })
        waitFor(entered)
        return workers
    }

    private fun tokenProvider(reads: AtomicInteger): AuthTokenProvider {
        return object : AuthTokenProvider {
            override fun getToken(): String {
                reads.incrementAndGet()
                return "local-test"
            }
        }
    }

    private fun cancellationCallback(count: AtomicInteger, thread: AtomicReference<Thread>): RecordingModelCallback {
        return object : RecordingModelCallback() {
            override fun onComplete() {
                count.incrementAndGet()
                thread.set(Thread.currentThread())
                super.onComplete()
            }
            override fun onFailure(error: Throwable) {
                count.incrementAndGet()
                thread.set(Thread.currentThread())
                super.onFailure(error)
            }
        }
    }
}
