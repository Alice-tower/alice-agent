# Alice Agent

一个面向个人长期使用的助手项目，计划支持多平台对话、工具调用、任务执行和记忆管理。

## 当前状态

已建立基本协作与架构约束，并引入 Pi `v1.5.2` 核心源码快照。当前尚未实现助手业务、运行时适配器、数据库或平台接入。

本次引入的是源码基础，不是完整 Pi CLI 或已经可运行的助手。项目尚未配置自己的依赖安装、构建和测试入口；不要在源码子目录单独安装依赖，否则可能从 npm 获取同名 Pi 包而没有使用本地源码。下一步应将本地核心包纳入统一 workspace，再接入最小应用。

## 仓库导航

- 本地 `AGENTS.md`：AI 协作规则，不纳入 Git；其他检出环境可能没有该文件。
- [架构约束](docs/architecture.md)：模块职责、数据归属和执行边界。
- [文档维护规则](docs/documentation.md)：更新时机、内容边界、拆分和澄清原则。
- [Pi 来源记录](vendor/pi/UPSTREAM.md)：固定版本、引入范围和完整性验证。
- [Agent 核心](vendor/pi/packages/agent/README.md)：Agent 循环、工具执行和事件。
- [模型接入](vendor/pi/packages/ai/README.md)：模型供应商和流式接口。

文档维护方式见 [文档维护规则](docs/documentation.md)，本地 AI 协作还遵循 `AGENTS.md`；本文只描述项目入口和当前状态。

## 源码阅读入口

```text
vendor/pi/packages/
  agent/src/agent.ts       Agent 状态与调用入口
  agent/src/agent-loop.ts  模型与工具循环
  agent/src/types.ts       核心消息、事件和工具类型
  ai/src/index.ts          模型接入公共导出
  schema/src/index.ts      Schema 定义与校验
  telemetry/src/index.ts   遥测接口
```

## 初期开发范围

主语言为 TypeScript，开启严格类型检查；采用兼容 Pi 的 Node.js LTS 和统一锁文件。当前 Pi 版本要求 Node.js `>=22.19.0`。

先接通一个入口、一个模型和一个只读工具，保存会话及执行状态，再处理取消、超时、消息去重和回复投递。后续按实际场景增加记忆、其他平台及带副作用的工具。

## 本地数据

应用运行数据统一放在 `data/`，应用运行日志放在 `logs/`；两者不进入 Git。凭据使用环境变量或被忽略的 `.env` 文件。需要配置示例时，提供不含真实凭据的 `.env.example`。

开发期间没有长期价值的临时文件、一次性脚本、截图、调试输出和中间产物统一存放在根目录 `tmp/`，可按用途分子目录。该目录及各级 `AGENTS.md` 不进入 Git，也不应强制添加；新检出环境使用前按需创建 `tmp/`。具有长期维护价值的内容再移入正式目录。

Pi 源码及第三方材料按其各自许可证使用，见 [Pi LICENSE](vendor/pi/LICENSE) 和包内声明。
