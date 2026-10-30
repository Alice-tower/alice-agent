package dev.agent.core.proxy

import java.util.ArrayDeque
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 工具参数的增量预览解析器，每个输入字符仅扫描一次。
 *
 * 本类保留尚未闭合的结构和拆开的 Unicode 转义；执行工具时仍须使用
 * toolcall_end 提供的完整参数。本类实例只服务一个工具调用，不支持并发追加。
 */
internal class PartialJsonArguments {
    private val containers = ArrayDeque<PartialJsonNode>()
    private var root: PartialJsonNode? = null
    private var string: PartialJsonStringNode? = null
    private var stringIsKey: Boolean = false
    private var atom: PartialJsonAtomNode? = null
    private var escaped: Boolean = false
    private var unicodeDigits: StringBuilder? = null

    fun append(delta: String): JsonElement {
        for (character in delta) {
            val activeString = string
            if (activeString != null) {
                appendStringCharacter(activeString, character)
                continue
            }
            val activeAtom = atom
            if (activeAtom != null && character != ',' && character != ']' &&
                character != '}' && !character.isWhitespace()) {
                activeAtom.text.append(character)
                continue
            }
            atom = null
            if (character.isWhitespace() || character == ':') {
                continue
            }
            when (character) {
                '{' -> {
                    val node = PartialJsonObjectNode()
                    attach(node)
                    containers.addLast(node)
                }
                '[' -> {
                    val node = PartialJsonArrayNode()
                    attach(node)
                    containers.addLast(node)
                }
                '}', ']' -> {
                    if (!containers.isEmpty()) {
                        containers.removeLast()
                    }
                }
                ',' -> {
                    val parent = containers.peekLast()
                    if (parent is PartialJsonObjectNode) {
                        parent.expectingKey = true
                    }
                }
                '"' -> {
                    val parent = containers.peekLast()
                    stringIsKey = parent is PartialJsonObjectNode && parent.expectingKey
                    val node = PartialJsonStringNode()
                    string = node
                    if (!stringIsKey) {
                        attach(node)
                    }
                }
                else -> {
                    val node = PartialJsonAtomNode()
                    node.text.append(character)
                    atom = node
                    attach(node)
                }
            }
        }
        val currentRoot = root
        if (currentRoot == null) {
            return JsonObject(emptyMap())
        }
        return currentRoot.snapshot()
    }

    private fun attach(node: PartialJsonNode) {
        val parent = containers.peekLast()
        when (parent) {
            is PartialJsonObjectNode -> {
                val key = parent.key
                if (key != null) {
                    parent.fields[key] = node
                    parent.key = null
                }
            }
            is PartialJsonArrayNode -> parent.items.add(node)
            else -> root = node
        }
    }

    private fun appendStringCharacter(active: PartialJsonStringNode, character: Char) {
        val digits = unicodeDigits
        if (digits != null) {
            digits.append(character)
            if (digits.length == 4) {
                val value = digits.toString().toIntOrNull(16)
                if (value != null) {
                    active.text.append(value.toChar())
                }
                unicodeDigits = null
            }
            return
        }
        if (escaped) {
            escaped = false
            when (character) {
                'u' -> unicodeDigits = StringBuilder()
                'n' -> active.text.append('\n')
                'r' -> active.text.append('\r')
                't' -> active.text.append('\t')
                'b' -> active.text.append('\b')
                'f' -> active.text.append('\u000c')
                else -> active.text.append(character)
            }
            return
        }
        if (character == '\\') {
            escaped = true
        } else if (character == '"') {
            val parent = containers.peekLast()
            if (stringIsKey && parent is PartialJsonObjectNode) {
                parent.key = active.text.toString()
                parent.expectingKey = false
            }
            string = null
        } else {
            active.text.append(character)
        }
    }
}
