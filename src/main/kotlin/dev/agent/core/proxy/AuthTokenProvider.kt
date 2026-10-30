package dev.agent.core.proxy

/** 每次代理请求前在 IO 工作线程读取凭据；凭据仅用于 HTTP 请求头。 */
interface AuthTokenProvider {
    fun getToken(): String
}
