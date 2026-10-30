package dev.agent.core.bean

import dev.agent.core.model.ModelClient
import dev.agent.core.tool.AgentTool
import dev.agent.core.runtime.AgentHooks
import java.util.concurrent.ExecutorService

/** Agent 的统一配置；创建和更新时由 core 生成快照。 */
class AgentConfig {
    var modelClient: ModelClient? = null
    var model: Model = Model()
    var thinkingLevel: ThinkingLevel = ThinkingLevel.OFF
    var systemPrompt: String? = null
    var tools: List<AgentTool> = emptyList()
    var messages: List<AgentMessage> = emptyList()
    var streamOptions: StreamOptions = StreamOptions()
    var hooks: AgentHooks = AgentHooks()
    var toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL
    var steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME
    var followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME
    var workerExecutor: ExecutorService? = null
}
