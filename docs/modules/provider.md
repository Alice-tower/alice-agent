# Provider 模块

Provider 负责模型服务接入，对上提供统一的模型调用能力，对内隔离不同协议和供应商差异。当前接通 OpenAI 兼容 Chat Completions，本机 Ollama 使用同一协议适配器。

主要能力包括服务连接与协议注册、模型发现、完整与流式生成、工具请求解析、取消和错误归一化。Provider 只完成单轮推理，不组装业务上下文、不执行工具，也不管理会话或任务。

## 核心类与入口

| 核心符号 | 职责与源码 |
| --- | --- |
| `ProviderRegistry` | 管理协议适配器与已配置的模型服务，供应用注册和查找连接；见 [registry.ts](../../src/provider/registry.ts) |
| `LlmProvider` | 上层使用的统一模型服务接口，承接模型发现与单轮生成；见 [contracts.ts](../../src/provider/contracts.ts) |
| `ProviderAdapter` | 将某种协议的连接配置转换为 `LlmProvider`，是新增协议的接入契约；见 [contracts.ts](../../src/provider/contracts.ts) |
| `openAIChatAdapter` | 当前 OpenAI 兼容 Chat Completions 协议适配器；见 [openai-chat.ts](../../src/provider/openai-chat.ts) |

## 相关模块

| 模块 | 为什么相关 |
| --- | --- |
| [运行时](runtime.md) | 运行时通过 Provider 请求模型，再根据返回结果推进工具循环 |
| [后端应用](backend.md) | 应用组装服务连接，将配置及凭据注入 Provider，并向客户端提供模型选择能力 |
| `secrets` | 集中管理模型密钥；由应用入口读取并注入，Provider 不自行寻找凭据文件 |
| [Pi AI](../pi.md) | 当前复用其模型协议处理，通过内部转换接入，业务层不依赖 Pi 类型 |

新增模型协议时扩展协议适配器，保持上层调用边界清晰。模型返回的工具请求必须经过工具模块的校验与授权后才能执行；部分输出或达到限制不代表任务成功。

公共入口：[src/provider/index.ts](../../src/provider/index.ts)。数据契约与实现从该入口继续查阅；行为验证见 [Provider 测试](../../test/provider)。运行与真实模型测试入口见 [项目说明](../../README.md#开发命令)。
