# Alice Agent

一个面向个人长期使用的助手项目，计划支持多平台对话、工具调用、任务执行和记忆管理。

## 当前状态

已接通 Electron 对话界面、独立后端 API、Pi Agent 运行时和 OpenAI 兼容 Provider。支持连接认证、模型选择、多轮对话、流式回复、只读时间工具、取消与断线重连；会话和执行状态保存在 SQLite。默认连接本机 Ollama。

后端重启后保留历史，将未完成任务标为中断；当前不自动恢复执行。长期记忆、其他聊天平台、文件与 Shell 工具、桌面安装包和自动更新尚未实现。

四个 Pi 核心包已纳入统一 npm workspace，构建与调用均使用本地源码。依赖安装和构建在项目根执行，不在源码子目录单独安装。

## 开发命令

要求 Node.js `>=22.19.0`，项目声明 npm `12.2.2`。npm 缓存位于被忽略的 `tmp/npm-cache`。

```sh
npm ci --ignore-scripts
npm run setup:desktop
npm run build
npm run check
npm test
```

`setup:desktop` 单独下载 Electron 二进制；首次构建补齐模型目录数据。默认测试使用模拟服务。`npm run test:desktop` 单独启动真实 Electron 窗口，模型仍用模拟实现；真实 Ollama 测试在下面显式启用。

本机启动前确保 Ollama 已运行并提供支持工具调用的模型。在两个终端中分别启动：

```sh
npm run start:backend
npm run start:desktop
```

桌面端首次启动显示独立的后端设置对话框，填写 `http://127.0.0.1:4317` 并从本机 `secrets/access-token` 复制访问令牌，验证连接成功后进入对话主界面。客户端只保存后端连接设置，令牌经系统加密保存；下次启动自动连接，失败时返回设置对话框。会话、消息、模型配置及其他业务数据全部保存在后端。可复制 `.env.example` 为 `.env` 调整后端配置。配置项查阅 `.env.example`；模块职责和关系见 [后端文档](docs/modules/backend.md)。

如需真实模型验证，显式运行相应测试（默认使用本机 Ollama；可用 `OLLAMA_BASE_URL`、`OLLAMA_MODEL`、`OLLAMA_API_KEY` 配置模块级测试连接）：

```sh
ALICE_TEST_OLLAMA=1 npm run test:ollama
ALICE_TEST_OLLAMA=1 node --test test/integration/runtime-ollama.test.ts
ALICE_TEST_OLLAMA=1 node --test test/e2e/desktop-ollama.test.ts
```

桌面测试需要可用桌面会话。当前已在 Mac 上验证，本机模型为 `qwen3.8:27b-mlx`；Windows 与跨设备场景尚未实测。测试产物放在 `tmp/`。

## 仓库导航

- 本地 `AGENTS.md`：AI 协作规则，不纳入 Git；其他检出环境可能没有该文件。
- [架构约束](docs/architecture.md)：模块职责、数据归属和执行边界。
- [文档维护规则](docs/documentation.md)：更新时机、内容边界、拆分和澄清原则。
- [Provider 模块](docs/modules/provider.md)：模型接入能力、边界和关联模块。
- [Agent 运行时](docs/modules/runtime.md)：任务循环能力及与模型、工具、任务编排的关系。
- [后端与存储](docs/modules/backend.md)：网络接入、任务编排、存储和凭据管理的划分。
- [桌面客户端](docs/modules/desktop.md)：前端能力、数据边界与后端接驳关系。
- [Pi 能力导航](docs/pi.md)：当前使用范围、可复用能力及开发前查找入口。
- [Agent 核心](vendor/pi/packages/agent/README.md)：Agent 循环、工具执行和事件。
- [模型接入](vendor/pi/packages/ai/README.md)：模型供应商和流式接口。

文档维护方式见 [文档维护规则](docs/documentation.md)，本地 AI 协作还遵循 `AGENTS.md`；本文只描述项目入口和当前状态。

## 初期开发范围

主语言为 TypeScript，开启严格类型检查；采用兼容 Pi 的 Node.js LTS 和统一锁文件。当前 Pi 版本要求 Node.js `>=22.19.0`。

采用前后端分离，以 Electron 桌面客户端作为产品入口，目标平台为 Windows 和 macOS。当前界面使用 TypeScript、HTML 和 CSS，通过网络接口连接独立后端。日常在 Mac 上开发和联调；Windows 构建及跨设备实测尚未完成。

日常开发、运行和前后端联调在当前 Mac 上完成，客户端通过本机网络接口连接后端。跨设备连接是架构支持目标，Windows 构建和局域网互通在具备运行链路后专项验证，不作为每次本地迭代的前提。

目前已完成本机最小链路。后续按实际场景增加记忆、其他平台及带副作用的工具，保留现有模块契约和接驳边界。

## 本地数据

应用运行数据统一放在 `data/`，应用运行日志放在 `logs/`，凭据统一存放在 `secrets/`；这些目录均不进入 Git。后端访问令牌使用 `secrets/access-token`，默认模型密钥使用 `secrets/llm-api-key`（本机 Ollama 无需填写）。每个凭据文件只存一项值。`.env` 主要保存地址、端口和目录等配置，仍支持用环境变量覆盖凭据；示例文件不含真实凭据。凭据读取逻辑见 `src/secrets/`。

开发期间没有长期价值的临时文件、一次性脚本、截图、调试输出和中间产物统一存放在根目录 `tmp/`，可按用途分子目录。该目录及各级 `AGENTS.md` 不进入 Git，也不应强制添加；新检出环境使用前按需创建 `tmp/`。具有长期维护价值的内容再移入正式目录。

Pi 源码及第三方材料按其各自许可证使用，见 [Pi LICENSE](vendor/pi/LICENSE) 和包内声明。
