# dist

本目录只放**免激活（free）版**安装包；需要激活码的版本不在此分发。

## CLEAN-free-1.0.0.apk

| 项 | 值 |
| --- | --- |
| 应用名 / 包名 | CLEAN / `com.clean.click` |
| versionName / versionCode | `1.0.0-8d2fa41` / `1` |
| 构建命令 | `./gradlew :clean-app:assembleRelease -PCLEAN_FREE=true` |
| 激活门禁 | `BuildConfig.ACTIVATION_REQUIRED = false` —— 装完即用，无需激活码 |
| minSdk / targetSdk | 26 / 37 |
| ABI | `arm64-v8a`、`x86_64` |
| 文件大小 | 3,357,972 字节 |
| SHA-256 | `2B3FF83A3CE932685D49F3E3CD92E97CF4ACF51692D7EF63AEEB9B614F382676` |
| 构建日期 | 2026-10-08 |

### 签名

| 项 | 值 |
| --- | --- |
| 证书 DN | `CN=CLEAN Free, OU=CLEAN, O=CLEAN, L=Unknown, ST=Unknown, C=CN` |
| 证书 SHA-256 | `d76fa1ec0e63b915ae50feeb861906f4fe4e61aee87406411748a60cfab3d5dd` |
| 密钥库 | `clean-free.jks`，alias `cleanfree`，RSA 4096，有效期 10000 天 |

> 签名密钥库**位于仓库之外，不入库**。它与「需要激活码」版本使用的密钥不同。

### 注意

- 免激活版与需激活版**同包名、同一份代码**，但**签名不同**：两者不能互相覆盖安装，切换前需先卸载（会丢本地设置）。
- 免激活版只关闭激活门禁，**不裁剪特权链**：安装包内仍包含 Shizuku / 特权相关权限声明（`WRITE_SECURE_SETTINGS`、`GET_APP_OPS_STATS`、`QUERY_ALL_PACKAGES`、`moe.shizuku.manager.permission.API_V23` 等）。
- 安装前请阅读仓库 README 的「关于规则来源」与「隐私」两节。二次分发需遵守 **GPL-3.0-only**，保留上游版权声明并标注这是修改版本。
