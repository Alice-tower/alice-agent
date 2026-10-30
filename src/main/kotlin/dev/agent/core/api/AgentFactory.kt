package dev.agent.core.api

import dev.agent.core.bean.AgentConfig
import dev.agent.core.runtime.DefaultAgent

/** 检查配置并创建默认 Agent 实现。 */
class AgentFactory {
    fun create(config: AgentConfig): Agent = DefaultAgent(config)
}
