package dev.agent.core.runtime

import dev.agent.core.api.Cancellable
import dev.agent.core.api.CancellationListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** 一次性取消注册；移除注册与发出通知共用原子门。 */
internal class CancellationRegistration(
    private val listener: CancellationListener,
    private val registrations: CopyOnWriteArrayList<CancellationRegistration>
) : Cancellable {
    private val active = AtomicBoolean(true)
    override fun cancel() {
        active.set(false)
        registrations.remove(this)
    }
    fun notifyCancellation() {
        registrations.remove(this)
        if (active.compareAndSet(true, false)) {
            try { listener.onCancelled() } catch (error: Throwable) { RuntimeLog().callbackFailure(error) }
        }
    }
}
