package dev.agent.core

import dev.agent.core.bean.ContentBlock
import java.util.concurrent.atomic.AtomicBoolean

/** 模拟工具结果中的扩展内容在后置 Hook 之后无法再复制。 */
class ThrowingSnapshotContent(private val fail: AtomicBoolean) : ContentBlock {
    override fun snapshot(): ContentBlock {
        check(!fail.get()) { "测试工具内容无法生成快照" }
        return ThrowingSnapshotContent(fail)
    }
}
