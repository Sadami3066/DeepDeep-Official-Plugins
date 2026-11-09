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
| `sysu` | 中山大学 CLI | 1.2.0 |
| `micloud` | 小米云服务 | 1.0.2 |

版本以各模块 `src/main/assets/plugin.json` 的 `version` 为准，`publisher.name` 固定为 `milous`。

## 目录

| 目录 | 说明 |
|---|---|
| `gradle/plugin-bundle.gradle.kts` | 共享打包脚本：`assemblePlugin` 把 debug APK 重打成 `.ddp`，`signPlugin` 用 Ed25519 签名，`verifyPlugin` 校验签名 |
| `scripts/gradlew-locked` | 串行 Gradle 包装（flock + 单 worker，避免小内存机器 OOM） |
| `scripts/check.sh` | 一键打包、签名、校验全部插件 |
| `keys/` | 本机开发签名密钥（不入库） |

## 构建与验证

```bash
./scripts/gradlew-locked :sysu:assemblePlugin   # 打包
./scripts/gradlew-locked :sysu:signPlugin       # 签名
./scripts/gradlew-locked :sysu:verifyPlugin     # 校验签名
./scripts/check.sh                              # 全部插件
```

产物在 `<module>/build/plugin/<module>.ddp`。安装：把 `.ddp` 传到手机，在 DeepDeep 插件页「导入插件」选择文件；首次安装会展示发布者与能力清单并要求确认。

开发新插件请使用模板仓库 [DeepDeep-Plugins](https://github.com/Sadami3066/DeepDeep-Plugins)。
