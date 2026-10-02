# 构建与发布

> 本文档基于仓库快照 `71e57de`。所有版本号、任务名、环境变量名均取自仓库内构建脚本。

## 1. 工具链

| 组件 | 要求 | 来源 |
| --- | --- | --- |
| JDK | 21（CI 使用 Temurin 21） | `.github/workflows/*.yml` |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | 同上 |
| KSP | 2.3.12 | 同上 |
| Node.js | `devEngines` 声明 26.9.0（`onFail: warn`） | `package.json` |
| pnpm | `devEngines` 声明 12.5.1（`onFail: error`） | `package.json` |
| 选择器 npm 包运行要求 | Node.js ≥ 22 或支持 WebAssembly GC 的浏览器 | `gkd-selector/package.json`、`gkd-selector/README.md` |

`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向本地文件 `file:///E:/anzhuang/gradle-9.7.1-bin.zip`，这是随仓库分发的环境定制；在标准克隆中应还原为官方 distribution URL。

`gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx4g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -XX:+UseParallelGC -XX:MaxMetaspaceSize=2g
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
```

## 2. 版本与 SDK 配置

根 `build.gradle.kts` 的 `Cfg` 对象是唯一的 SDK 事实源，通过 `subprojects {}` 统一应用到所有子项目：

| 项 | 值 |
| --- | --- |
| `compileSdk` | 37 |
| `buildToolsVersion` | `37.0.0` |
| `minSdk` | 26 |
| `targetSdk` | 37（跟随 `compileSdk`） |
| Java source/target | 11 |
| Kotlin jvmTarget | 11 |

### 2.1 全局 Kotlin 编译参数

```kotlin
-opt-in=kotlin.RequiresOptIn
-opt-in=kotlin.contracts.ExperimentalContracts
-opt-in=kotlinx.coroutines.FlowPreview
-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi
-opt-in=kotlinx.serialization.ExperimentalSerializationApi
-opt-in=androidx.compose.material3.ExperimentalMaterial3Api
-opt-in=androidx.compose.foundation.ExperimentalFoundationApi
-opt-in=androidx.compose.ui.ExperimentalComposeUiApi
-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi
-XXLanguage:+MultiDollarInterpolation
-XXLanguage:+ExplicitBackingFields
```

`-XXLanguage:+ExplicitBackingFields` 正是仓库约定「只读 `StateFlow` 必须使用 Explicit Backing Fields」的编译器支撑（见 [10-conventions.md](10-conventions.md)）。

项目还配置了 Compose 稳定性配置文件 `stability_config.conf`：

```text
li.gkd.*
kotlin.collections.*
```

`composeCompiler { stabilityConfigurationFiles.addAll(...) }` 使编译器把这两组类型视为稳定类型，减少无谓重组。设置 Gradle 属性 `-PcomposeReports` 可开启 Compose 编译器报告（输出到 `build/compose_compiler`）。

### 2.2 版本目录

`gradle/libs.versions.toml` 集中声明 versions / libraries / plugins。仓库同时应用了 `nl.littlerobots.version-catalog-update` 插件，其更新产物 `.gitignore` 已忽略：

```text
libs.versions.updates.toml
```

## 3. 模块与插件矩阵

| 模块 | 插件 | 关键配置 |
| --- | --- | --- |
| 根项目 | `google.ksp`、`android.library`、`android.kotlin.multiplatform.library`、`android.application`、`androidx.room`、`kotlin.serialization`、`kotlin.multiplatform`、`kotlin.parcelize`、`kotlin.compose`、`remap`、`codeorigin`（均 `apply false`）+ `littlerobots.version` | `Cfg`、全局编译参数、子项目 SDK |
| `gkd-app` | `android.application`、`kotlin.parcelize`、`kotlin.serialization`、`kotlin.compose`、`remap`、`codeorigin` | 见第 4 节 |
| `gkd-db` | `kotlin.multiplatform`、`android.kotlin.multiplatform.library`、`androidx.room`、`kotlin.serialization`、`google.ksp` | targets `android` + `jvm`；`room3 { schemaDirectory("$projectDir/schemas") }`；`kspAndroid`/`kspJvm` 使用 Room compiler |
| `gkd-selector` | `kotlin.multiplatform`、`kotlin.serialization` | `explicitApi()`；targets `jvm` + `js(es2015, ESM, nodejs, 生成 TS 声明)` |
| `gkd-hidden-api` | `android.library` | namespace `hidden.api`；`compileOnly` remap-annotation；`annotationProcessor` remap-processor |

