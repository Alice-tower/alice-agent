package dev.agent.core.bean

/** 模型推理级别；OFF 不附加 reasoning 请求。 */
enum class ThinkingLevel(val wire: String) {
    OFF("off"), MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high"),
    XHIGH("xhigh"), MAX("max")
}
