package dev.agent.core.bean

/** 文本与不透明的 Provider 签名。 */
class TextContent : ContentBlock {
    var text: String = ""
    var textSignature: String? = null

    constructor()
    constructor(text: String) { this.text = text }
    constructor(text: String, textSignature: String?) { this.text = text; this.textSignature = textSignature }

    override fun snapshot(): ContentBlock = BeanCopier().content(this)
}
