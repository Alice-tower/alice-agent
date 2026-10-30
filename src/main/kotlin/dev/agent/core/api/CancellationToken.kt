package dev.agent.core.api

import dev.agent.core.runtime.CancellationRegistration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** 只读取消信号。注册与取消竞争时，每个监听器最多执行一次。 */
class CancellationToken internal constructor() {
    private val cancelledFlag = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<CancellationRegistration>()
    val cancelled: Boolean get() = cancelledFlag.get()

    fun throwIfCancelled() {
        if (cancelled) throw CancellationException("Operation cancelled")
    }

    fun addListener(listener: CancellationListener): Cancellable {
        val registration = CancellationRegistration(listener, listeners)
        listeners.add(registration)
        if (cancelled) registration.notifyCancellation()
        return registration
    }

    internal fun cancel() {
        if (!cancelledFlag.compareAndSet(false, true)) return
        for (listener in listeners) listener.notifyCancellation()
        listeners.clear()
    }
}
