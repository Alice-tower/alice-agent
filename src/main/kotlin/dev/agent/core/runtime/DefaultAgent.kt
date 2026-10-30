package dev.agent.core.runtime

import dev.agent.core.api.*
import dev.agent.core.bean.*
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 回调式 Agent 默认实现。锁只保护状态读写，不在锁内调用业务代码。
 * 控制线程串行提交状态与事件，工作线程执行模型、工具及 Hook。
 */
class DefaultAgent(configuration: AgentConfig) : Agent {
    private val lock = Any()
    private val copier = BeanCopier()
    private val transcript = Transcript()
    private var config = prepareConfig(configuration)
    private var messages = initialMessages(config)
    // 初始历史已经归状态所有，后续运行不再复制这份过期的初始配置数据。
    init { config.messages = emptyList() }
    private val steering = PendingMessageQueue(config.steeringMode)
    private val followUps = PendingMessageQueue(config.followUpMode)
    private val dispatcher = SerialDispatcher("agent-control-" + UUID.randomUUID())
    private val ownsWorkers = config.workerExecutor == null
    private val workers: ExecutorService = config.workerExecutor ?: Executors.newFixedThreadPool(8, object : ThreadFactory {
        override fun newThread(action: Runnable): Thread {
            val thread = Thread(action, "agent-worker-" + UUID.randomUUID())
            thread.isDaemon = true
            return thread
        }
    })
    private var active: AgentRun? = null
    private var closed = false
    private var streamingMessage: AgentMessage? = null
    private var pendingTools = LinkedHashSet<String>()
    private var errorMessage: String? = null

    override fun run(request: AgentRequest, callback: AgentCallback): AgentTask {
        val input = copier.messages(request.messages)
        val run: AgentRun
        synchronized(lock) {
            checkAvailable()
            run = newRun(input, callback, false)
            active = run
            errorMessage = null
        }
        dispatcher.post(Runnable { run.start() })
        return run.task
    }

    override fun continueRun(callback: AgentCallback): AgentTask {
        val run: AgentRun
        synchronized(lock) {
            checkAvailable()
            check(messages.any { it !is SystemMessage }) { "No messages to continue from" }
            val fromAssistant = messages.last() is AssistantMessage
            if (fromAssistant) {
                check(hasQueuedMessages()) { "Cannot continue from assistant without queued input" }
            }
            run = newRun(emptyList(), callback, fromAssistant)
            active = run
            errorMessage = null
        }
        dispatcher.post(Runnable { run.start() })
        return run.task
    }

    private fun newRun(input: List<AgentMessage>, callback: AgentCallback, fromAssistant: Boolean): AgentRun {
        val context = AgentContext()
        // 内部历史采用替换式写入，引用稳定；深复制在 run.start 的锁外阶段完成。
        context.messages = messages
        context.tools = config.tools.toList()
        return AgentRun(this, config, context, input, callback, dispatcher, workers, fromAssistant)
    }

    override fun getState(): AgentState {
        val state = AgentState()
        val configuration: AgentConfig
        synchronized(lock) {
            configuration = config
            state.messages = messages
            state.model = configuration.model
            state.thinkingLevel = configuration.thinkingLevel
            state.isStreaming = active != null
            state.streamingMessage = streamingMessage
            state.pendingToolCalls = LinkedHashSet(pendingTools)
            state.errorMessage = errorMessage
        }
        state.tools = configuration.tools.map { it.getDefinition().declaration() }
        state.systemPrompt = transcript.currentSystemPrompt(state.messages)
        return copier.agentState(state)
    }

    override fun updateConfig(config: AgentConfig) {
        val snapshot = prepareConfig(config)
        snapshot.messages = emptyList()
        synchronized(lock) {
            checkAvailable()
            require(snapshot.workerExecutor === this.config.workerExecutor) { "Create a new Agent to change workerExecutor" }
            this.config = snapshot
            steering.mode = snapshot.steeringMode
            followUps.mode = snapshot.followUpMode
        }
    }

