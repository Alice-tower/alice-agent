package dev.agent.core.bean

/** Base64 图片数据与 MIME 类型；data 不包含 Data URI 前缀。 */
class ImageContent : ContentBlock {
    var data: String = ""
    var mimeType: String = ""

    constructor()
    constructor(data: String, mimeType: String) { this.data = data; this.mimeType = mimeType }

    override fun snapshot(): ContentBlock = BeanCopier().content(this)
}
