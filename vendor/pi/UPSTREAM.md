# Pi 核心源码来源

## 固定基线

- 上游仓库：https://github.com/earendil-works/pi
- 原仓库地址 `https://github.com/badlogic/pi-mono` 已重定向到上述仓库。
- 版本：`1.5.2`。
- 获取日期：2026-11-17。
- 来源分支：下载时的 `stable`，后续不自动跟随。
- 固定提交：[`b4afdddf44e059c67ccf5097e5317d913521b470`](https://github.com/earendil-works/pi/tree/b4afdddf44e059c67ccf5097e5317d913521b470)。
- 许可证：[MIT](LICENSE)；各包中的额外版权与第三方声明一并保留。

本目录是纳入当前项目版本控制的源码快照，不是 Git 子模块，也不包含嵌套 Git 仓库。

## 引入范围

| 上游路径 | 用途 |
| --- | --- |
| `packages/agent/` | Agent 循环、状态、工具执行、事件与对应测试 |
| `packages/ai/` | 模型供应商、流式接口、模型目录生成器与对应测试 |
| `packages/schema/` | Agent 和模型接入所需的 Schema 定义与校验 |
| `packages/telemetry/` | 模型接入所需的遥测接口与工具 |
| `tsconfig.base.json` | 上游共享 TypeScript 构建配置 |
| `vitest.base.ts` | 上游共享测试配置 |
| `scripts/package-files.mjs` | 包构建脚本使用的文件操作工具 |
| `LICENSE` | 上游许可证 |

上述路径的 Git 跟踪文件原样保留，共 782 个。四个包的上游内部运行依赖均包含在快照中；第三方 npm 依赖尚未安装。

未引入完整的 CLI、TUI、编码助手、其他应用和上游发布设施。上游根 `package.json` 与锁文件针对整个 monorepo，因此未照搬为本项目配置。包内 README、变更历史和测试材料是上游资料，不属于本项目额外维护的文档体系；其中涉及未引入组件的链接或命令仅供参考。

## 使用与验证

当前快照供后续源码开发使用，尚未配置独立安装和运行环境。首次接入时应在项目根建立 workspace，将这四个包解析到本地源码，固定依赖与锁文件，并按 `telemetry → schema → ai → agent` 顺序构建。

上游未将 `packages/ai/src/providers/data/` 模型数据纳入 Git。首次运行相关构建或测试前，需要补齐与固定版本匹配的数据并通过包内 `check:model-data` 检查；不要把“源码下载完成”当作“已经能够调用模型”。上游目录生成器访问外部模型目录，数据应单独验证，避免静默更新已固定的源码。

在项目根执行以下命令，可核对下载的原始文件：

```sh
cd vendor/pi
shasum -a 256 -c upstream-files.sha256
```

`upstream-files.sha256` 是引入时的原始基线；本项目添加的本文件及校验清单自身不在清单中。本地修改后，对应条目不匹配属于预期差异，不应重新生成清单来掩盖这些修改。升级上游版本时再更新基线。

本次仅校验源码文件与固定提交一致、运行依赖范围及本项目文档链接；尚未安装 npm 依赖、运行上游测试或进行真实模型调用。

## 本地修改

暂无。上游文件保持原样，本项目仅添加来源说明和原始文件校验清单。
