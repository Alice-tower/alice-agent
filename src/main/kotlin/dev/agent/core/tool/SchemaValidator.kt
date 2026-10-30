package dev.agent.core.tool

import dev.agent.core.bean.*

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.math.BigDecimal
import java.math.BigInteger
import java.util.WeakHashMap
import kotlinx.serialization.json.*

/**
 * 工具参数的 JSON Schema validation 与类型转换入口。
 *
 * 先清理不接受 null 的可选字段，再执行有限的 primitive coercion，最后验证。
 * Schema 编译结果使用同步弱引用缓存，避免长期持有动态工具定义。
 */
class SchemaValidator {
    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    private val cache = WeakHashMap<JsonObject, JsonSchema>()

    private fun compiled(schema: JsonObject): JsonSchema = synchronized(cache) {
        cache.getOrPut(schema) { factory.getSchema(mapper.readTree(schema.toString())) }
    }
    private fun valid(value: JsonElement, schema: JsonObject): Boolean =
        runCatching { compiled(schema).validate(mapper.readTree(value.toString())).isEmpty() }.getOrDefault(false)

    /**
     * 验证工具参数，返回经过兼容转换的 JSON 值，不修改原始参数。
     *
     * @param tool 提供参数 Schema 的工具。
     * @param call 原始 Tool call，argumentsError 非 null 时直接拒绝。
     * @return 验证通过的参数。
     * @throws IllegalArgumentException 参数解析失败或不符合 Schema。
     */
    fun validate(tool: ToolDefinition, call: ToolCall): JsonElement {
        require(call.argumentsError == null) { "Invalid arguments for tool \"${tool.name}\": ${call.argumentsError}" }
        val normalized = optionalNulls(call.arguments, tool.parameters)
        val converted = coerce(normalized, tool.parameters)
        val errors = compiled(tool.parameters).validate(mapper.readTree(converted.toString()))
        require(errors.isEmpty()) {
            "Validation failed for tool \"${tool.name}\":\n" + errors.joinToString("\n") { "  - $it" } + "\n\nReceived arguments:\n${call.arguments}"
        }
        return converted
    }

    /**
     * 仅删除 Schema 不接受 null 的可选属性；必填及引用属性保留交给验证器判断。
     */
    private fun optionalNulls(value: JsonElement, schema: JsonObject): JsonElement = when (value) {
        is JsonArray -> {
            val items = schema["items"]
            JsonArray(value.mapIndexed { index, item ->
                val nested = when (items) { is JsonObject -> items; is JsonArray -> items.getOrNull(index) as? JsonObject; else -> null }
                if (nested == null) item else optionalNulls(item, nested)
            })
        }
        is JsonObject -> {
            val properties = schema["properties"] as? JsonObject
            val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            JsonObject(value.mapNotNull { (key, item) ->
                val nested = properties?.get(key) as? JsonObject
                when {
                    nested == null -> key to item
                    item == JsonNull && key !in required && nested["\$ref"] == null && !valid(JsonNull, nested) -> null
                    else -> key to optionalNulls(item, nested)
                }
            }.toMap())
        }
        else -> value
    }

    /**
     * 按照 Schema 分支执行兼容转换；已满足 union 的值保持原 JSON 类型。
     */
    private fun coerce(value: JsonElement, schema: JsonObject): JsonElement {
        var next = value
        (schema["allOf"] as? JsonArray)?.forEach { nested -> if (nested is JsonObject) next = coerce(next, nested) }
        for (kind in listOf("anyOf", "oneOf")) {
            val variants = (schema[kind] as? JsonArray)?.filterIsInstance<JsonObject>() ?: continue
            if (variants.none { valid(next, it) }) {
                variants.asSequence().map { coerce(next, it) to it }.firstOrNull { valid(it.first, it.second) }?.let { next = it.first }
            }
        }
        val types = when (val type = schema["type"]) {
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
        if (!(types.size > 1 && types.any { matches(next, it) })) {
            for (type in types) {
                val candidate = primitive(next, type)
                if (candidate != next) { next = candidate; break }
            }
        }
        if ("object" in types && next is JsonObject) {
            val properties = schema["properties"] as? JsonObject
            val additional = schema["additionalProperties"] as? JsonObject
            next = JsonObject(next.mapValues { (key, item) ->
                val nested = if (properties?.containsKey(key) == true) properties[key] as? JsonObject else additional
                if (nested != null) coerce(item, nested) else item
            })
        }
        if ("array" in types && next is JsonArray) {
            val items = schema["items"]
            next = JsonArray(next.mapIndexed { index, item ->
                val nested = when (items) { is JsonObject -> items; is JsonArray -> items.getOrNull(index) as? JsonObject; else -> null }
                if (nested != null) coerce(item, nested) else item
            })
        }
        return next
    }

    private fun matches(value: JsonElement, type: String): Boolean = when (type) {
        "null" -> value == JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull() != null
        "integer" -> value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
        else -> false
    }

    private fun primitive(value: JsonElement, type: String): JsonElement {
        if (value !is JsonPrimitive) return value
        val text = value.content
        return when (type) {
            "number", "integer" -> {
                val number = when {
                    value == JsonNull -> BigDecimal.ZERO
                    value.booleanOrNull != null && !value.isString -> if (value.boolean) BigDecimal.ONE else BigDecimal.ZERO
                    value.isString && text.isNotBlank() -> parseNumericString(text)
                    else -> null
                }
                if (number != null && (type != "integer" || number.stripTrailingZeros().scale() <= 0)) JsonPrimitive(number) else value
            }
            "boolean" -> when {
                value == JsonNull -> JsonPrimitive(false)
                value.isString && text in listOf("true", "false") -> JsonPrimitive(text == "true")
                !value.isString && text.toBigDecimalOrNull()?.compareTo(BigDecimal.ONE) == 0 -> JsonPrimitive(true)
                !value.isString && text.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 0 -> JsonPrimitive(false)
                else -> value
            }
            "string" -> when {
                value == JsonNull -> JsonPrimitive("")
                !value.isString -> JsonPrimitive(text.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: text)
                else -> value
            }
            "null" -> if (value == JsonPrimitive("") || (!value.isString && text.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 0) || value == JsonPrimitive(false)) JsonNull else value
            else -> value
        }
    }

    /**
     * 解析十进制及常用进制前缀；拒绝转换为非有限浮点数的值。
     */
    private fun parseNumericString(text: String): BigDecimal? {
        val trimmed = text.trim()
        val prefix = trimmed.take(2).lowercase()
        val parsed = runCatching {
            when (prefix) {
                "0x" -> BigInteger(trimmed.drop(2), 16).toBigDecimal()
                "0b" -> BigInteger(trimmed.drop(2), 2).toBigDecimal()
                "0o" -> BigInteger(trimmed.drop(2), 8).toBigDecimal()
                else -> trimmed.toBigDecimal()
            }
        }.getOrNull() ?: return null
        return parsed.takeIf { it.toDouble().isFinite() }
    }
}
