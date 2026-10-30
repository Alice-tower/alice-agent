package dev.agent.core.api

import dev.agent.core.bean.*

/**
 * 宿主统一入口。run 立即返回句柄，实例同时只接受一个活动任务。
 * getState 返回独立快照，配置更新、历史恢复和 reset 只允许在空闲时使用。
 * updateConfig 保留历史和排队输入；config.messages/systemPrompt 仅用于创建初始历史。
 * core 关闭自己创建的线程池，不关闭 config.workerExecutor 注入的宿主资源。
 */
interface Agent : AutoCloseable {
    /**
     * 复制 [request] 中的消息并启动新任务，立即返回可取消的任务句柄，不等待执行完成。
     * 模型、工具及 Hook 在工作线程执行；[callback] 在 Agent 的串行控制线程接收事件和结束通知。
     * 先提交状态再通知事件，最后事件处理完并进入空闲后才调用结束回调；回调异常会被隔离并记录。
     * 回调应快速返回，不可等待本任务或该 Agent 的下一任务完成。
     *
     * @throws IllegalStateException Agent 已关闭或已有活动任务。
     */
    fun run(request: AgentRequest, callback: AgentCallback): AgentTask

    /**
     * 基于已有历史继续运行，不额外提交请求消息；返回值和回调线程、时机与 [run] 相同。
     * 历史必须包含非系统消息；若最后一条是助手消息，还必须有排队输入供本次任务消费。
     *
     * @throws IllegalStateException Agent 已关闭、已有活动任务，或历史及排队输入不满足继续条件。
     */
    fun continueRun(callback: AgentCallback): AgentTask

    /**
     * 复制 [message] 并加入优先输入队列，不直接启动任务。
     * 活动任务在当前整批工具完成后的轮次调度阶段优先消费；空闲时排队的消息供后续任务消费。
     * 每次取一条还是全部取出由配置中的 steeringMode 决定。
     *
     * @throws IllegalStateException Agent 已关闭。
     */
    fun steer(message: AgentMessage)

    /**
     * 复制 [message] 并加入后续输入队列，不直接启动任务。
     * 当前任务自然准备停止时才消费，优先级低于工具续跑和 [steer] 输入；强制结束、失败或取消不保证消费。
     * 每次取一条还是全部取出由配置中的 followUpMode 决定。
     *
     * @throws IllegalStateException Agent 已关闭。
     */
    fun followUp(message: AgentMessage)

    /**
     * 返回当前历史、模型、流式消息、待完成工具及错误信息等状态的独立快照，可在运行中读取。
     * 修改返回对象不会影响内部状态；该方法不等待活动任务结束。
     */
    fun getState(): AgentState

    /**
     * 空闲时复制并更新后续任务使用的配置，保留已有历史和排队输入，同时更新队列消费策略。
     * 配置中的 messages 和 systemPrompt 不会替换当前对话；模型客户端、工具、Hook 和线程池保留引用。
     * 不允许更换 workerExecutor，需要更换时应创建新的 Agent。
     *
     * @throws IllegalStateException Agent 已关闭或已有活动任务。
     * @throws IllegalArgumentException 缺少模型客户端、工具名为空或重复，或尝试更换工作线程池。
     */
    fun updateConfig(config: AgentConfig)

    /**
     * 空闲时用 [messages] 的副本替换历史，清空两类输入队列以及流式、工具和错误状态。
     * 首条消息不是系统消息时，按当前配置补建系统基线；此方法不触发模型调用。
     *
     * @throws IllegalStateException Agent 已关闭或已有活动任务。
     */
    fun restoreHistory(messages: List<AgentMessage>)

    /**
     * 空闲时清理对话、两类输入队列及运行状态，保留当前历史回放得到的系统基线和现有配置。
     * 此方法不启动新任务。
     *
     * @throws IllegalStateException Agent 已关闭或已有活动任务。
     */
    fun reset()

    /**
     * 清空尚未选入当前准备阶段的优先输入，可在运行中调用，不影响后续输入队列。
     * 已选入准备阶段的输入仍可提交，但随后失败或取消时不会再退回此队列。
     */
    fun clearSteeringQueue()

    /**
     * 清空尚未选入当前准备阶段的后续输入，可在运行中调用，不影响优先输入队列。
     * 已选入准备阶段的输入仍可提交，但随后失败或取消时不会再退回此队列。
     */
    fun clearFollowUpQueue()

    /**
     * 依次清空优先输入和后续输入队列，可在运行中调用；两次清理不是跨队列的原子操作。
     * 已选入准备阶段的输入仍可提交，但随后失败或取消时不会再退回已清理的队列。
     */
    fun clearAllQueues()

    /**
     * 判断任一输入队列是否仍有待选取的消息，不包含已选入当前准备阶段的输入。
     * 可在运行中查询；结果不会预留消息，并发入队或消费可能使其立即变化。
     */
    fun hasQueuedMessages(): Boolean

    /**
     * 返回按当前消费策略选取的待处理消息副本，不移除或预留消息，也不包含已选入准备阶段的输入。
     * 优先输入非空时只返回该队列的消息，否则查看后续输入；ONE_AT_A_TIME 返回队首一条，ALL 返回该队列全部消息。
     * 修改返回数据不会影响队列；此预览不保证与后续实际消费结果一致。
     */
    fun peekQueuedMessages(): List<AgentMessage>

    /**
     * 幂等关闭 Agent，拒绝后续任务和输入，并请求取消活动任务。
     * 取消会传播到模型、工具及关联 IO，但不撤销已发生的外部操作，也不改写已确定的任务终态。
     * 此方法不等待任务结束；活动任务完成结束通知后释放 Agent 自有线程资源。
     * 不关闭宿主注入的共享线程池、模型客户端或工具，这些资源由宿主负责释放。
     */
    override fun close()
}
