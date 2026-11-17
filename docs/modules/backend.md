# 后端与存储

后端作为独立服务运行，承担任务执行和全部业务数据的保存。网络接入、任务编排、存储和凭据管理各有边界，当前由同一后端进程组装。

| 模块 | 主要能力 | 相关模块及原因 |
| --- | --- | --- |
| `backend` | 连接认证、网络请求与事件订阅、应用组装 | 接驳 `api` 契约、`runs` 业务和 Provider 配置，向桌面提供服务 |
| `api` | 定义前后端公共数据契约 | 后端和桌面共同使用，避免泄漏内部对象或供应商类型 |
| `runs` | 基础会话与任务编排、重复请求处理、取消和状态管理 | 调用运行时执行工作，通过 ChatStore 保存状态与历史 |
| `storage` | 保存会话、消息、执行状态和续接历史 | 实现业务所需的持久化接口，隔离 SQLite 等存储细节 |
| `secrets` | 统一读取本地凭据 | 由应用入口向 Provider 等适配器注入所需密钥，业务模块不自行搜索密钥文件 |

## 核心类与入口

| 所属模块 / 核心符号 | 职责与源码 |
| --- | --- |
| `backend` / `createBackendServer` | 创建 HTTP 服务，接驳认证、请求与执行状态订阅；见 [server.ts](../../src/backend/server.ts) |
| `backend` / 应用启动入口 | 读取配置与凭据，组装模型、运行时、工具、存储和服务；见 [main.ts](../../src/backend/main.ts) |
| `api` / 公共 Schema 与类型 | 定义并校验前后端交换的服务、会话、消息和执行数据；见 [contracts.ts](../../src/api/contracts.ts) |
| `runs` / `RunService` | 会话与任务的业务入口，协调去重、运行、取消、事件与持久化；见 [runs/index.ts](../../src/runs/index.ts) |
| `storage` / `ChatStore` | 编排层使用的持久化契约，隔离具体数据库；见 [contracts.ts](../../src/storage/contracts.ts) |
| `storage` / `SqliteChatStore` | 基于 SQLite 实现 `ChatStore`，保存会话、执行和模型续接历史；见 [sqlite.ts](../../src/storage/sqlite.ts) |
| `secrets` / `LocalSecretStore` | 统一访问本地凭据目录，读取密钥并按需创建后端访问令牌；见 [secrets/index.ts](../../src/secrets/index.ts) |

## 关键边界

- 客户端断开连接不会取消后端任务，重新连接可读取已保存状态。当前同一会话串行执行，不隐式排队。
- 对话按流式消息体创建时间排序，状态变化时间与消息创建时间分开。显示消息与模型续接历史分开保存，失败或中断的半截工具过程不进入后续推理。
- 后端重启保留历史并标记未完成任务中断，当前不自动恢复或重放工具。一个数据库只允许一个后端实例持有。
- 当前为单用户服务；访问令牌授予对该后端实例的访问权限。默认在本机开发，跨设备连接需要相应的网络与传输配置。
- 运行数据放在 `data/`，实际凭据集中在被 Git 忽略的 `secrets/`，模型密钥不发送到桌面或模型上下文。

运行和连接方式见 [项目说明](../../README.md#开发命令)，环境配置见 [配置示例](../../.env.example)。网络契约见 [src/api/contracts.ts](../../src/api/contracts.ts)；各模块源码入口为 [backend](../../src/backend/index.ts)、[runs](../../src/runs/index.ts)、[storage](../../src/storage/index.ts)、[secrets](../../src/secrets/index.ts)。接口路径、字段、状态时间与存储机制直接查阅对应源码，验证见 [后端测试](../../test/backend)。
