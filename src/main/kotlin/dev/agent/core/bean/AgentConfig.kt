package dev.agent.core.bean

import dev.agent.core.model.ModelClient
import dev.agent.core.tool.AgentTool
import dev.agent.core.runtime.AgentHooks
import java.util.concurrent.ExecutorService

/**
 * Agent 的统一配置，提供无参构造及 JavaBean 属性访问方法。
 * 创建和更新时由 core 复制可变配置数据；模型客户端、工具、Hook 和宿主线程池保留引用。
 * 创建后修改此对象不会直接更新 Agent，应在空闲时调用 Agent.updateConfig。
 */
class AgentConfig {
    /**
     * 模型接入客户端，默认未设置；创建或更新 Agent 时必须提供。
     * core 在工作线程调用其 stream 方法，任务取消会传递给返回的可取消句柄。
     * 客户端由宿主管理生命周期，Agent.close 不会关闭它。
     */
    var modelClient: ModelClient? = null

    /**
     * 模型身份及能力描述，默认使用 Model 的初始值；宿主应设置实际模型信息。
     * core 复制并传递这些字段，不自动发现模型；准备 Hook 可为当前任务替换模型。
     */
    var model: Model = Model()

    /**
     * 模型推理级别，默认 OFF；每次请求据此设置 streamOptions.reasoning，覆盖选项中的原值。
     * OFF 表示不附加 reasoning 请求；准备 Hook 可为当前任务调整级别。
     */
    var thinkingLevel: ThinkingLevel = ThinkingLevel.OFF

    /**
     * 构建系统基线使用的提示词，默认不提供文本提示词。
     * 初始化或恢复历史时，首条不是系统消息则按此值和工具声明补建基线。
     * updateConfig 不会据此替换当前历史中的系统提示词。
     */
    var systemPrompt: String? = null

    /**
     * 可供模型调用的业务工具，默认空列表；工具名称必须非空白且互不重复。
     * core 复制工具声明并保留工具实现引用；执行发生在工作线程，工具资源由宿主释放。
     */
    var tools: List<AgentTool> = emptyList()

    /**
     * 创建 Agent 时使用的初始历史，默认空列表；core 会复制消息及其可变内容。
     * 首条不是系统消息时按配置补建系统基线；updateConfig 忽略此字段。
     * 创建后替换历史应在空闲时调用 Agent.restoreHistory。
     */
    var messages: List<AgentMessage> = emptyList()

    /**
     * 模型请求选项，如温度、输出长度、请求头和 Provider Hook，默认使用 StreamOptions 的初始值。
     * 每轮请求使用副本；signal 由当前任务的取消令牌替换，reasoning 由 thinkingLevel 决定。
     * 选项是否被实际采用以及 Provider Hook 的调用由模型适配器负责。
     */
    var streamOptions: StreamOptions = StreamOptions()

    /**
     * 请求准备、上下文转换、工具前后处理及轮次决策 Hook，默认使用 AgentHooks 的默认行为。
     * core 保留此实例引用并在工作线程调用；耗时实现应响应取消令牌。
     */
    var hooks: AgentHooks = AgentHooks()

    /**
     * 整批工具的执行策略，默认 PARALLEL；整批参数准备、验证及执行前 Hook 始终按模型调用顺序进行。
     * 配置为 SEQUENTIAL，或同批任一工具要求串行时，整批串行执行。
     * 并行时完成事件按完成顺序发送，历史结果按模型调用顺序提交。
     */
    var toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL

    /**
     * 优先输入队列的消费策略，默认 ONE_AT_A_TIME，每次取队首一条；ALL 每次取出当前队列全部消息。
     * steer 输入在当前整批工具完成后的轮次调度阶段优先处理；更新配置会同步更新此策略。
     */
    var steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME

    /**
     * 后续输入队列的消费策略，默认 ONE_AT_A_TIME，每次取队首一条；ALL 每次取出当前队列全部消息。
     * followUp 输入在任务自然准备停止时处理；更新配置会同步更新此策略。
     */
    var followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME

    /**
     * 模型调用、工具及 Hook 使用的工作线程池，默认由 Agent 创建并负责关闭。
     * 注入线程池时保留其引用，Agent.close 不会关闭宿主线程池；控制线程仍由 Agent 单独管理。
     * 创建后不能通过 updateConfig 更换线程池。
     */
    var workerExecutor: ExecutorService? = null
}
