# Agent 运行时

运行时负责一次 Agent 任务中的模型与工具循环，复用 Pi Agent Core，并通过项目公共契约接入模型服务。它不拥有数据库、长期会话、客户端连接或崩溃恢复。

主要能力包括执行启动、流式输出与工具进度、每轮上下文准备、工具参数与授权检查、取消和执行预算控制，以及生成完整的续接历史。

## 核心类与入口

| 所属模块 / 核心符号 | 职责与源码 |
| --- | --- |
| `runtime` / `AgentRuntime` | 对外执行契约，以事件流返回单次任务的进度和结果；见 [contracts.ts](../../src/runtime/contracts.ts) |
| `runtime` / `createPiRuntime` | 创建基于 Pi 循环的运行时，接驳项目 Provider、工具及执行控制；见 [pi-runtime.ts](../../src/runtime/pi-runtime.ts) |
| `runtime` / `RuntimeTool` | 工具接入运行时的公共契约，组合模型可见定义与实际执行能力；见 [contracts.ts](../../src/runtime/contracts.ts) |
| `tools` / `createBackendTimeTool` | 创建当前只读时间工具，查询后端机器时间；新增实际工具从 [tools/index.ts](../../src/tools/index.ts) 查起 |

## 相关模块

| 模块 | 为什么相关 |
| --- | --- |
| [Provider](provider.md) | 提供单轮模型推理，运行时决定何时继续调用 |
| `tools` | 提供实际可执行能力，运行时负责将工具接入循环并检查本次授权 |
| `runs` | 编排任务并消费运行事件，将状态和历史交给存储；运行时不直接持久化 |
| [Pi Agent Core / Schema](../pi.md) | 复用循环、工具调度及参数校验，接驳层隔离其类型 |

工具可见不等于获得执行授权。当前工具按顺序执行，校验、授权或执行失败后阻止同批剩余调用。取消与达到限制均不是成功，取消等待也不等于撤销已发生的外部动作。

运行时提供完整消息与工具结果供调用方续接；这不代表产品已具备崩溃后自动恢复或安全重放工具的能力。

公共入口：[src/runtime/index.ts](../../src/runtime/index.ts)。工具入口：[src/tools/index.ts](../../src/tools/index.ts)，当前提供只读后端时间工具。具体契约、执行机制和验证分别查阅入口源码及 [运行时测试](../../test/runtime)。
