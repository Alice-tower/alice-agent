package dev.agent.core.model

/** Provider HTTP 响应 Hook，由 adapter 调用。 */
interface ResponseHook { fun onResponse(status: Int, headers: Map<String, List<String>>) }
