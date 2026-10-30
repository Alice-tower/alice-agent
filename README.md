# Kotlin Agent Core

一个 Kotlin/JVM Agent runtime，用于组合模型调用、工具执行、消息历史和任务状态。宿主统一通过 `Agent` 接口使用 core，通过 `ModelClient` 接入模型，通过 `AgentTool` 接入业务工具。

工程采用普通类、JavaBean 数据对象、具名回调和 JDK 线程池。Kotlin 与 Java 都可以直接接入，不需要协程或响应式库；每个文件仅声明一个具名类型。工程约定见 [AGENTS.md](AGENTS.md)。

## 构建和测试

需要 JDK 21，`JAVA_HOME` 指向该 JDK。Kotlin 版本为 2.1.21，构建使用仓库内的 Gradle Wrapper。

```sh
./gradlew test
./gradlew build
```

Windows 使用 `gradlew.bat`。首次运行会下载固定版本的 Gradle 和 Maven 依赖。

## 接入工程

仓库尚未发布 Maven 坐标。可以先放入宿主工程的 `vendor/alice-agent`，作为 Gradle 子项目引入。

宿主 `settings.gradle.kts`：

```kotlin
include(":agent-core")
project(":agent-core").projectDir = file("vendor/alice-agent")
```

宿主使用模块：

```kotlin
dependencies {
    implementation(project(":agent-core"))
    // 宿主需要构造工具 Schema、参数等 JSON 树时添加。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
```

宿主配置 `mavenCentral()`，并保持 Kotlin/JDK 版本一致。

## 统一入口

`AgentConfig` 合并模型、请求参数、工具、初始历史、Hook 和队列策略。模型及 reasoning 只有一处配置来源。

宿主提供 `ModelClient` 实现，并在 `AgentConfig` 中设置模型、工具和运行策略，再由 `AgentFactory.create(config)` 创建实例。提交 `AgentRequest` 时传入 `AgentCallback`，通过返回的 `AgentTask` 管理任务；使用结束后调用 `Agent.close()` 释放资源。

| 入口 | 作用 |
| --- | --- |
| `AgentFactory.create(config)` | 校验配置并创建默认实现 |
| `Agent.run(request, callback)` | 立即返回任务句柄，通过回调报告执行过程 |
| `Agent.continueRun(callback)` | 继续已有历史，或消费待处理输入 |
| `Agent.steer(message)` | 当前整批工具完成后优先处理输入 |
| `Agent.followUp(message)` | 当前任务自然准备停止时处理后续输入 |
| `Agent.getState()` | 读取独立状态快照 |
| `Agent.updateConfig(config)` | 空闲时更新下次任务的配置，保留已有历史和排队输入 |
| `Agent.restoreHistory(messages)` | 空闲时恢复历史副本并清理队列，缺少系统消息时补建基线 |
| `Agent.reset()` | 空闲时清理对话及队列，保留系统基线 |
| `Agent.close()` | 幂等取消活动任务并释放 Agent 自己拥有的资源 |

`AgentRequest` 支持普通文本构造器，也可通过 `messages` 传入消息批次、图片或系统 section 更新。已有历史通过初始 `AgentConfig.messages` 或空闲时的 `restoreHistory()` 恢复；会话持久化由宿主完成。`updateConfig()` 保留已有历史和排队输入，传入配置的 `messages`、`systemPrompt` 仅用于实例初始化，不会隐式替换当前对话。

## 回调与生命周期

`AgentCallback` 提供：

- `onEvent(AgentEvent)`：消息、增量、工具和轮次事件。
- `onSuccess(AgentResult)`：本次任务成功，结果包含本次新增的消息。
- `onFailure(Throwable)`：模型或运行阶段失败。
- `onCancelled()`：任务取消。

可以继承 `AgentCallbackAdapter`，只覆盖所需方法。每次任务只通知一种结束回调，重复或迟到的模型/工具回调会被忽略。普通工具失败作为 `isError` 工具结果交给模型，模型可以继续处理。

