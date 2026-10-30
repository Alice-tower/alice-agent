package dev.agent.core.model

import kotlinx.serialization.json.JsonElement

/** Provider 原始事件 Hook，由 adapter 调用。 */
interface ProviderEventHook { fun onEvent(event: JsonElement) }
