# 开发约定与测试策略

> 本文汇总仓库内三份「约定型」文档的硬性规则：根目录 `AGENTS.md`（仓库级强制约定）、`clean-app/ARCHITECTURE.md`（分层与写入边界）、`clean-app/STRINGS.md`（UI 文案）。
> 这些规则是**强制**的，不是建议；改动代码前请先通读对应章节。

## 1. 规则来源与优先级

| 文档 | 覆盖范围 |
| --- | --- |
| `AGENTS.md` | XML 禁令、Kotlin 语言约定、Compose 状态边界、动画交互、构建与测试、测试策略、Android API 调研、特权进程异常边界、Git 操作 |
| `clean-app/ARCHITECTURE.md` | `clean-app` 内部分层、目录职责、写入边界、并发模型、新代码放置 |
| `clean-app/STRINGS.md` | UI 文案的维护点、命名、转义与代码生成 |
| `clean-selector/README.md` | 选择器包的公开 API 与快照契约 |
| `skills-lock.json` + `.agents/skills/` | 项目级 skill 的锁定版本 |

## 2. XML 文件禁令

除 Android 平台必须使用 XML 的场景外，**禁止新增 XML 文件**。

| 场景 | 是否允许 XML |
| --- | --- |
| `app_icon`、`service` 等 Android 平台配置 | ✅ 允许 |
| UI、图标及其他能用 Kotlin 表达的实现 | ❌ 禁止新增 drawable / layout 等 XML 资源 |
| 新增图标资源（页面及页面内组件使用） | ❌ 必须以 Kotlin `ImageVector` 定义 |
| 仅 `AndroidManifest.xml` 等平台配置引用的图标及依赖资源 | ✅ 允许 drawable XML |
| 同一图标同时用于平台配置和页面 | ⚠️ 平台配置用 XML，**页面仍必须用 Kotlin `ImageVector`** |
| 无法确定是否属于例外场景 | ⚠️ 必须先向用户确认 |

现状参考：`clean-app/src/main/res/drawable/` 下只有 `ic_capture`、`ic_event_list`、`ic_flash_on`、`ic_http`、`ic_launcher*`、`ic_layers`、`ic_radio_button`、`ic_status` 等平台磁贴/启动图标；页面图标集中在 `li/gkd/app/ui/icon/`，以 Kotlin 声明。

## 3. Kotlin 可见性

- `clean-app` 模块内**禁止使用 `internal`**。因为没有其他模块引用 `clean-app`，对外可见的声明应省略可见性修饰符（即 Kotlin 默认 `public`），仅在需要收窄作用域时使用 `private`。
- 与公开属性一一对应、仅用于收窄可见性或可变性的 `_xxx` backing property **必须改用 Explicit Backing Fields**。不禁止不存在这种直接对应关系的普通私有字段、缓存或生成代码风格命名。
- 未使用的 Lambda 参数占位符 `_` 不受此限制。

Explicit Backing Fields 由全局编译参数 `-XXLanguage:+ExplicitBackingFields` 启用，因此可以写：

```kotlin
val storeFlow: StateFlow<AppStore>
    field = MutableStateFlow(AppStore())
```

## 4. Kotlin 静态初始化

`companion object` 或 `object` 中，由 `object` / `data object` 单例组成的**列表、集合、映射及其排序结果**必须使用 `by lazy` 初始化。

原因：避免 JVM、JS 和 Wasm 上的循环初始化。

```kotlin
// ✅
object Catalog {
    val all by lazy { listOf(Alpha, Beta, Gamma).sortedBy { it.name } }
}

// ❌ 静态初始化阶段直接构造
object Catalog {
    val all = listOf(Alpha, Beta, Gamma).sortedBy { it.name }
}
```

## 5. Kotlin 工具声明

适用于 `clean-app` 的 `util` 包：

| 声明类型 | 规则 |
| --- | --- |
| 跨文件工具函数、共享工具属性（新增或修改） | 必须是**与文件名同名的 `object`** 的成员 |
| 扩展函数、类型声明、仅供文件内部使用的 `private` 实现 | 可以保留为顶级声明 |
| `XxxExt.kt` 文件 | **只允许**放置扩展声明 |
| 普通工具函数和共享工具属性 | 必须移入对应的 `XxxUtils.kt` 或职责明确的同名 `object` |
| Compose 页面、组件及其私有 Composable | 不适用上述工具声明规则 |