同一 Agent 的状态和事件按顺序提交；先更新状态，再通知 `onEvent`。`AGENT_END` 是最后事件，该回调返回后才进入空闲，再调用任务结束回调，因此结束回调中可以启动下一次任务。`AgentTask.await(timeout, unit)` 在结束回调返回后才完成，不能在本任务自己的回调内等待。

事件与任务结束回调在 Agent 的串行调度线程执行。回调应快速返回；界面线程切换由宿主完成。回调抛出的异常会被隔离并记录，不会改变任务结果或阻止资源清理。同一 Agent 的回调共用控制线程，不要在其中等待本任务或该 Agent 的下一任务完成。

同一实例同时只接受一个活动任务；运行中直接再次 `run`、`continueRun`、`reset`、`restoreHistory` 或 `updateConfig` 会被拒绝。运行中的额外输入使用 `steer`/`followUp`，轮次内修改模型或上下文使用准备 Hook。

排队输入在写入历史后才确认消费。下一轮准备期间取消或失败时，尚未提交的输入按原顺序回到队首。清空队列不撤销已选入当前准备阶段的输入，但这些输入随后失败或取消时也不再回队。已经提交的工具结果会同时保留在历史和本次结束事件中。

`AgentTask` 提供任务 ID、状态、`cancel()` 和有超时的 `await()`。取消会传到模型、工具和底层 IO；取消不撤销已经发生的外部操作。任务进入最后事件阶段后，取消不会把已经完成的结果改写成另一种终态。

## 模型与工具

`ModelClient.stream(ModelRequest, ModelCallback)` 返回 `Cancellable`。模型通过 `onEvent` 发送 start、文本/reasoning/工具参数增量，以及携带最终 `AssistantMessage` 的 done/error 事件，之后调用 `onComplete()`；传输或适配异常调用 `onFailure()`。缺少最终事件而直接完成会被 core 判为协议错误。

`ModelCallback.onEvent` 是同步的流量控制边界：返回前 core 会接收并处理该事件。适配器不能持有业务锁调用回调，也不要通过无界任务队列转发增量。

`AgentTool` 提供工具声明、验证前的参数准备和执行方法。继承 `AbstractAgentTool` 可使用默认的参数准备逻辑。执行时接收 `ToolContext`，通过 `ToolCallback` 发送进度、成功或失败，并返回可取消句柄。

工具处理顺序为：参数准备 → JSON Schema 验证 → 执行前 Hook → 工具执行 → 执行后 Hook。验证失败、未知工具、权限拦截和工具异常形成错误结果。`outputSchema` 是输出约定元数据，core 不主动验证输出。

默认并行执行工具：先按模型顺序完成整批 preflight，再并行执行。完成事件按实际完成顺序发出，历史工具结果按模型原调用顺序提交。全局策略为 `SEQUENTIAL`，或同批任一工具要求串行时，整批顺序执行。输出截断时禁止执行工具；只有整批工具结果都声明 `terminate` 才停止自动工具续跑。

## 状态、配置和线程资源

配置传入时生成副本。历史消息、对外事件、工具结果和 `getState()` 返回的数据都与内部状态隔离；修改返回 Bean 不会修改 Agent。模型客户端、工具、Hook 和宿主线程池属于策略或资源，保留引用。自定义 `AgentMessage` 或 `ContentBlock` 必须实现 `snapshot()`，负责复制自己的可变字段。

自定义快照在内部锁外执行。快照失败会使任务失败并释放运行资源；如果结束消息中仍有无法复制的对象，最后事件只包含可复制的消息，并通过失败回调报告原因。

模型 IO、工具和 Hook 在工作线程执行，状态及事件由串行调度器管理。同步 Hook 可以抛异常或检查取消令牌；需要长期等待的实现应响应取消，并避免占满宿主共享工作线程池。

Agent 默认创建自己的工作线程池，也允许注入 `AgentConfig.workerExecutor`。关闭 Agent 只关闭其自己创建的线程资源。宿主注入的共享线程池、模型客户端和工具资源由宿主关闭；`ProxyModelClient` 需要单独 `close()`。

## Hook

继承普通 `AgentHooks` 类并覆盖需要的方法，再设置 `AgentConfig.hooks`。