    override fun restoreHistory(messages: List<AgentMessage>) {
        val history = copier.messages(messages)
        while (true) {
            val configuration = synchronized(lock) { checkAvailable(); config }
            val baseline = if (history.firstOrNull() is SystemMessage) null else
                transcript.initialSystemMessage(configuration.systemPrompt, configuration.tools)
            val restored = if (baseline == null) history else listOf(baseline) + history
            synchronized(lock) {
                checkAvailable()
                if (config !== configuration) return@synchronized
                this.messages = restored
                streamingMessage = null
                pendingTools.clear()
                errorMessage = null
                clearAllQueues()
                return
            }
        }
    }

    override fun reset() = synchronized(lock) {
        checkAvailable()
        val baseline = transcript.currentSystemMessage(messages)
        messages = if (baseline == null) emptyList() else listOf(baseline)
        errorMessage = null
        streamingMessage = null
        pendingTools.clear()
        clearAllQueues()
    }

    override fun steer(message: AgentMessage) {
        val snapshot = copier.message(message)
        synchronized(lock) {
            check(!closed) { "Agent is closed" }
            steering.enqueueOwned(snapshot)
        }
    }
    override fun followUp(message: AgentMessage) {
        val snapshot = copier.message(message)
        synchronized(lock) {
            check(!closed) { "Agent is closed" }
            followUps.enqueueOwned(snapshot)
        }
    }
    override fun clearSteeringQueue() { steering.clear() }
    override fun clearFollowUpQueue() { followUps.clear() }
    override fun clearAllQueues() { steering.clear(); followUps.clear() }
    override fun hasQueuedMessages(): Boolean = steering.hasItems() || followUps.hasItems()
    override fun peekQueuedMessages(): List<AgentMessage> {
        val first = steering.peek()
        return if (first.isNotEmpty()) first else followUps.peek()
    }
    internal fun reserveSteering(): PendingInputBatch? = steering.reserve()
    internal fun reserveFollowUps(): PendingInputBatch? = followUps.reserve()

    internal fun commit(event: AgentEvent) {
        val message = when (event.type) {
            AgentEventType.MESSAGE_START, AgentEventType.MESSAGE_UPDATE, AgentEventType.MESSAGE_END -> event.message?.let { copier.message(it) }
            else -> null
        }
        synchronized(lock) {
            when (event.type) {
                AgentEventType.MESSAGE_START, AgentEventType.MESSAGE_UPDATE -> streamingMessage = message
                AgentEventType.MESSAGE_END -> {
                    messages = messages + requireNotNull(message)
                    streamingMessage = null
                    if (message is AssistantMessage) errorMessage = message.errorMessage
                }
                AgentEventType.TOOL_EXECUTION_START -> pendingTools.add(event.toolCallId)
                AgentEventType.TOOL_EXECUTION_END -> pendingTools.remove(event.toolCallId)
                else -> {}
            }
        }
    }

    internal fun recordFailure(error: Throwable) = synchronized(lock) {
        errorMessage = error.message ?: error.toString()
    }

    internal fun release(run: AgentRun) = synchronized(lock) {
        if (active === run) {
            active = null
            streamingMessage = null
            pendingTools.clear()
        }
    }

    internal fun closeIfIdle() {
        val shouldClose = synchronized(lock) { closed && active == null }
        if (shouldClose) {
            dispatcher.close()
            if (ownsWorkers) workers.shutdownNow()
        }
    }

    override fun close() {
        val run = synchronized(lock) {
            if (closed) return
            closed = true
            active
        }
        run?.task?.cancel()
        closeIfIdle()
    }

    private fun checkAvailable() {
        check(!closed) { "Agent is closed" }
        check(active == null) { "Agent is already processing; use steer or followUp to queue input" }
    }

    private fun prepareConfig(value: AgentConfig): AgentConfig {
        require(value.modelClient != null) { "AgentConfig.modelClient is required" }
        val result = copier.config(value)
        result.tools = result.tools.map { ConfiguredTool(it) }
        val names = result.tools.map { it.getDefinition().name }
        require(names.all { it.isNotBlank() } && names.toSet().size == names.size) { "Tool names must be nonempty and unique" }
        return result
    }

    private fun initialMessages(value: AgentConfig): List<AgentMessage> {
        val history = copier.messages(value.messages)
        if (history.firstOrNull() is SystemMessage) return history
        val baseline = transcript.initialSystemMessage(value.systemPrompt, value.tools) ?: return history
        return listOf(baseline) + history
    }
}
