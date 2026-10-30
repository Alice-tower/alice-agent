package dev.agent.core.runtime

import dev.agent.core.api.*
import dev.agent.core.bean.*
import dev.agent.core.model.ModelCallback
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.function.Consumer
import kotlinx.serialization.json.JsonObject

/**
 * 单次运行的显式阶段调度器。只有控制线程修改上下文；阻塞操作全部提交工作线程。
 * 外部回调通过有界 dispatcher 同步提交，终态和过期模型事件在控制线程过滤。
 */
internal class AgentRun(
    private val owner: DefaultAgent,
    private var config: AgentConfig,
    private var context: AgentContext,
    private val input: List<AgentMessage>,
    private val callback: AgentCallback,
    private val dispatcher: SerialDispatcher,
    private val workers: ExecutorService,
    private val continueFromAssistant: Boolean
) : AgentEventSink {
    private val copier = BeanCopier()
    private val transcript = Transcript()
    private val source = CancellationSource()
    val task = DefaultAgentTask(UUID.randomUUID().toString(), source, Runnable { dispatcher.post(Runnable { cancelRun() }) })
    private val added = ArrayList<AgentMessage>()
    private var started = false
    private var turnOpen = false
    private var currentResults: List<ToolResultMessage> = emptyList()
    private var pendingInput: PendingInputBatch? = null
    @Volatile private var modelGeneration = 0
    private var modelSlot: OperationSlot? = null
    private var partialIndex: Int? = null

    fun start() {
        if (started || !running()) return
        started = true
        publish(event(AgentEventType.AGENT_START))
        openTurn()
        if (source.token.cancelled) { cancelRun(); return }
        try {
            config = copier.config(config)
            context = copier.context(context)
            pendingInput = owner.reserveSteering()
            if (continueFromAssistant && pendingInput == null) pendingInput = owner.reserveFollowUps()
            check(!continueFromAssistant || pendingInput != null) { "Cannot continue from assistant without queued input" }
            appendInputs(input)
            prepareRequest()
        } catch (error: Throwable) { fail(error) }
    }

    override fun emit(event: AgentEvent) {
        if (!running() || source.token.cancelled) return
        try { publish(event) } catch (error: Throwable) { fail(error) }
    }

    private fun prepareRequest() {
        if (!checkActive()) return
        val requestContext = PrepareRequestContext()
        requestContext.context = copier.context(context)
        requestContext.model = copier.model(config.model)
        requestContext.thinkingLevel = config.thinkingLevel
        background(Callable { config.hooks.prepareRequest(requestContext, source.token) }, Consumer { update ->
            applyUpdate(update)
            prepareModelRequest()
        })
    }

    private fun prepareModelRequest() {
        if (!checkActive()) return
        val history = copier.messages(context.messages)
        val model = copier.model(config.model)
        val options = copier.streamOptions(config.streamOptions)
        if (options.identity == null) options.identity = JsonObject(emptyMap())
        options.reasoning = if (config.thinkingLevel == ThinkingLevel.OFF) null else config.thinkingLevel
        options.signal = source.token
        background(Callable {
            val transformed = config.hooks.transformContext(history, source.token)
            val converted = config.hooks.convertToLlm(transformed)
            val apiKey = config.hooks.getApiKey(model.provider)
            if (!apiKey.isNullOrEmpty()) options.apiKey = apiKey
            val request = ModelRequest()
            request.model = model
            request.options = options
            request.context = AgentContext()
            request.context.messages = copier.messages(converted)
            request
        }, Consumer { request -> streamModel(request) })
    }

    private fun streamModel(request: ModelRequest) {
        if (!checkActive()) return
        val generation = ++modelGeneration
        partialIndex = null
        val slot = OperationSlot(source.token)
        modelSlot = slot
        val receiver = object : ModelCallback {
            override fun onEvent(event: AssistantMessageEvent) {
                dispatchModel(generation, Runnable { consumeModel(copier.assistantEvent(event), slot) })
            }
            override fun onComplete() {
                dispatchModel(generation, Runnable { fail(IllegalStateException("Provider stream ended without a terminal event")) })
            }
            override fun onFailure(error: Throwable) {
                dispatchModel(generation, Runnable { fail(error) })
            }
        }
        try {
            slot.setFuture(workers.submit {
                try {
                    source.token.throwIfCancelled()
                    slot.setHandle(requireNotNull(config.modelClient).stream(request, receiver))
                } catch (error: Throwable) {
                    if (!source.token.cancelled) receiver.onFailure(error)
                }
            })
        } catch (error: Throwable) { slot.cancel(); fail(error) }
    }

    private fun acceptModel(generation: Int): Boolean = running() && !source.token.cancelled && generation == modelGeneration

    private fun dispatchModel(generation: Int, action: Runnable) {
        if (!acceptModel(generation)) return
        try {
            dispatcher.invoke(Runnable {
                if (acceptModel(generation)) {
                    try { action.run() } catch (error: Throwable) { fail(error) }
                }
            })
        } catch (rejected: RejectedExecutionException) {
            if (acceptModel(generation)) throw rejected
        } catch (interrupted: InterruptedException) {
            if (acceptModel(generation)) throw interrupted
        }
    }

    private fun consumeModel(event: AssistantMessageEvent, slot: OperationSlot) {
        if (event.terminal) {
            val final = requireNotNull(event.finalMessage) { "Terminal provider event has no final assistant message" }
            val message = copier.message(final) as AssistantMessage
            message.thinkingLevel = config.thinkingLevel
            val index = partialIndex
            if (index == null) {
                context.messages = context.messages + message
                messageEvent(AgentEventType.MESSAGE_START, message)
            } else {
                val messages = context.messages.toMutableList()
                messages[index] = message
                context.messages = messages
            }
            messageEvent(AgentEventType.MESSAGE_END, message)
            ++modelGeneration
            modelSlot = null
            slot.finish()
            executeTools(message)
        } else if (event.type == "start") {
            check(partialIndex == null) { "Provider stream emitted duplicate start" }
            val message = requireNotNull(event.partial) { "Start provider event has no partial message" }
            partialIndex = context.messages.size
            context.messages = context.messages + copier.message(message)
            messageEvent(AgentEventType.MESSAGE_START, message)
        } else {
            val index = partialIndex
            val partial = event.partial
            if (index != null && partial != null) {
                val messages = context.messages.toMutableList()
                messages[index] = copier.message(partial)
                context.messages = messages
                val update = event(AgentEventType.MESSAGE_UPDATE)
                update.message = partial
                update.assistantMessageEvent = event
                publish(update)
            }
        }
    }

    private fun executeTools(message: AssistantMessage) {
        if (!checkActive()) return
        if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
            finishTurn(message, ToolBatch())
            return
        }
        val executor = ToolExecutor(context, message, config, source.token, dispatcher, workers, this, object : ToolBatchCallback {
            override fun onComplete(batch: ToolBatch) {
                try {
                    if (checkActive()) finishTurn(message, batch)
                } catch (error: Throwable) { fail(error) }
            }
            override fun onFailure(error: Throwable) { fail(error) }
        })
        executor.start()
    }

    private fun finishTurn(message: AssistantMessage, batch: ToolBatch) {
        if (!checkActive()) return
        currentResults = batch.messages
        val turn = turnContext(message, batch.messages)
        background(Callable { config.hooks.finishTurn(turn, source.token) }, Consumer { decision ->
            if (!checkActive()) return@Consumer
            endTurn(message, batch.messages)
            if (!checkActive()) return@Consumer
            if (message.stopReason == StopReason.ERROR) {
                finish(TaskStatus.FAILED, IllegalStateException(message.errorMessage ?: "Model request failed"))
                return@Consumer
            }
            if (message.stopReason == StopReason.ABORTED) {
                finish(TaskStatus.CANCELLED, null)
                return@Consumer
            }
            if (decision == AgentTurnDecision.END) { finish(TaskStatus.SUCCEEDED, null); return@Consumer }
            pendingInput = owner.reserveSteering()
            val steering = pendingInput
            val needsToolTurn = message.content.any { it is ToolCall } && !batch.terminate
            if (needsToolTurn || steering != null) {
                prepareNextTurn(turnContext(message, batch.messages), steering)
                return@Consumer
            }
            pendingInput = owner.reserveFollowUps()
            val followUps = pendingInput
            if (followUps != null || decision == AgentTurnDecision.CONTINUE) {
                prepareNextTurn(turnContext(message, batch.messages), followUps)
            } else finish(TaskStatus.SUCCEEDED, null)
        })
    }

    private fun prepareNextTurn(previous: AgentTurnContext, pending: PendingInputBatch?) {
        pendingInput = pending
        background(Callable { config.hooks.prepareNextTurn(previous, source.token) }, Consumer { update ->
            applyUpdate(update)
            val messages = update?.messages ?: emptyList()
            if (pendingInput == null) pendingInput = owner.reserveSteering()
            openTurn()
            appendInputs(messages)
            prepareRequest()
        })
    }

    private fun applyUpdate(update: AgentLoopTurnUpdate?) {
        if (update == null) return
        val nextContext = update.context
        if (nextContext != null) {
            context = copier.context(nextContext)
            context.tools = context.tools.map { ConfiguredTool(it) }
        }
        update.model?.let { config.model = copier.model(it) }
        update.thinkingLevel?.let { config.thinkingLevel = it }
    }

    private fun turnContext(message: AssistantMessage, results: List<ToolResultMessage>): AgentTurnContext {
        val turn = AgentTurnContext()
        turn.message = copier.message(message) as AssistantMessage
        turn.toolResults = results.map { copier.message(it) as ToolResultMessage }
        turn.context = copier.context(context)
        turn.newMessages = copier.messages(added)
        return turn
    }

    private fun appendInputs(prepared: List<AgentMessage>) {
        val reservation = pendingInput
        val original = prepared + (reservation?.messages ?: emptyList())
        val messages = transcript.declareToolChanges(context, original)
        val insertion = if (messages.size == original.size) -1 else {
            val first = original.indexOfFirst { it !is SystemMessage }
            if (first < 0) original.size else first
        }
        for (index in messages.indices) {
            if (!checkActive()) return
            val message = messages[index]
            val snapshot = copier.message(message)
            context.messages = context.messages + snapshot
            messageEvent(AgentEventType.MESSAGE_START, snapshot)
            messageEvent(AgentEventType.MESSAGE_END, snapshot)
            val sourceIndex = when {
                insertion < 0 -> index
                index == insertion -> -1
                index > insertion -> index - 1
                else -> index
            }
            if (sourceIndex >= prepared.size) reservation?.acknowledge()
        }
        pendingInput = null
    }

    private fun openTurn() {
        turnOpen = true
        currentResults = emptyList()
        publish(event(AgentEventType.TURN_START))
    }

    private fun endTurn(message: AssistantMessage, results: List<ToolResultMessage>) {
        if (!turnOpen) return
        turnOpen = false
        val end = event(AgentEventType.TURN_END)
        end.message = message
        end.toolResults = results
        publish(end)
    }

    private fun cancelRun() {
        if (!running()) return
        if (!started) { start(); return }
        fail(CancellationException("Operation cancelled"))
    }

    internal fun fail(error: Throwable) {
        if (!running()) return
        val cancelled = source.token.cancelled || error is CancellationException
        ++modelGeneration
        source.cancel()
        pendingInput?.rollback()
        pendingInput = null
        modelSlot?.cancel()
        modelSlot = null
        if (!turnOpen) openTurn()
        val message = AssistantMessage()
        message.api = config.model.api
        message.provider = config.model.provider
        message.model = config.model.id
        message.thinkingLevel = config.thinkingLevel
        message.stopReason = if (cancelled) StopReason.ABORTED else StopReason.ERROR
        message.errorMessage = error.message ?: error.toString()
        message.content = listOf(TextContent(""))
        owner.recordFailure(error)
        try {
            context.messages = context.messages + message
            messageEvent(AgentEventType.MESSAGE_START, message)
            messageEvent(AgentEventType.MESSAGE_END, message)
            endTurn(message, currentResults)
        } catch (reportingFailure: Throwable) {
            RuntimeLog().callbackFailure(reportingFailure)
        } finally {
            finish(if (cancelled) TaskStatus.CANCELLED else TaskStatus.FAILED, error)
        }
    }

    private fun finish(status: TaskStatus, failure: Throwable?) {
        if (!running()) return
        if (status == TaskStatus.SUCCEEDED && source.token.cancelled) {
            fail(CancellationException("Operation cancelled"))
            return
        }
        var outcome = status
        var error = failure
        var result: AgentResult? = null
        val end = event(AgentEventType.AGENT_END)
        var snapshot: AgentEvent
        try {
            end.messages = added.toList()
            snapshot = copier.event(end)
            if (status == TaskStatus.SUCCEEDED) {
                result = AgentResult()
                result.messages = copier.messages(added)
            }
        } catch (snapshotFailure: Throwable) {
            if (status != TaskStatus.CANCELLED) outcome = TaskStatus.FAILED
            error = failure ?: snapshotFailure
            owner.recordFailure(error)
            source.cancel()
            // 单个宿主快照失败不能阻止其余消息和最后事件送达。
            end.messages = availableFinalMessages()
            snapshot = end
        }
        val selected = task.settle(outcome) ?: return
        ++modelGeneration
        modelSlot?.finish()
        modelSlot = null
        try {
            notifyEvent(snapshot)
            owner.release(this)
            when (selected) {
                TaskStatus.SUCCEEDED -> callback.onSuccess(requireNotNull(result))
                TaskStatus.CANCELLED -> callback.onCancelled()
                TaskStatus.FAILED -> callback.onFailure(error ?: IllegalStateException("Agent run failed"))
                else -> {}
            }
        } catch (error: Throwable) {
            RuntimeLog().callbackFailure(error)
        } finally {
            owner.release(this)
            task.complete()
            owner.closeIfIdle()
        }
    }

    private fun availableFinalMessages(): List<AgentMessage> {
        val result = ArrayList<AgentMessage>()
        for (message in added) {
            try { result.add(copier.message(message)) }
            catch (error: Throwable) { RuntimeLog().callbackFailure(error) }
        }
        return result
    }

    private fun running(): Boolean = task.getStatus() == TaskStatus.RUNNING

    private fun checkActive(): Boolean {
        if (!running()) return false
        if (source.token.cancelled) { cancelRun(); return false }
        return true
    }

    private fun event(type: AgentEventType): AgentEvent {
        val event = AgentEvent()
        event.type = type
        return event
    }

    private fun messageEvent(type: AgentEventType, message: AgentMessage) {
        val event = event(type)
        event.message = message
        publish(event)
    }

    private fun publish(event: AgentEvent) {
        val snapshot = copier.event(event)
        owner.commit(event)
        if (event.type == AgentEventType.MESSAGE_END) {
            val message = requireNotNull(event.message)
            added.add(message)
            if (message is ToolResultMessage) {
                context.messages = context.messages + message
                currentResults = currentResults + message
            }
        }
        notifyEvent(snapshot)
    }

    private fun notifyEvent(snapshot: AgentEvent) {
        try { callback.onEvent(snapshot) } catch (error: Throwable) { RuntimeLog().callbackFailure(error) }
    }

    private fun <T> background(operation: Callable<T>, continuation: Consumer<T>) {
        if (!checkActive()) return
        val slot = OperationSlot(source.token)
        try {
            slot.setFuture(workers.submit {
                try {
                    source.token.throwIfCancelled()
                    val result = operation.call()
                    slot.finish()
                    dispatcher.post(Runnable {
                        if (checkActive()) {
                            try { continuation.accept(result) } catch (error: Throwable) { fail(error) }
                        }
                    })
                } catch (error: Throwable) {
                    slot.finish()
                    if (!source.token.cancelled) dispatcher.post(Runnable { fail(error) })
                }
            })
        } catch (error: Throwable) { slot.cancel(); fail(error) }
    }
}
