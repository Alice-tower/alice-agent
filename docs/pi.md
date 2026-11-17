# Pi 现有能力导航

`vendor/pi` 是项目已有的代码资产，可按需求直接修改。开发相关功能前从这里定位已有能力，再阅读当前文档、源码及对应测试。本页维护能力与接入状态，不记录来源或原始版本基线；代码历史由 Git 保留。

## 当前使用范围

| 已有包 | 当前接入情况 |
| --- | --- |
| `pi-ai` | Provider 直接复用 OpenAI Chat 协议实现、上下文格式转换和流式消息事件；具体调用与限制见 [Provider 文档](modules/provider.md) |
| `pi-agent-core` | 运行时复用 Agent 循环、工具调度及执行前拦截，经 `streamFn` 接入项目 Provider；行为与验证见 [运行时文档](modules/runtime.md) |
| `pi-schema` | 参与构建，并通过 Pi 工具入口执行参数 Schema 校验；项目请求、配置和接驳数据校验使用 Zod |
| `pi-telemetry` | 作为 Pi AI 依赖参与构建，尚未接入本项目的观测流程 |

底层已有、应用已接通和已完整验证是不同状态。包内具备某项能力，不代表产品已经对外提供。新增、修改或替换能力时同步更新本表与对应文档，详细行为留在所属模块文档。

## 核心类与入口

| 所属包 / 核心符号 | 职责与源码 |
| --- | --- |
| `pi-ai` / `Models`、`createModels` | 模型注册与协议调用入口，定位模型接入能力时从 [models.ts](../vendor/pi/packages/ai/src/models.ts) 查起 |
| `pi-agent-core` / `Agent`、`runAgentLoop` | [Agent](../vendor/pi/packages/agent/src/agent.ts) 封装内存中的 Agent 状态与交互；[runAgentLoop](../vendor/pi/packages/agent/src/agent-loop.ts) 执行模型与工具循环，是项目运行时当前复用的入口 |
| `pi-schema` / `compile`、`Validator` | 编译 Schema 并校验数据，供 Pi 工具参数检查等能力复用；见 [validator.ts](../vendor/pi/packages/schema/src/validator.ts) |
| `pi-telemetry` / `TelemetryContext`、`TelemetryStore` | 分别定义追踪上下文和观测数据查询契约，尚未接入产品；见 [公共接口](../vendor/pi/packages/telemetry/src/index.ts) |

## 按需求查找

以下路径均位于仓库中，可直接检索；使用前核对公共导出和模块契约，跨模块交互仍通过接驳层。

| 要开发的能力 | 优先查看的入口 |
| --- | --- |
| 模型协议、流式文本、推理和工具参数拼接 | [AI 文档](../vendor/pi/packages/ai/README.md)、[协议实现目录](../vendor/pi/packages/ai/src/api)、[AI 测试](../vendor/pi/packages/ai/test) |
| 供应商配置、认证、模型注册与元数据 | AI 文档中的 Custom Providers；[models.ts](../vendor/pi/packages/ai/src/models.ts)、[认证目录](../vendor/pi/packages/ai/src/auth)、[供应商目录](../vendor/pi/packages/ai/src/providers) |
| Agent 循环、启动、继续、取消、运行中追加输入 | [Agent 文档](../vendor/pi/packages/agent/README.md)、[agent.ts](../vendor/pi/packages/agent/src/agent.ts)、[agent-loop.ts](../vendor/pi/packages/agent/src/agent-loop.ts)、[Agent 测试](../vendor/pi/packages/agent/test) |
| 每轮上下文准备、裁剪或转换 | Agent 文档与 [types.ts](../vendor/pi/packages/agent/src/types.ts) 中的 `prepareRequest`、`prepareNextTurn`、`transformContext`、`convertToLlm` |
| 工具执行、串并行、执行前后拦截和循环结束控制 | Agent 类型与循环实现中的 `toolExecution`、`beforeToolCall`、`afterToolCall`、`finishTurn` |
| 工具参数 Schema、编译与校验 | [Schema 文档](../vendor/pi/packages/schema/README.md)、[公共导出](../vendor/pi/packages/schema/src/index.ts)、[AI 工具参数校验](../vendor/pi/packages/ai/src/utils/validation.ts) |
| 流式事件、消息内容及回传历史格式 | [AI 类型](../vendor/pi/packages/ai/src/types.ts)、[事件流工具](../vendor/pi/packages/ai/src/utils/event-stream.ts)、[上下文工具](../vendor/pi/packages/ai/src/utils/transcript.ts) |
| 调用追踪、耗时、指标和观测接驳 | [Telemetry 文档](../vendor/pi/packages/telemetry/README.md)、[公共接口](../vendor/pi/packages/telemetry/src/index.ts)、[Pi AI 观测接入](../vendor/pi/packages/ai/src/telemetry.ts) |

## 复用判断

先检索业务模块和本页列出的代码。核对能力是否满足当前场景，尤其是错误、取消、数据保留和工具副作用语义；对不明确的行为查看对应测试或做小范围验证。

- 已满足：通过本项目接驳层调用，复用现有实现。
- 需要调整：按需求使用扩展接口或直接修改现有实现，同步契约、文档与测试。
- 现有实现不适合：独立实现或替换对应模块，避免为了统一接口静默丢弃数据；不新增决策或修改台账。

Pi Agent 的 `streamFn` 可注入符合其消息事件契约的自定义推理实现。当前运行时已用它接入本项目 Provider，并转换消息、签名和流式事件，因此复用 Agent 循环不要求所有协议都使用 Pi AI。新增协议或消息类型时仍需验证该接驳，具体范围见运行时文档。

## 范围边界

当前仓库不包含 `pi-coding-agent`、`pi-mcp`、`pi-tui` 等其他包。需要文件、Shell 工具或 MCP 能力时，先查找可用实现并评估引入或自行实现的成本，不把这些能力误认为当前已包含。

应用的长期会话、持久任务状态、崩溃恢复、权限、记忆策略和平台投递仍由本项目负责。Pi 的运行中状态、继续执行、内存消息队列或观测接口，不能直接当作这些产品能力已经实现。
