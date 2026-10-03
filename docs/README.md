# GKD 项目文档

本目录是对 [GKD](https://gkd.li/) 仓库的完整技术文档，面向**新加入的开发者**、**规则/订阅作者**与**代码审阅者**。

- 分析对象：`gkd-main` 仓库快照 `71e57de`
- 应用版本：`1.12.1`（`versionCode 92`）
- 选择器包版本：`@gkd-kit/selector@0.6.0`
- 许可：GPL-3.0-only

> 所有结论均取自仓库内源码、构建脚本与 schema 文件，不引用外部资料。文中标注的路径均为**仓库相对路径**（使用正斜杠）。

## 阅读路线

### 我想快速了解这个项目

1. [01-overview.md](01-overview.md) — 定位、核心概念、平台与规模
2. [02-architecture.md](02-architecture.md) — 模块划分、分层、数据流

### 我要写规则 / 选择器

1. [03-selector-engine.md](03-selector-engine.md) — 选择器语法、匹配语义、类型校验
2. [05-subscription-and-rules.md](05-subscription-and-rules.md) — 订阅与规则层级、启用策略
3. [04-automation-runtime.md](04-automation-runtime.md) — 规则在运行时如何被匹配与执行

### 我要改代码

1. [10-conventions.md](10-conventions.md) — **先读这份**，其中的规则是强制的
2. [02-architecture.md](02-architecture.md) — 新代码该放哪里
3. [09-build-and-release.md](09-build-and-release.md) — 构建、变体、CI

### 我负责某个子系统

| 子系统 | 文档 |
| --- | --- |
| 选择器解析与匹配（KMP + npm） | [03-selector-engine.md](03-selector-engine.md) |
| 无障碍 / 自动化运行时、系统组件、通知、快照捕获 | [04-automation-runtime.md](04-automation-runtime.md) |
| 订阅模型、规则层级、启用策略 | [05-subscription-and-rules.md](05-subscription-and-rules.md) |
| Room 数据库、设置、备份、日志 | [06-data-layer.md](06-data-layer.md) |
| 特权进程、Shizuku、隐藏 API 适配 | [07-privilege-layer.md](07-privilege-layer.md) |
| Compose 导航、页面地图、组件规范 | [08-ui-layer.md](08-ui-layer.md) |

## 文档清单

| 文档 | 内容摘要 |
| --- | --- |
| [01-overview.md](01-overview.md) | 项目定位、三大支柱概念、运行前提、平台矩阵、仓库结构与代码规模、技术栈速览 |
| [02-architecture.md](02-architecture.md) | 5 个 Gradle 模块、`clean-app` 内部分层、启动流程、规则执行与订阅解析数据流、状态与写入边界、并发模型、跨模块契约 |
| [03-selector-engine.md](03-selector-engine.md) | `clean-selector` 的公开 API、语法、属性与值表达式、关系与逻辑操作符、匹配引擎、快速查询、类型校验、匹配轨迹、npm 发布 |
| [04-automation-runtime.md](04-automation-runtime.md) | `A11yRuntime` / `A11yState` / `A11yRuleEngine`、Service 与磁贴清单、通知体系、保活悬浮窗、截图与快照、HTTP 服务、三类日志 |
| [05-subscription-and-rules.md](05-subscription-and-rules.md) | 订阅输入解析、`RawSubscription` 模型、解析汇总、启用/排除策略、写事务一致性、订阅持久化、相关数据表与页面 |
| [06-data-layer.md](06-data-layer.md) | Room 表结构、DAO、schema 演进与迁移、设置存储语义、`Loadable`、快照存储、备份格式与回滚、并发与测试 |
| [07-privilege-layer.md](07-privilege-layer.md) | 三条特权路径、`UserService` Binder 接口、跨版本 `Compat*` 适配、隐藏 API 存根与 remap、Shizuku 集成、权限协调、异常边界 |
| [08-ui-layer.md](08-ui-layer.md) | 技术栈、启动与 Navigation3 导航、页面清单、状态管理契约、`Gk` 组件族、底部留白、动画规范、文案与图标规范 |
| [09-build-and-release.md](09-build-and-release.md) | 工具链版本、SDK 配置、全局编译参数、变体与签名、`buildSrc` 代码生成、构建命令、CI 工作流与发布流程 |
| [10-conventions.md](10-conventions.md) | XML 禁令、Kotlin 语言约定、Compose 状态边界、动画交互、文案规范、协程约定、构建与测试纪律、测试策略、Android API 调研、特权进程异常边界 |

## 仓库内的其它权威文档

以下文件是文档的上游来源，修改时应保持同步：

| 文件 | 说明 |
| --- | --- |
| [`README.md`](../README.md) | 项目门面：简介、免责声明、安装、截图、订阅与选择器入口 |
| [`AGENTS.md`](../AGENTS.md) | 仓库级强制编码约定 |
| [`clean-app/ARCHITECTURE.md`](../clean-app/ARCHITECTURE.md) | `clean-app` 分层与写入边界基线 |
| [`clean-app/STRINGS.md`](../clean-app/STRINGS.md) | UI 文案维护规范 |
| [`clean-selector/README.md`](../clean-selector/README.md) | 选择器包的公开 API 与快照契约 |
| [`CHANGELOG.md`](../CHANGELOG.md) | 更新内容（同时作为 GitHub Release 的 body） |
| [`.agents/skills/android-api-diff/SKILL.md`](../.agents/skills/android-api-diff/SKILL.md) | Android framework API 跨版本调研流程 |

## 维护约定

- 本目录文档描述的是**当前快照**的行为；改动代码时请同步更新受影响的文档。
- 文档中的断言应可追溯到具体文件；新增断言时请附上仓库相对路径。
- Mermaid 图使用 GitHub 原生支持的 `flowchart` / `sequenceDiagram` / `classDiagram` 语法。
- 不在文档中复制大段源码；用表格与短段落说明结构，必要时给出可追溯的文件路径。
