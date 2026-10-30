package dev.agent.core

import dev.agent.core.bean.AgentMessage

/** 普通自定义消息，复制时执行可控的并发访问探针。 */
class LockCheckingMessage(private val probe: SnapshotLockProbe) : AgentMessage {
    override val role: String = "lock-test"
    override val timestamp: Long = 0

    override fun snapshot(): AgentMessage {
        probe.checkAccess()
        return LockCheckingMessage(probe)
    }
}
