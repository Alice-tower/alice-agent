package dev.agent.core

import dev.agent.core.bean.AgentMessage
import java.util.concurrent.atomic.AtomicBoolean

/** 共享开关用于在指定生命周期阶段模拟宿主快照失败。 */
class ThrowingSnapshotMessage(private val fail: AtomicBoolean) : AgentMessage {
    override val role: String = "snapshot-test"
    override val timestamp: Long = 0

    override fun snapshot(): AgentMessage {
        check(!fail.get()) { "测试消息无法生成快照" }
        return ThrowingSnapshotMessage(fail)
    }
}
