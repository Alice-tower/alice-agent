package dev.agent.core.runtime

import dev.agent.core.api.CancellationToken
import dev.agent.core.bean.*
import dev.agent.core.tool.AgentTool
import dev.agent.core.tool.SchemaValidator
import dev.agent.core.tool.ToolCallback
import java.util.concurrent.ExecutorService

/**
 * 传统回调式工具调度器。控制线程串行提交状态，阻塞准备及 Hook 运行在工作线程。
 * 并行批次先完成全部 preflight，再执行工具；结束事件按完成顺序，历史按调用顺序。
 */
internal class ToolExecutor(
    context: AgentContext,
    assistant: AssistantMessage,
    private val config: AgentConfig,
    private val signal: CancellationToken,
    private val dispatcher: SerialDispatcher,
    private val workers: ExecutorService,
    private val sink: AgentEventSink,
    private val callback: ToolBatchCallback,
) {
    private val copier = BeanCopier()
    private val validator = SchemaValidator()
    private val context = copier.context(context)
    private val assistant = copier.assistantMessage(assistant)
    private val calls = ArrayList<ToolCall>()
    private val executions = ArrayList<ToolExecution>()
    private val orderedMessages = ArrayList<ToolResultMessage>()
    private var sequential = false
    private var remaining = 0
    private var closed = false

    fun start() {
        if (signal.cancelled || closed) return
        for (block in assistant.content) {
            if (block is ToolCall) calls.add(copier.toolCall(block))
        }
        if (calls.isEmpty()) {
            finishBatch()
            return
        }
        if (assistant.stopReason == StopReason.LENGTH) {
            truncatedBatch()
            return
        }
        try {
            sequential = config.toolExecution == ToolExecutionMode.SEQUENTIAL
            for (call in calls) {
                val tool = findTool(call.name)
                if (tool?.getDefinition()?.executionMode == ToolExecutionMode.SEQUENTIAL) sequential = true
            }
            preflight(0)
        } catch (error: Throwable) {
            failBatch(error)
        }
    }

    private fun findTool(name: String): AgentTool? {
        for (tool in context.tools) {
            if (tool.getDefinition().name == name) return tool
        }
        return null
    }

    private fun truncatedBatch() {
        for (call in calls) {
            if (signal.cancelled) return
            emitStart(call)
            val outcome = failed(call, "Tool call \"${call.name}\" was not executed: the response hit the output token limit, so its arguments may be truncated. Re-issue the tool call with complete arguments.")
            emitEnd(outcome)
            orderedMessages.add(commitMessage(outcome))
        }
        finishBatch()
    }

    private fun preflight(index: Int) {
        if (signal.cancelled || closed) return
        if (index == calls.size) {
            if (sequential) finishBatch() else executeParallel()
            return
        }
        val call = calls[index]
        emitStart(call)
        val slot = OperationSlot(signal)
        try {
            val task = workers.submit(Runnable {
                val execution = prepare(call)
                dispatcher.post(Runnable control@ {
                    slot.finish()
                    if (signal.cancelled || closed) return@control
                    executions.add(execution)
                    val outcome = execution.outcome
                    if (outcome != null) {
                        emitEnd(outcome)
                        if (sequential) orderedMessages.add(commitMessage(outcome))
                    }
                    if (sequential && outcome == null) execute(index, execution)
                    else preflight(index + 1)
                })
            })
            slot.setFuture(task)
        } catch (error: Throwable) {
            slot.finish()
            failBatch(error)
        }
    }

    private fun prepare(call: ToolCall): ToolExecution {
        val execution = ToolExecution()
        execution.call = copier.toolCall(call)
        try {
            signal.throwIfCancelled()
            val tool = findTool(call.name)
            if (tool == null) {
                execution.outcome = failed(call, "Tool ${call.name} not found")
                return execution
            }
            val preparedCall = copier.toolCall(call)
            preparedCall.arguments = copier.json(tool.prepareArguments(copier.json(call.arguments)))
            val args = validator.validate(copier.toolDefinition(tool.getDefinition()), preparedCall)
            val before = BeforeToolCallContext()
            before.assistantMessage = copier.assistantMessage(assistant)
            before.toolCall = copier.toolCall(call)
            before.args = copier.json(args)
            before.context = copier.context(context)
            val decision = config.hooks.beforeToolCall(before, signal)
            signal.throwIfCancelled()
            if (decision?.block == true) {
                val outcome = failed(call, decision.reason?.takeIf { it.isNotEmpty() } ?: "Tool execution was blocked")
                outcome.result.terminate = decision.terminate
                execution.outcome = outcome
            } else {
                execution.tool = tool
                execution.args = copier.json(before.args)
            }
        } catch (error: Throwable) {
            execution.outcome = failed(call, error.message ?: error.toString())
        }
        return execution
    }

    private fun executeParallel() {
        if (signal.cancelled || closed) return
        remaining = 0
        for (execution in executions) if (execution.outcome == null) remaining++
        if (remaining == 0) {
            commitParallelMessages()
            return
        }
        for (index in executions.indices) {
            val execution = executions[index]
            if (execution.outcome == null) execute(index, execution)
        }
    }

    private fun execute(index: Int, execution: ToolExecution) {
        if (signal.cancelled || closed) return
        val slot = OperationSlot(signal)
        val start = System.nanoTime()
        val toolCallback = object : ToolCallback {
            override fun onProgress(result: AgentToolResult) {
                if (execution.terminal.get() || signal.cancelled) return
                val snapshot = copier.toolResult(result)
                dispatcher.invoke(Runnable {
                    if (execution.terminal.get() || signal.cancelled || closed) return@Runnable
                    val event = AgentEvent()
                    event.type = AgentEventType.TOOL_EXECUTION_UPDATE
                    event.toolCallId = execution.call.id
                    event.toolName = execution.call.name
                    event.args = copier.json(execution.call.arguments)
                    event.partialResult = snapshot
                    sink.emit(event)
                })
            }

            override fun onSuccess(result: AgentToolResult) {
                if (!execution.terminal.compareAndSet(false, true)) return
                try {
                    finishExecution(index, execution, copier.toolResult(result), start, slot)
                } catch (error: Throwable) {
                    finishExecution(index, execution, ToolResults().error(error.message ?: error.toString()), start, slot)
                }
            }

            override fun onFailure(error: Throwable) {
                if (!execution.terminal.compareAndSet(false, true)) return
                finishExecution(index, execution, ToolResults().error(error.message ?: error.toString()), start, slot)
            }
        }
        try {
            val task = workers.submit(Runnable {
                try {
                    signal.throwIfCancelled()
                    val toolContext = ToolContext()
                    toolContext.toolCallId = execution.call.id
                    toolContext.arguments = copier.json(execution.args)
                    toolContext.signal = signal
                    val handle = requireNotNull(execution.tool).execute(toolContext, toolCallback)
                    slot.setHandle(handle)
                } catch (error: Throwable) {
                    toolCallback.onFailure(error)
                }
            })
            slot.setFuture(task)
        } catch (error: Throwable) {
            slot.finish()
            failBatch(error)
        }
    }

    private fun finishExecution(
        index: Int,
        execution: ToolExecution,
        result: AgentToolResult,
        start: Long,
        slot: OperationSlot,
    ) {
        val duration = (System.nanoTime() - start) / 1_000_000
        dispatcher.post(Runnable {
            slot.finish()
            if (signal.cancelled || closed) return@Runnable
            afterExecution(index, execution, result, duration)
        })
    }

    private fun afterExecution(index: Int, execution: ToolExecution, result: AgentToolResult, duration: Long) {
        val slot = OperationSlot(signal)
        try {
            val task = workers.submit(Runnable {
                val outcome = AgentToolCallOutcome()
                outcome.toolCall = copier.toolCall(execution.call)
                outcome.durationMs = duration
                try {
                    var finalResult = result
                    signal.throwIfCancelled()
                    val afterContext = AfterToolCallContext()
                    afterContext.assistantMessage = copier.assistantMessage(assistant)
                    afterContext.toolCall = copier.toolCall(execution.call)
                    afterContext.args = copier.json(execution.args)
                    afterContext.result = copier.toolResult(result)
                    afterContext.isError = result.isError
                    afterContext.context = copier.context(context)
                    val after = config.hooks.afterToolCall(afterContext, signal)
                    signal.throwIfCancelled()
                    if (after != null) finalResult = applyAfter(result, after)
                    outcome.result = copier.toolResult(finalResult)
                    outcome.isError = outcome.result.isError
                } catch (error: Throwable) {
                    outcome.result = ToolResults().error(error.message ?: error.toString())
                    outcome.isError = true
                }
                dispatcher.post(Runnable control@ {
                    slot.finish()
                    if (signal.cancelled || closed) return@control
                    execution.outcome = outcome
                    emitEnd(outcome)
                    if (sequential) {
                        orderedMessages.add(commitMessage(outcome))
                        preflight(index + 1)
                    } else {
                        remaining--
                        if (remaining == 0) commitParallelMessages()
                    }
                })
            })
            slot.setFuture(task)
        } catch (error: Throwable) {
            slot.finish()
            failBatch(error)
        }
    }

    private fun applyAfter(original: AgentToolResult, after: AfterToolCallResult): AgentToolResult {
        val result = copier.toolResult(original)
        val content = after.content
        if (content != null) {
            result.content = copier.contents(content)
            result.structuredContent = null
        }
        val details = after.details
        if (details != null) result.details = copier.json(details)
        val structured = after.structuredContent
        if (structured != null) result.structuredContent = copier.json(structured)
        val usage = after.usage
        if (usage != null) result.usage = copier.usage(usage)
        val isError = after.isError
        if (isError != null) result.isError = isError
        val terminate = after.terminate
        if (terminate != null) result.terminate = terminate
        return result
    }

    private fun commitParallelMessages() {
        if (signal.cancelled || closed) return
        for (execution in executions) orderedMessages.add(commitMessage(requireNotNull(execution.outcome)))
        finishBatch()
    }

    private fun commitMessage(outcome: AgentToolCallOutcome): ToolResultMessage {
        val message = ToolResultMessage()
        message.toolCallId = outcome.toolCall.id
        message.toolName = outcome.toolCall.name
        message.content = copier.contents(outcome.result.content)
        message.details = copier.json(outcome.result.details)
        message.isError = outcome.isError
        message.usage = outcome.result.usage?.let { copier.usage(it) }
        message.durationMs = outcome.durationMs
        val start = AgentEvent()
        start.type = AgentEventType.MESSAGE_START
        start.message = copier.toolResultMessage(message)
        sink.emit(start)
        val end = AgentEvent()
        end.type = AgentEventType.MESSAGE_END
        end.message = copier.toolResultMessage(message)
        sink.emit(end)
        return message
    }

    private fun emitStart(call: ToolCall) {
        val event = AgentEvent()
        event.type = AgentEventType.TOOL_EXECUTION_START
        event.toolCallId = call.id
        event.toolName = call.name
        event.args = copier.json(call.arguments)
        sink.emit(event)
    }

    private fun emitEnd(outcome: AgentToolCallOutcome) {
        val event = AgentEvent()
        event.type = AgentEventType.TOOL_EXECUTION_END
        event.toolCallId = outcome.toolCall.id
        event.toolName = outcome.toolCall.name
        event.result = copier.toolResult(outcome.result)
        event.isError = outcome.isError
        event.durationMs = outcome.durationMs
        sink.emit(event)
    }

    private fun failed(call: ToolCall, message: String): AgentToolCallOutcome {
        val result = AgentToolCallOutcome()
        result.toolCall = copier.toolCall(call)
        result.result = ToolResults().error(message)
        result.isError = true
        return result
    }

    private fun finishBatch() {
        if (closed || signal.cancelled) return
        closed = true
        val result = ToolBatch()
        val messages = ArrayList<ToolResultMessage>()
        for (message in orderedMessages) messages.add(copier.toolResultMessage(message))
        result.messages = messages
        result.terminate = executions.isNotEmpty()
        for (execution in executions) {
            if (execution.outcome?.result?.terminate != true) result.terminate = false
        }
        callback.onComplete(result)
    }

    private fun failBatch(error: Throwable) {
        if (closed || signal.cancelled) return
        closed = true
        callback.onFailure(error)
    }
}
