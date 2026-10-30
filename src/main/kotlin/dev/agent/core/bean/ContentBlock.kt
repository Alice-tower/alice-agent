package dev.agent.core.bean

/** 消息内容接口；自定义实现必须返回隔离可变数据的快照。 */
interface ContentBlock {
    fun snapshot(): ContentBlock
}
