# DeepDeep 官方插件

DeepDeep 官方维护的外部插件仓库，发布者 milous。每个插件是独立的 Android 模块，由共享脚本打成 `.ddp` 并用 Ed25519 签名。

宿主契约 `plugin-api` 与 UI 设计系统 `design` 由公开 Maven 仓库提供：

```kotlin
repositories {
    maven { url = uri("https://sadami3066.github.io/deepdeep-maven") }
}

dependencies {
    compileOnly("io.github.sadami3066.deepdeep:plugin-api:0.7.0")
}
```

`design` 由 `plugin-api` 通过 `api()` 传递，插件页面直接使用其中的 Dd 组件。

## 插件

| 模块 | 名称 | 版本 |
|---|---|---|
| `feishu` | 飞书 | 1.0.1 |
| `wecom` | 企业微信 | 1.0.1 |
| `sysu` | 中山大学 CLI | 1.2.2 |
| `micloud` | 小米云服务 | 1.0.2 |

版本以各模块 `src/main/assets/plugin.json` 的 `version` 为准，`publisher.name` 固定为 `milous`；市场条目与本仓库版本保持一致。

## 目录

| 目录 | 说明 |
|---|---|
| `gradle/plugin-bundle.gradle.kts` | 共享打包脚本：`assemblePlugin` 把 debug APK 重打成 `.ddp`，`signPlugin` 用 Ed25519 签名，`verifyPlugin` 校验签名 |
| `scripts/gradlew-locked` | 串行 Gradle 包装（flock + 单 worker，避免小内存机器 OOM） |
| `scripts/check.sh` | 一键打包、签名、校验全部插件 |
| `keys/` | 本机签名密钥（不入库）；官方公钥 keyId 见下 |

## 签名密钥

官方签名密钥 `keys/plugin-signing.pem`/`.pub` 不随仓库分发，由维护者离线备份并在构建前恢复到 `keys/`；缺失时 `signPlugin` 直接失败，不会自动生成新钥。官方发布者 `keyId` 为：

```
ed25519:cb08701a59d66606aa5cf461d1c865e3787dbf571ef53b2cd2c3d750a6b53ee2
```

市场条目与已发布制品均使用该密钥；换钥会被市场视为新发布者，已安装用户无法直接升级。

## 构建与验证

```bash
./scripts/gradlew-locked :sysu:assemblePlugin   # 打包
./scripts/gradlew-locked :sysu:signPlugin       # 签名
./scripts/gradlew-locked :sysu:verifyPlugin     # 校验签名
./scripts/check.sh                              # 全部插件
```

产物在 `<module>/build/plugin/<module>.ddp`。安装：把 `.ddp` 传到手机，在 DeepDeep 插件页「导入插件」选择文件；首次安装会展示发布者与能力清单并要求确认。

市场制品发布在 [DeepDeep-Market](https://github.com/Sadami3066/DeepDeep-Market) 的 Release 中，条目版本与上表对应，签名密钥与上节的官方 `keyId` 一致。

开发新插件请使用模板仓库 [DeepDeep-Plugins](https://github.com/Sadami3066/DeepDeep-Plugins)。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
