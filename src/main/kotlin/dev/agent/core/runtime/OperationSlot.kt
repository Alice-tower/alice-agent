package dev.agent.core.runtime

import dev.agent.core.api.Cancellable
import dev.agent.core.api.CancellationListener
import dev.agent.core.api.CancellationToken
import java.util.concurrent.Future

/** 解决取消发生在资源句柄登记之前的竞争。锁外关闭外部资源。 */
internal class OperationSlot(token: CancellationToken) : Cancellable {
    private val lock = Any()
    private var closed = false
    private var interrupted = false
    private var future: Future<*>? = null
    private var handle: Cancellable? = null
    private var registration: Cancellable? = null

    init {
        val listener = object : CancellationListener { override fun onCancelled() { cancel() } }
        val value = token.addListener(listener)
        synchronized(lock) { registration = value }
        if (closed) value.cancel()
    }

    fun setFuture(value: Future<*>) {
        val cancelNow = synchronized(lock) { future = value; interrupted }
        if (cancelNow) value.cancel(true)
    }
    fun setHandle(value: Cancellable) {
        val cancelNow = synchronized(lock) { handle = value; closed }
        if (cancelNow) safelyCancel(value)
    }
    fun finish() { release(false) }
    override fun cancel() { release(true) }

    private fun release(interrupt: Boolean) {
        val values: Triple<Cancellable?, Future<*>?, Cancellable?>
        synchronized(lock) {
            if (closed && (!interrupt || interrupted)) return
            closed = true
            interrupted = interrupted || interrupt
            values = Triple(handle, if (interrupt) future else null, registration)
            handle = null
            registration = null
        }
        values.third?.cancel()
        values.second?.cancel(true)
        values.first?.let { safelyCancel(it) }
    }
    private fun safelyCancel(value: Cancellable) {
        try { value.cancel() } catch (error: Throwable) { RuntimeLog().callbackFailure(error) }
    }
}
