package dev.agent.core.bean

import kotlinx.serialization.json.JsonObject

/** Provider 报告的用量及费用，core 不重新计费。 */
class Usage {
    var input: Long = 0
    var output: Long = 0
    var cacheRead: Long = 0
    var cacheWrite: Long = 0
    var totalTokens: Long = 0
    var cost: JsonObject = JsonObject(emptyMap())
}