## 6. 通用 UI 组件命名

项目自定义、供**跨页面或跨文件复用**的 UI 组件统一使用 `Gk` 前缀 + PascalCase，命名为 `GkAbc`：

- 规则不受组件所在包限制。
- 名称必须描述组件用途；不再使用 `Perf`、`Custom` 等泛化前缀。
- 具有实际语义的词保留，例如 `GkAppIcon`、`GkAppBarTextField`、`GkRuleGroupCard`。
- 单组件文件与组件同名；同一组件族的重载、私有实现和配套声明允许放在同一文件，文件以 `Gk` 开头并描述该组件族。
- 组件专属配置类型使用 `GkAbcDefaults`、`GkAbcColors` 等名称；图标组件使用 `GkIcon`，共享图标集合使用 `GkIcons`。
- **不强制**加 `Gk` 的：页面、页面私有 Composable、Preview、普通工具函数、Modifier 扩展及独立状态管理类型。状态对象的 `Render()` 成员不属于独立组件入口。

## 7. Compose 与状态边界

### 7.1 ViewModel 获取

| 场景 | 规则 |
| --- | --- |
| 主界面 Composable / 页面 ViewModel | 统一通过 `MainViewModel.requireCurrent()` 获取当前 `mainVm`，无需逐层转发导航、全局弹窗、打开 URL 等应用级操作 |
| `LocalMainViewModel` | **不再使用** |
| 注册时机 | 由 `MainActivity` 在权限及 Activity Result 宿主绑定后、创建 Compose 界面前注册；ViewModel 清理时按实例身份清除引用 |
| `requireCurrent()` 适用范围 | 仅限已初始化的主界面调用链；**不得**用于 Service、后台任务或悬浮窗 |
| 实例持有 | 一次操作获取一次并贯穿整个操作；不得在权限等待前后重新获取；不得在静态字段中缓存；该方法不得自行创建替代实例 |
| 路由页面及其私有 Composable | 可以直接获取页面 ViewModel，并处理权限和 Activity Result 等平台 UI 行为 |
| 可复用组件 | **不得**获取页面 ViewModel，只接收所需状态和事件回调 |

### 7.2 状态读取

- 应用级只读 Flow 由**实际消费它的 Composable 直接收集**，不要复制进页面 `UiState` 或 ViewModel。
- 普通 Flow 使用 `collectAsStateWithLifecycle`；Paging 使用专用 API；高频状态放在最小消费子树。
- Service 启停、持久化和其他业务副作用必须由**明确事件触发**，交给 ViewModel / Repository / Store 完成；Composable 不得通过状态监听执行写入。

### 7.3 UiState / UiActions

- `XxxUiState` 和 `XxxUiActions` **只在复用、独立预览或复杂页面契约确有需要时**使用。
- `UiState` 只能表示**不可变页面快照**，不得包含 Flow、Paging 或高频状态。
- 相同映射存在多个构造路径时再提取私有构建函数。
- ViewModel 的可变状态必须为 `private`，只暴露不可变状态和明确的业务方法。
- 只读 `StateFlow` 使用 Explicit Backing Fields；**禁止** `_xxxFlow` / `xxxFlow` 双属性和 `.asStateFlow()`。

### 7.4 纯 UI 状态

- 滚动、焦点、菜单、动画、拖拽和多选等纯 UI 状态留在 Compose。
- 可复用交互逻辑可封装为 `rememberXxxState`，但**不得**访问 ViewModel、数据库、Store、Service 或导航。
- 需要原子一致性的多个字段必须由事实源提供**同一个不可变快照**。
- 业务状态**不得**通过 `CompositionLocal` 传递。

### 7.5 条件渲染

Composable 需要根据条件决定是否输出后续 UI 时，**禁止使用提前 `return`**，必须把 UI 包裹在对应的条件区块中。事件或协程 Lambda 的标记返回不受此限制。

## 8. 状态与副作用

- Room 可观察查询应保持为**冷 `Flow`**：先在 ViewModel 内按页面一致性边界完成聚合，再把最终页面快照转换为 `StateFlow<Loadable<XxxUiState>>`。
  - `Loading` 表示尚未收到完整首发。
  - `Ready(emptyList())` 表示已加载但结果为空。
  - **禁止**用空集合伪装初始值，也禁止用计数器、`attachLoad` 等旁路状态推断多个查询是否加载完成。