| 方法 | 时机与用途 |
| --- | --- |
| `prepareRequest` | 包括首轮在内，每次请求前调整上下文、模型和 reasoning |
| `transformContext` | 模型消息转换前裁剪历史或注入检索信息 |
| `convertToLlm` | 转换业务消息，默认只保留四种内置消息 |
| `getApiKey` | 按 Provider 动态取得凭据 |
| `beforeToolCall` | 验证后检查权限或替换可信参数 |
| `afterToolCall` | 改写工具结果、结构化输出和结束标记 |
| `prepareNextTurn` | 确定继续后替换上下文或追加消息 |
| `finishTurn` | 工具结果提交后决定自然调度、继续或结束 |

请求准备先于上下文转换，`transformContext` 先于 `convertToLlm`。准备 Hook 中的模型/reasoning 更新只影响当前任务。`beforeToolCall` 替换参数后不重复验证。`afterToolCall` 只替换正文而未提供新的结构化输出时，旧结构化输出会被清除。

Provider 的 `PayloadHook`、`ResponseHook` 和 `ProviderEventHook` 位于 `StreamOptions`，由实际模型适配器调用；core 只传递这些具名接口。

## HTTP/SSE 代理

`ProxyModelClient` 实现本项目 `/api/stream` JSON/SSE 协议，可直接作为 `ModelClient` 注入。它不是任意模型厂商 API 的通用客户端。

代理在 IO 线程同步发送模型回调；收到 done/error 后立即停止读取，无需等待服务端关闭连接。HTTP 错误、解析错误和缺少终止事件的 EOF 会形成 error 模型事件；取消通过 `onFailure(CancellationException)` 通知，Agent 会将自己的活动任务结算为取消。尚未启动的请求被取消时，会立即在取消调用线程通知并清理，不依赖线程池继续调度；调度被拒绝时也在调用线程通知失败。

代理句柄取消或取消令牌触发时会关闭响应体并取消 HTTP 请求。客户端默认拥有有界 IO 线程池，也支持注入宿主线程池；`close()` 不关闭宿主注入的线程池。

创建 `ProxyModelClient` 时提供代理服务根地址和 `AuthTokenProvider`，将其设置为 `AgentConfig.modelClient`。根地址不带 `/api/stream`、认证信息或 query；模型身份由 `AgentConfig.model` 提供。代理 token 通过 Authorization header 发送，Provider API key、取消令牌及回调不进入请求 JSON。不要在日志中输出凭据。

## 旧接口迁移

本次调整属于公开 API 重构，旧适配器和业务工具需要一起迁移。

| 原入口 | 新入口 |
| --- | --- |
| `Agent(AgentOptions(...))` | `AgentFactory.create(AgentConfig)` |
| `AgentOptions`、`AgentInitialState`、`AgentLoopConfig` | 统一 `AgentConfig` |
| `prompt` + `subscribe` | `run(AgentRequest, AgentCallback)` |
| `abort`、挂起等待 | `AgentTask.cancel()`、有超时的 `AgentTask.await()` |
| 可写 `agent.state` | `getState()` 快照、`updateConfig()` 和 `restoreHistory()` |
| `StreamFn`、完整回复包装函数 | `ModelClient`、`ModelCallback` |
| 工具执行函数和进度函数 | `AgentTool`、`ToolContext`、`ToolCallback` |
| 函数类型 Hook | `AgentHooks` 和 Provider 具名接口 |
| 多套顶层循环入口及进程默认模型注册 | 显式配置模型、通过统一 Agent 入口执行 |
| 顶层 Transcript 函数、单例编解码器 | 普通 `Transcript`、`ProtocolCodec` 类实例 |
| `ProxyStream`、`streamProxy` | `ProxyModelClient` |
| 嵌套事件子类 | `AgentEvent` Bean + `AgentEventType` 枚举 |

JSON/SSE 字段、消息签名、未知厂商元数据及 Transcript 的 section/工具声明回放继续保留。

## License

本项目采用 [MIT License](LICENSE)。第三方依赖由 Gradle 管理，遵循各自许可证。