`gkd-db` 的测试任务会把 schema 目录作为系统属性传给测试 JVM，供 Room 迁移测试使用：

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("room.schemaDirectory", layout.projectDirectory.dir("schemas").asFile.absolutePath)
}
```

## 4. gkd-app 变体与签名

### 4.1 基本信息

| 项 | 值 |
| --- | --- |
| `namespace` | `li.gkd.app` |
| `applicationId` | `li.songe.gkd` |
| `versionCode` | 92 |
| `versionName` | `1.12.1` |
| `buildFeatures` | `compose`、`aidl`、`resValues` 均为 true |
| `ndk.abiFilters` | `arm64-v8a`、`x86_64` |
| `androidResources.localeFilters` | `zh-rCN`、`en` |
| `packaging.jniLibs.useLegacyPackaging` | true（priv-kit 要求） |
| `dependenciesInfo.includeInApk/Bundle` | false（配合 HiddenApiBypass） |

> `localeFilters` 只是资源裁剪范围；项目当前**只维护一套文案**，不做运行时语言切换（见 `gkd-app/STRINGS.md`）。

### 4.2 渠道（productFlavors，dimension `channel`）

| 渠道 | `isDefault` | `is_accessibility_tool` | 说明 |
| --- | --- | --- | --- |
| `gkd` | 是 | `true` | 官网 / GitHub 分发，支持更新检查 |
| `play` | 否 | `false` | Google Play 分发，不含更新检查 |

所有渠道都会写入 `manifestPlaceholders["channel"] = name`。

### 4.3 构建类型

| buildType | 说明 |
| --- | --- |
| `release` | `isMinifyEnabled = true`、`isShrinkResources = true`、非 debuggable，使用 `proguard-android-optimize.txt` + `proguard-rules.pro`（内容仅 `-dontwarn **`） |
| `debug` | `applicationIdSuffix = ".debug"`，`resValue("color","better_black","#FF5D92")` |

所有 buildType 都设置 `vcsInfo.include = false`，并按 git 信息追加 `versionNameSuffix`：打 tag 时后缀为 `null`，否则为 `-<commitId 前 7 位>`。

### 4.4 签名配置

签名参数通过 `buildProperty(name)` 读取，**先取同名环境变量，再取同名 Gradle 属性**：

| 渠道 | 变量 |
| --- | --- |
| gkd | `GKD_STORE_FILE`、`GKD_STORE_PASSWORD`、`GKD_KEY_ALIAS`、`GKD_KEY_PASSWORD` |
| play | `PLAY_STORE_FILE`、`PLAY_STORE_PASSWORD`、`PLAY_KEY_ALIAS`、`PLAY_KEY_PASSWORD` |

未提供 `GKD_STORE_FILE` 时回退到 debug 签名；未提供 `PLAY_STORE_FILE` 时回退到 gkd 签名。

### 4.5 其他开关

- `GKD_RENAME_APK_FLAG` 存在时，输出 APK 重命名为 `gkd-v<versionName>.apk`。
- `manifestPlaceholders` 注入 `buildKey`、`commitId`、`commitTime`、`tagName`。
- release 变体的 `buildKey` 由 `project.releaseBuildKey(flavorName, commitId)` 计算。

## 5. buildSrc 构建期能力

`buildSrc` 是一个 `kotlin-dsl` 项目，依赖 Ktor client（okhttp）用于上传构建产物。

| 文件 | 提供 |
| --- | --- |
| `BuildProperty.kt` | `Project.buildProperty(name)`：环境变量 → Gradle 属性 |
| `DebugSuffixResources.kt` | `readDebugSuffixResources()`：读取 `strings.xml` 中带 `debug_suffix` 属性的字符串，生成 `<value>-debug` |
| `GenerateUiStringsTask.kt` | `strings.xml` → `li/gkd/app/text/UiStrings.kt`（可缓存任务） |
| `GenerateSourcePathsTask.kt` | 由 `git ls-tree -r <commitId>` 过滤出被跟踪的 `.kt`（排除 test/androidTest/`li/songe/gradle/`），生成 `assets/source-paths.txt` |
| `Git.kt` | `GitInfo`（`commitId`/`commitTime`/`tagName`/`versionNameSuffix`）、`GitInfoService`、`GitOutputValueSource`、`releaseBuildKey`（脏工作区时对变更路径 + 文件大小 + mtime 做 SHA-256 指纹） |
| `BuildAsset.kt` | `configureBuildAssets`：注册 `generateSourcePaths`，并在提供上传凭据时为 release 变体注册 `upload<Variant>BuildAsset` 任务并挂到 `assemble*`/`bundle*` 生命周期 |
| `UploadBuildAssetTask.kt` | 打包 `build.json` + `mapping.txt` + `source-paths.txt` 为 zip，调用 `api.gkd.li` 的 `createBuildAsset` / `getBuildAsset`，并把 zip 作为 GitHub issue 附件上传 |

### 5.1 构建产物上传（可选）

仅在同时提供下列凭据时才注册上传任务：

| 属性 / 环境变量 | 用途 |
| --- | --- |
| `GITHUB_COOKIE` 或 `GKD_GITHUB_COOKIE` | GitHub 会话 |
| `GKD_API_AUTH_TOKEN` | `api.gkd.li` 鉴权 |

上传内容用于崩溃堆栈反混淆：`mapping.txt` 还原混淆名，`source-paths.txt` 还原源码路径，`build.json` 关联 commit/tag 与版本。当工作区有未提交改动时，`buildKey` 会变成带指纹的脏版本，`includeGitMetadata` 相应为 false。

## 6. UI 文案代码生成

`generateUiStrings` 由 `gkd-app/build.gradle.kts` 注册，并通过 `variant.sources.java?.addGeneratedSourceDirectory(...)` 接入每个变体：

```kotlin
val generateUiStrings = tasks.register<GenerateUiStringsTask>("generateUiStrings") {
    stringsFile.set(layout.projectDirectory.file("src/main/res/values/strings.xml"))
    outputDirectory.set(layout.buildDirectory.dir("generated/source/uiStrings"))
}
```

生成规则（源自任务实现）：

- 跳过带 `debug_suffix` 属性的字符串（这些继续走 `R.string` 以保留变体后缀）。
- 字符串名必须匹配 `[a-z][a-z0-9_]*`，否则构建失败。
- 无 `%N$s` 参数的生成 `const val name: String`；有参数的生成 `fun name(arg1: Any?, ...): String`，使用 `String.format(Locale.ROOT, ...)`。
- 参数编号必须连续（`1..n`），否则构建失败；`formatted="false"` 的字符串不做参数解析。
- 输出文件带注释「Generated from res/values/strings.xml. Do not edit.」，**不要手动修改**。

## 7. 常用构建命令

```shell
# 常规开发渠道 release APK
./gradlew :gkd-app:assembleGkdRelease

