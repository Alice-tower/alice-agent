package dev.agent.core

import dev.agent.core.bean.AgentMessage

/** 自定义消息必须明确实现快照，才能与 core 的历史隔离。 */
class TestNoteMessage() : AgentMessage {
    var text: String = ""
    override val role: String = "note"
    override var timestamp: Long = 0

    constructor(text: String) : this() {
        this.text = text
    }

    override fun snapshot(): AgentMessage {
        val copy = TestNoteMessage(text)
        copy.timestamp = timestamp
        return copy
    }
}
