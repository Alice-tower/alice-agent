package dev.agent.core.bean

/** 推理内容、签名及脱敏标识。 */
class ThinkingContent : ContentBlock {
    var thinking: String = ""
    var thinkingSignature: String? = null
    var redacted: Boolean = false

    constructor()
    constructor(thinking: String) { this.thinking = thinking }

    override fun snapshot(): ContentBlock = BeanCopier().content(this)
}