- `combine`、`map`、`stateIn` 等产生的派生展示状态**只能用于渲染和临时 UI 同步**，禁止通过 `collect`、`onEach` 或状态 watch 驱动数据库、文件、网络写入以及 Service 启停。
- 持久化和业务副作用必须由明确的用户事件、系统事件或领域方法触发，并在 Repository / Store 中按业务一致性边界完成。允许将单一权威状态同步到幂等外部投影，但同步回调**不得再读取其他状态拼装写入**。
- `debounce`、`conflate`、`collectLatest` 和互斥锁只能控制调度或并发，**不能替代**多状态源的原子更新；需要一致读取的状态应聚合为同一个不可变状态对象。

## 9. UI 交互与过渡动画

- 动画只负责视觉过渡，交互按当前业务或 UI 状态**立即响应**。
  - 禁止因动画未结束、图标变形或旧内容正在退场，给按钮、图标、开关、标题等添加临时禁用态。
  - 禁止等待动画完成、延时解锁或额外点击节流。
  - 也不得改成在点击回调中吞掉操作。
- 禁用交互必须对应**明确的业务前提**，例如没有可操作数据、输入无效或权限不足。普通开关的短暂保存、模式切换或对快速点击的假设，不得成为临时锁定控件、扩大禁用范围的理由；写入一致性在 ViewModel / Repository / Store 中处理。
- 退场重复内容可以从无障碍导航中隐藏，但不得因此改变控件颜色或增加点击等待。
- 相关测试应验证过渡期间的正常点击和状态切换，**不得**将这类临时禁用作为正确行为固化。

## 10. 页面底部留白

- 可滚动页面必须在内容末尾提供统一的额外底部留白：
  - 普通 `Column` 使用 `GkPageBottomSpace()`；
  - `LazyColumn` 使用 `gkPageBottomSpace()` 添加末尾 item；
  - 已有末尾 item 包含空状态等内容时，可在该 item 内使用 `GkPageBottomSpace()`，**不得重复添加**。
- 高度统一由 `GkPageBottomSpaceDefaults` 管理，不再手写页面底部 Spacer 高度。
- 底部留白**必须位于滚动内容内部**，让最后一项可以继续向上滚动；它不替代 Scaffold、系统导航栏或 IME inset 处理，也不得在统一组件中重复叠加已由宿主处理的 inset。

## 11. UI 文案

唯一维护点：`clean-app/src/main/res/values/strings.xml`。

适用对象：用户可见的标题、按钮、副文案、Toast、通知、无障碍描述和校验提示。
**不属于**固定 UI 文案：日志、内部诊断、协议字段、URL、动画调试标签、用户输入。

| 规则 | 说明 |
| --- | --- |
| 命名 | 按用途命名，如 `rule_enable_in_app`、`subscription_disabled`；不加 `gk_` 前缀；无障碍相关键使用 `a11y` |
| 复用 | 复用已有键前应确认语义相同；显示值相同但用途不同可以分别命名 |
| 多语言 | 目前**只支持一套文案**，不做运行时语言切换 |
| 生成 | `generateUiStrings` 从 XML 生成 `li.gkd.app.text.UiStrings`，供 Compose、通知、ViewModel 和纯 Kotlin 规则逻辑共用，无需 Android Context |
| 生成文件位置 | `build/generated/source/uiStrings`，**不要手动修改** |
| 无参数文案 | 生成常量：`UiStrings.rule_enable_in_app` |
| 带参数文案 | 生成函数，参数用连续编号 `%1$s`、`%2$s`：`UiStrings.app_count(apps.size)` |
| 属性 | 普通文案无需声明 `translatable` 或 `formatted` |
| 百分号 | 带参数文案中的字面百分号写成 `%%` |
| 引号 | 普通文案不加外层双引号；仅需保留首尾空格、连续空白时使用引号包裹 |
| 转义 | 换行 `\n`、双引号 `\"`、反斜杠 `\\`；XML 中 `&`、`<` 使用实体转义 |
| 模板变量 | `${i}` 等自定义通知模板变量作为普通文本保留，无需额外属性 |
| `formatted="false"` | 仅当需要把 `%1$s` 等格式符本身作为普通文本显示时声明 |
| `debug_suffix` | 带该属性的平台标签继续通过 `R.string` 获取，以保留构建变体后缀，不生成访问器 |
| 将来多语言 | 应将文案解析切换为 Android 资源机制（`values-xx` + 资源解析），**不能只新增 `values-xx` 目录** |

