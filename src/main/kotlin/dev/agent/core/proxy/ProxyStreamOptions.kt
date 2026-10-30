package dev.agent.core.proxy

import dev.agent.core.bean.StreamOptions

/** 代理连接参数；认证信息不作为 JSON 请求内容发送。 */
class ProxyStreamOptions {
    var authToken: String = ""
    var proxyUrl: String = ""
    var streamOptions: StreamOptions = StreamOptions()
}
