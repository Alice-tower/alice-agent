package dev.agent.core.model

import kotlinx.serialization.json.JsonElement

/** Provider 请求载荷 Hook，由 adapter 调用。 */
interface PayloadHook { fun onPayload(payload: JsonElement): JsonElement? }