## 12. 架构分层与放置规则

完整的目录职责、写入边界表、并发模型见 [02-architecture.md](02-architecture.md)。核心纪律：

- Composable 不得直接访问 `Db`、文件或 Service 生命周期。
- ViewModel 可直接使用职责单一的 DAO；不要为形式上的依赖注入把全局唯一 DAO 塞入 ViewModel 构造器。
- Service、Receiver 和无障碍运行时不得引用页面 ViewModel。
- 跨数据源写入和带业务规则的操作交给 Repository / Manager / Service。
- 单表查询/写入直接用 DAO，不新增一对一转发层。
- `domain` 不得依赖 Compose、Activity、Service 或 DAO，并优先写纯函数行为测试。
- `service` 包名承载 Manifest、无障碍服务与快捷设置组件身份，**只迁移调用边界，不修改组件类名**。
- `RawSubscription` 和数据库配置实体仍是历史共享模型；规则汇总已经下沉到 `domain/rule/RuleSummaryBuilder`，新逻辑不要再回填到应用级状态容器。

## 13. 协程与线程

| 规则 | 说明 |
| --- | --- |
| 调度器归属 | 页面操作由 ViewModel 作用域管理，默认从 Main 发起；阻塞文件操作在函数内部切 IO；大量解析/计算在函数内部切 Default |
| 不重复指定 | 调用方不为已经负责线程切换的函数重复指定调度器 |
| Room | 挂起 DAO 使用数据库配置的协程上下文 |
| 一致性 | 线程调度不代替业务一致性；基于旧值的规则配置修改必须经 `RuleGroupConfigService` 在写事务内完成 |
| 临界区 | `A11yState.withTopActivityLock` 必须把查询、判断和更新放在同一临界区，不能只锁最终赋值或换成调用方自身的锁 |
| 设置持久化 | `update`/`replace` 只表示请求被接受；`awaitPersistence` 才表示落盘完成 |
| 转换函数 | 必须无副作用（备份恢复期间可能对恢复状态和回滚状态各计算一次） |
| 取消语义 | 备份读取和解析阶段可以取消；获得订阅写锁后，恢复提交和必要补偿完成后才释放锁 |
| `NonCancellable` | 只覆盖已接受的提交和补偿区间 |
| 应用级作用域 | 只承接明确需要跨页面存活的工作，并提供完成或失败语义；普通操作跟随调用方生命周期 |

## 14. 构建与测试约定

### 14.1 渠道

- **常规测试只编译 `gkd` 渠道**。
- 用户没有明确指令时，**禁止运行任何 `play` 渠道的编译任务**。

### 14.2 界面测试（Instrumentation / Compose UI）

在执行界面测试（真机、模拟器上的 Compose UI / Instrumentation 测试）前，必须：

1. **先记录**应用原有的自动化开关与运行模式；
2. 临时关闭自动化功能（关闭 `enableAutomator` 并退出自动化模式）；
3. 确认设置已生效后再初始化测试，防止应用自动化干扰测试初始化。

测试结束后**必须恢复并核对**原有自动化设置；测试失败或中断时也必须执行恢复，脚本应通过 `finally` 等清理机制保证这一点。恢复时**只还原本次临时修改的字段**，不得用整份旧配置覆盖其他设置。

### 14.3 Git 提交与推送

用户明确要求提交或推送代码时，只执行必要的轻量核对与对应的 Git 操作；**不得**自动扩展为深度代码审查、临时 worktree 隔离验证、全量构建或测试、Release Gate、发布检查等重量级流程。只有用户明确要求对应检查时才允许执行。

## 15. 测试策略

### 15.1 新增测试的判据

新增测试必须**验证可观察行为**，明确：

- 被测输入；
- 预期输出；
- 要防止的具体回归。

优先覆盖：纯函数、边界条件、异常路径、平台或版本兼容差异，以及已修复缺陷的回归场景。

### 15.2 禁止事项

- 禁止仅为增加测试而拆散本应聚合的生产逻辑、扩大声明可见性或暴露测试专用 API。测试必须适配合理的生产设计，而不是反向塑造生产代码。
- 禁止新增**仅复述生产代码静态声明**的测试，包括：
  - 枚举成员、常量取值或集合；
  - 连续编号；
  - 由同一注册表推导出的成员关系；
  - Kotlin 类型系统已经保证的约束。