# Play 渠道 AAB
./gradlew :gkd-app:bundlePlayRelease

# 单元测试
./gradlew :gkd-app:test
./gradlew :gkd-db:jvmTest
./gradlew :gkd-selector:jvmTest

# 仅编译（快速校验）
./gradlew :gkd-app:compileGkdDebugKotlin

# 选择器：本地构建 + 全量测试（含 TS 检查与 Node 测试）
pnpm --dir gkd-selector build
pnpm --dir gkd-selector test

# 拉取已发布的 dist（不需要 Java/Gradle/Kotlin）
pnpm fetch-selector-dist
```

> 仓库约定：**常规测试只编译 `gkd` 渠道**；用户没有明确指令时禁止运行任何 `play` 渠道的编译任务。

## 8. 持续集成

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `Build-Apk.yml` | `workflow_dispatch`；push 到任意分支（忽略 `LICENSE`、`*.md`、`.github/**`），且提交信息不以 `chore:` / `chore(` 开头 | JDK 21 → 写入 `gkd.jks` → `:gkd-app:assembleGkdRelease`（`GKD_RENAME_APK_FLAG=1`）→ 上传 APK 与 `gkd-app/build/outputs` |
| `Build-Release.yml` | push tag `v*` | 写入 gkd 与 play 两个 keystore → `assembleGkdRelease` + `bundlePlayRelease` → 打包 `gkd-<tag>.apk` 与 `outputs-<tag>.zip` → 创建 GitHub Release，body 取 `CHANGELOG.md`，tag 名含 `beta` 时标记为 prerelease |
| `Publish-Selector.yml` | push tag `@gkd-kit/selector@*.*.*` | 校验 tag → `pnpm -F @gkd-kit/selector publish --no-git-checks --fail-if-no-match` |

CI 需要的 Secrets：`GRADLE_CACHE_ENCRYPTION_KEY`、`GKD_STORE_FILE_BASE64`、`GKD_STORE_PASSWORD`、`GKD_KEY_ALIAS`、`GKD_KEY_PASSWORD`、`PLAY_STORE_FILE_BASE64`、`PLAY_STORE_PASSWORD`、`PLAY_KEY_ALIAS`、`PLAY_KEY_PASSWORD`、`GKD_GITHUB_COOKIE`、`GKD_API_AUTH_TOKEN`。

## 9. 发布流程

### 9.1 应用 Release

1. 更新 `CHANGELOG.md`（将作为 Release body）。
2. 如需变更版本，修改 `gkd-app/build.gradle.kts` 的 `versionCode` / `versionName`。
3. 打 tag 并推送：`git tag vX.Y.Z && git push origin vX.Y.Z`（beta 版本 tag 名包含 `beta`）。
4. `Build-Release.yml` 自动构建并创建 Release。

### 9.2 选择器 npm 包发布

发布使用 npm **Trusted Publishing**，不在 GitHub 保存长期 npm token。npm 侧一次性配置的 trusted publisher 为：

- Organization/user：`gkd-kit`
- Repository：`gkd`
- Workflow filename：`Publish-Selector.yml`
- Allowed action：`npm publish`

每次发布：

1. 更新 `gkd-selector/package.json` 的 version 为正式 `x.y.z`（当前为 `0.6.0`），提交并推送。
2. 打 tag：`git tag -a '@gkd-kit/selector@0.6.0' -m '@gkd-kit/selector@0.6.0'`。
3. 推送 tag，工作流自动发布。

发布前会执行包内 `prepack` 钩子，即 `pnpm test`：`test:kotlin` → `build` → `type-check`（tsc）→ `test:node`。任一步失败都不会发布。

`Publish-Selector.yml` 中的 tag 校验脚本为 `gkd-selector/scripts/validate-release-tag.ts`，只接受数字 `x.y.z` 且要求 tag 等于 `package.json` 的 `name@version`。

### 9.3 工作区辅助脚本

`package.json` 只暴露一个根脚本：

```json
"fetch-selector-dist": "node ./gkd-selector/scripts/fetch-dist.ts"
```

它把已发布的 npm 包下载到临时目录，校验包名、版本、JS 入口与类型声明后，**只替换被忽略的本地 `dist` 目录**；不会在依赖安装时自动运行，也不修改 Kotlin 源码。当修改选择器 Kotlin 代码时，应改用 `pnpm --dir gkd-selector build` 从本地源码重建 `dist`。

## 10. 仓库忽略与本地文件

`.gitignore` 覆盖：`.gradle`、`.idea`、`.vscode`、`.cache`、`.kotlin`、`node_modules`、`kotlin-js-store`、`/build`、`/*/build`、`/*/dist`、`.cxx`、`.externalNativeBuild`、`*.jks`、`*.keystore`、`*.apk`、`*.zip`、`local.properties`、`libs.versions.updates.toml`。

需要注意：

- `local.properties` 被忽略但本快照中存在，包含本机 Android SDK 路径；不要提交。
- `gkd-app/src/main/res/values/strings.xml` 是 UI 文案的唯一维护点（见 `gkd-app/STRINGS.md`）。

## 11. 关键文件索引

| 文件 | 职责 |
| --- | --- |
| `settings.gradle.kts` | 模块清单、插件与依赖仓库 |
| `build.gradle.kts` | `Cfg`、全局编译参数、子项目 SDK/Java 版本 |
| `gradle.properties` | Gradle JVM 参数与缓存/配置缓存开关 |
| `gradle/libs.versions.toml` | 版本目录 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.7.1 distribution |
| `stability_config.conf` | Compose 稳定性配置 |
| `gkd-app/build.gradle.kts` | 变体、签名、文案生成、构建产物上传、依赖清单 |
| `gkd-db/build.gradle.kts` | KMP targets、Room schema 目录、KSP |
| `gkd-selector/build.gradle.kts` | KMP targets、`explicitApi()`、npm 依赖注入 |
| `gkd-hidden-api/build.gradle.kts` | 隐藏 API 存根与 remap processor |
| `buildSrc/src/main/kotlin/li/gkd/gradle/*.kt` | 构建期任务与约定 |
| `.github/workflows/Build-Apk.yml` | 分支构建 APK |
| `.github/workflows/Build-Release.yml` | tag 发布 APK + AAB + GitHub Release |
| `.github/workflows/Publish-Selector.yml` | 发布 npm 包 |
| `gkd-selector/package.json` | npm 包元数据与 `prepack` 测试链 |
