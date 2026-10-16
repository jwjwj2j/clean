# dist

本目录只放**免激活（free）版**安装包；需要激活码的版本不在此分发。

## CLEAN-free-1.0.0.apk

| 项 | 值 |
| --- | --- |
| 应用名 / 包名 | CLEAN / `com.clean.click` |
| versionName / versionCode | `1.0.0-b1c8ff6` / `1` |
| 构建命令 | `./gradlew :clean-app:assembleRelease -PCLEAN_FREE=true` |
| 激活门禁 | `BuildConfig.ACTIVATION_REQUIRED = false` —— 装完即用，无需激活码 |
| 随包规则 | `assets/gkd-fallback.json5`，订阅 `id 0` / **`version 187`** |
| 规则 SHA-256 | `01E3890195D9B095E2EBCC248E8A38E3F83D02E75340594D765121D8EB61BCDD`（与包内 assets 逐字节一致） |
| minSdk / targetSdk | 26 / 37 |
| ABI | `arm64-v8a`、`x86_64` |
| 文件大小 | 3,370,260 字节 |
| SHA-256 | `689F428521623FAA3A1098907EC22907DED61149719AAAD86E45D4BFFAD2147E` |
| 构建日期 | 2026-10-16 |

### 本次新增：无障碍服务存活守护（相对上一版 `1.0.0-c797abc`）

ColorOS 等系统会在后台运行数小时后自动关闭侧载应用的无障碍服务。本版加入守护：

- **检测**：4 条路径 —— 无障碍服务销毁（秒级）／前台服务 10 分钟轮询／
  `JobScheduler` 持久化周期任务（进程被杀后仍能拉起自检）／闹钟 + 开机广播；
- **提醒**：被系统关闭时发 `IMPORTANCE_HIGH` 通知（息屏可全屏弹出，无权限自动降级），
  点击直达本应用的无障碍开关页；
- **防误报**：应用内主动关闭开关、切换自动化模式都不会触发提醒；同一次死亡只提醒一次。

> **重要**：无 ADB / Shizuku / root 时**无法自动重新开启**无障碍开关（该开关受
> `WRITE_SECURE_SETTINGS` 保护）。本机制是「尽快发现 + 一键恢复 + 进程自愈」，
> 不是「自动修复」。完整方案与局限见 [../docs/10-无障碍存活守护.md](../docs/10-无障碍存活守护.md)。

### 本次规则变更（相对上一版 `1.0.0-8d2fa41`）

- **铁路12306 / 京东 / 携程 / 淘宝 / 美团 / 滴滴** 六个应用的规则条目整段取自远程订阅 `@fkybnjd/gkd-subscription-cn` v413，与在线内容**逐字节一致**
- 铁路12306 原本没有条目，本次新增（`apps` 610 → 611）
- 其余 610 个应用条目、全局组、11 个分类**保持不变**
- 随包订阅 `version 186 → 187`，使已装旧兜底规则的设备认作「随包版本更新」

### 规则来源策略（重要）

- 本版起 **App 优先使用随包规则**（`assets/gkd-fallback.json5`）：启动时直接装载随包那份，**不访问任何网络订阅源**。
- 老用户（原先装的是网络订阅源）升级后会被自动切到随包规则，旧的网络源会被 `purgeForeignSources` 清除。
- 「检查更新」对随包规则直接跳过（它没有可下载地址），不会再产生注定失败的更新请求与错误提示。
- 要改回上游行为（先试网络、全部失败才用随包），把 `SubscriptionRepository.PREFER_BUNDLED_RULES` 改成 `false` 即可。

### 签名

| 项 | 值 |
| --- | --- |
| 证书 DN | `CN=CLEAN Free, OU=CLEAN, O=CLEAN, L=Unknown, ST=Unknown, C=CN` |
| 证书 SHA-256 | `d76fa1ec0e63b915ae50feeb861906f4fe4e61aee87406411748a60cfab3d5dd` |
| 密钥库 | `clean-free.jks`，alias `cleanfree`，RSA 4096，有效期 10000 天 |

> 签名密钥库**位于仓库之外，不入库**。它与「需要激活码」版本使用的密钥不同。同一密钥签出的包可以互相覆盖安装。

### 注意

- 免激活版与需激活版**同包名、同一份代码**，但**签名不同**：两者不能互相覆盖安装，切换前需先卸载（会丢本地设置）。
- 免激活版只关闭激活门禁，**不裁剪特权链**：安装包内仍包含 Shizuku / 特权相关权限声明（`WRITE_SECURE_SETTINGS`、`GET_APP_OPS_STATS`、`QUERY_ALL_PACKAGES`、`moe.shizuku.manager.permission.API_V23` 等）。
- 安装前请阅读仓库 README 的「关于规则来源」与「隐私」两节。二次分发需遵守 **GPL-3.0-only**，保留上游版权声明并标注这是修改版本。