### 15.3 例外

只有当常量或标识属于**外部协议、持久化格式或跨版本兼容契约**时，才允许为其新增稳定性测试，并在测试名称或注释中说明要保护的兼容行为。

### 15.4 现有测试分布

| 位置 | 文件数 | 覆盖重点 |
| --- | --- | --- |
| `clean-app/src/test/kotlin` | 30 | `domain/rule` 策略、`data` 解析与持久化、`priv` 兼容适配、`snapshot` 文件事务、`util` 工具、`ui/share` 会话状态 |
| `clean-db/src/jvmTest/kotlin` | 2 | Room 迁移（`AppDbMigrationTest`）、订阅配置写事务（`SubscriptionConfigStoreTest`） |
| `clean-selector/src/commonTest` + `jvmTest` + `jsTest` | 12 | 选择器语法、查询、优化、位置、类型、正则契约、冷启动 |
| `androidTest` | 0 | 当前仓库没有 instrumented 测试目录 |

## 16. Android API 调研

涉及 Android framework Java/AIDL API 的源码定位、跨版本签名或可用性比较、API 缺失原因分析，以及 Java hidden-API 访问代码生成时，**必须**使用项目内 `.agents/skills/android-api-diff/SKILL.md`。

- 按该 skill 的路由使用 `android-api-diff` CLI；
- **保留默认 JSON 输出**；
- 不得自行实现或模拟 Android API 版本检查；
- 安装或更新项目级 skill 时，在项目根目录运行 `android-api-diff skill install`。

项目通过 `skills-lock.json` 锁定该 skill 的来源与版本：

```json
"android-api-diff": {
  "source": "android-cs/android-api-diff",
  "ref": "v0.4.1",
  "sourceType": "github",
  "skillPath": "skills/android-api-diff/SKILL.md"
}
```

## 17. 嵌入式 UserService 异常边界

嵌入式 `UserService` 运行在**特权进程**中。调用隐藏 API 等可能失败的 Binder 方法时必须：

1. 在方法**最外层**以末端 `catch (e: Throwable)` 兜住可恢复错误；
2. 通过 Binder 可传输的异常或失败结果，把原始类型、消息和堆栈交给主进程；
3. **不得只在主进程捕获**；
4. **不得**让 `NoSuchMethodError` 等 `LinkageError` 逃逸导致特权进程崩溃。

例外：`VirtualMachineError` 和 `ThreadDeath` 等无法可靠恢复的终止错误**可以原样抛出**，不要把它们伪装成普通业务失败。

实现细节与代码位置见 [07-privilege-layer.md](07-privilege-layer.md)。

## 18. 选择器包的额外契约

`clean-selector` 除上述通用规则外，还受自身 README 中的契约约束（详见 [03-selector-engine.md](03-selector-engine.md)）：

- `getNodeKey` 必须为相等节点返回相等 key、不同逻辑节点返回不等 key，且在一次操作内保持稳定（匹配器用它做回溯 memoization）。
- 一次匹配操作观察**同一个稳定节点快照**；状态变化（包括刷新后的无障碍节点）只在下一次匹配操作可见。
- Kotlin 与 JavaScript 的节点类型都必须非 `null`；`null` 保留给「不存在的父/子」与「匹配或查询失败」。
- 快速查询（fast query）是**正确性敏感**的优化钩子：可以返回误报，但**不得遗漏**候选节点；顺序不做保证。
- `clean-selector` 开启 `explicitApi()`，新增公开声明必须显式标注可见性。

## 19. 关键文件索引

| 文件 | 职责 |
| --- | --- |
| `AGENTS.md` | 仓库级强制约定全文 |
| `clean-app/ARCHITECTURE.md` | 分层、写入边界、并发、新代码放置 |
| `clean-app/STRINGS.md` | UI 文案规范 |
| `clean-selector/README.md` | 选择器包 API 与快照契约 |
| `.agents/skills/android-api-diff/SKILL.md` | Android API 调研流程 |
| `skills-lock.json` | 项目级 skill 锁定 |
| `stability_config.conf` | Compose 稳定性配置 |
| `clean-app/src/main/res/values/strings.xml` | UI 文案唯一维护点 |
| `buildSrc/src/main/kotlin/li/gkd/gradle/GenerateUiStringsTask.kt` | 文案代码生成的实现与校验 |
