# DeepDeep 官方插件

[English](README.en.md) | 简体中文

DeepDeep 官方维护的外部插件源码仓库：把飞书、企业微信、中山大学校园服务、微信记录导出、小米云服务和标准邮箱接入 DeepDeep，统一打包成带 Ed25519 签名的 `.ddp` 插件包。

## 概览

DeepDeep 是一个运行在 Android 上的个人智能体（[应用仓库](https://github.com/Sadami3066/DeepDeep)）。插件用 Kotlin 实现宿主契约 `plugin-api`，在 App 进程内加载，通过工具、技能、提示词和自绘页面扩展助手能力；插件只从 `.ddp` 安装，每个插件是一个独立的 Android application 模块。

本仓库包含七个官方插件，发布者为 `milous`：

| 模块 | 插件 | 版本 | 作用 | 适合 |
|---|---|---|---|---|
| `feishu` | 飞书 | 2.2.0 | 通过官方 `lark-cli` 读写消息、日历、云文档、云空间、邮箱与任务 | 日常使用飞书的办公用户 |
| `wecom` | 企业微信 | 2.2.0 | 通过官方 `wecom-cli` 操作消息、邮件、文档、日程与待办 | 使用企业微信的团队用户 |
| `sysu` | 中山大学 CLI | 2.2.0 | 在 Linux 环境运行 Node.js 版 `sysu` CLI：课表、成绩、作业、课件、场馆预约、网费与统一认证换票 | 中山大学在校师生 |
| `wechat` | 微信导出 | 0.5.0 | 同步并解密微信本地数据库，按日期范围导出完整聊天记录，交给 Agent 总结 | 需要完整导出微信记录的用户 |
| `micloud` | 小米云服务 | 2.2.0 | 登录小米账号后读取笔记、待办、短信、联系人、相册与录音 | 使用小米云服务的用户 |
| `mail` | 邮箱 | 1.4.0 | 用授权码接入 IMAP/SMTP 邮箱：收件箱、会话、写信（含附件）、账户管理，聊天内邮件卡片，并让 Agent 搜信、读信、归档、起草与发送 | 使用 QQ、163、Gmail、iCloud 等标准 IMAP/SMTP 邮箱的用户 |
| `themes` | 主题包 | 1.0.0 | 给宿主提供 8 套浅色/深色主题配色，在设置的外观里一键切换 | 想换个界面气质的用户 |

版本以各模块 `src/main/assets/plugin.json` 的 `version` 为准。七个插件的清单均为 `apiLevel` 6，使用 `plugin-api` 1.2.0 构建；都要求 DeepDeep 1.0.8+。运行时依赖：飞书使用 `lark-cli` 1.0.98，企业微信使用 `wecom-cli` 1.3.4，中山大学使用 Node.js 版 `sysu` CLI 3.4.2；微信导出插件自带 `wechat` CLI，需要 Root 和微信本地数据；小米云服务没有 CLI，通过插件页内 WebView 登录；邮箱插件不依赖 CLI，用 JVM 网络栈直连 IMAP/SMTP（QQ、163、126、腾讯企业邮、阿里云邮箱、Gmail、iCloud、Fastmail、Yahoo、Zoho 与自定义服务器）。

## 仓库里有什么

| 路径 | 说明 |
|---|---|
| `feishu/`、`wecom/`、`sysu/`、`wechat/`、`micloud/`、`mail/`、`themes/` | 七个插件模块：Kotlin 源码、`src/main/assets/plugin.json` 清单、图标与页面 |
| `gradle/plugin-bundle.gradle.kts` | 共享打包脚本，提供 `assemblePlugin`、`signPlugin`、`verifyPlugin` 三个任务 |
| `settings.gradle.kts` | 模块列表与仓库配置；依赖来自 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api`（版本以该仓库最新发布为准，`api()` 传递配套的 `design`）、Google 与 Maven Central |
| `gradle/wrapper/`、`gradlew` | Gradle 9.8.2 wrapper，无需本机预装 Gradle |
| `scripts/gradlew-locked` | 串行 Gradle 包装：flock 构建锁、默认单 worker、限制 JVM 内存，避免小内存机器并发构建 OOM |
| `scripts/check.sh` | 构建、签名并验签全部七个插件 |
| `keys/` | 本机签名密钥目录，密钥文件不入库（`.gitignore` 忽略 `keys/*.pem` 与 `keys/*.pub`） |

各模块编译配置一致：`compileSdk`/`targetSdk` 36、`minSdk` 29、Java 17。仓库只保存源码与打包脚本，不包含私钥，也不托管已签名制品；`.ddp` 产物在构建时生成。

## 快速开始

以下步骤面向仓库维护者与自行构建的用户。

### 环境要求

- JDK 17 或更高（模块按 Java 17 编译）
- Android SDK Platform 36 与对应 Build-Tools
- Git；首次构建需要联网下载 Gradle wrapper 与 Maven 依赖

Android SDK 位置二选一：设置 `ANDROID_HOME`，或在仓库根创建 `local.properties` 并写入 `sdk.dir=/path/to/Android/Sdk`（该文件不入库）。`scripts/check.sh` 会在未设置 `ANDROID_HOME` 时自动读取 `local.properties`。

### 放置签名密钥

```bash
mkdir -p keys
cp /path/to/plugin-signing.pem keys/
cp /path/to/plugin-signing.pub keys/
chmod 600 keys/plugin-signing.pem
```

- 官方签名密钥不随仓库分发；缺失时 `signPlugin` 直接失败，不会自动生成新密钥。
- 密钥可放在任意位置，用 Gradle 属性指向不带后缀的路径：`-PpluginSigningKey=/secure/plugin-signing`（脚本会读取同目录下的 `.pem` 与 `.pub`）。
- 私钥务必离线备份：丢失后无法再用同一发布者身份发布更新，泄露则可以伪造官方更新。
- 自行构建也可以生成自己的 Ed25519 密钥：

  ```bash
  mkdir -p keys
  openssl genpkey -algorithm Ed25519 -out keys/plugin-signing.pem
  openssl pkey -in keys/plugin-signing.pem -pubout -out keys/plugin-signing.pub
  ```

  这样签出的 `.ddp` 属于你自己的发布者身份，与下方的官方 `keyId` 无关。

### 构建、签名与验签

```bash
./scripts/check.sh                              # 全部插件：构建 + 签名 + 验签
./scripts/gradlew-locked :feishu:verifyPlugin   # 单个插件，包含构建与签名
./scripts/gradlew-locked :sysu:assemblePlugin   # 仅重打未签名 .ddp
```

任务关系为 `assemblePlugin → signPlugin → verifyPlugin`：

- `assemblePlugin` 把 debug APK 中的 `classes*.dex`、`assets/**`、`lib/**` 重打成 `<module>/build/plugin/<module>.unsigned.ddp`。
- `signPlugin` 对规范化后的条目摘要做 Ed25519 签名，生成 `<module>/build/plugin/<module>.ddp`，签名写在 `META-INF/plugin-signature.json`；任务输出会打印 `publisher keyId` 与 `bundle sha256`，市场条目的 `artifact.sha256` 使用该值。
- `verifyPlugin` 用签名内嵌公钥复算 `keyId` 并对包体验签。

`scripts/gradlew-locked` 支持用环境变量 `DEEPDEEP_GRADLE_WORKERS`（默认 1）与 `DEEPDEEP_GRADLE_JVMARGS` 调整构建参数。

### 常见失败

| 现象 | 原因与处理 |
|---|---|
| `missing signing key: ...` | `keys/` 下缺少 `.pem`/`.pub`；从备份恢复，或用 `-PpluginSigningKey` 指向正确路径 |
| SDK 位置找不到 | 未设置 `ANDROID_HOME` 且没有 `local.properties` 的 `sdk.dir` |
| `另一个构建正在运行，等待中…` | 已有 Gradle 构建持锁；等待即可（最多 60 分钟），或避免并发构建 |
| `assets/plugin.json is missing` | 清单必须位于模块的 `src/main/assets/plugin.json` |
| 验签失败 | 签名后包体被改动，或签名与公钥不匹配；重新执行 `signPlugin` |

## 使用方式

### 从插件市场安装

在 DeepDeep 的「插件 → 市场」中搜索并安装。市场条目版本与本仓库一致，制品发布在 [deepdeep-market](https://github.com/Sadami3066/deepdeep-market) 的 GitHub Releases 中（Release 名形如 `plugins-YYYYMMDD`，资产名形如 `feishu-2.1.0.ddp`），条目记录的 `publisherKeyId` 为官方 `keyId`。

### 手动导入 .ddp

1. 从市场 Release 下载 `.ddp`，或按上文自行构建。
2. 在 DeepDeep 的插件页点击「导入插件」，选择该文件。
3. 确认页会展示发布者、`keyId`、整包 sha256、体积与插件声明的能力（`capabilities`）；确认后写入信任库并安装启用。
4. 更新时导入同 `id` 的新版本 `.ddp`：签名必须由同一密钥签出；没有忙碌会话时立即激活，否则保持待重启状态，等会话空闲或下次启动后激活。

### 官方发布者

- 发布者名称：`milous`
- 官方 `keyId`：

  ```
  ed25519:cb08701a59d66606aa5cf461d1c865e3787dbf571ef53b2cd2c3d750a6b53ee2
  ```

市场条目的 `plugin.publisherKeyId` 与包内 `META-INF/plugin-signature.json` 的 `keyId` 都应与上面的值一致；不一致的插件不要安装。更换签名密钥会被视为新的发布者，已安装用户无法直接升级。

## 风险

- **与 App 同进程、同权限**：插件在 DeepDeep 进程内加载，拥有与 App 相同的 Android 权限和数据访问范围。只安装你信任的发布者签名的插件。
- **能力范围**：插件在 `plugin.json` 的 `capabilities` 中声明所需能力（本仓库涉及 `files`、`linux`、`network`、`secrets`、`ui`），首次安装的确认页会列出。飞书、企业微信、中山大学插件会在 App 的 Linux 环境里安装并运行 CLI，需要网络与账号凭据；小米云服务插件只申请 `secrets`。
- **账号凭据**：飞书登录态在 Linux 环境的 `/root/.lark-cli` 与 `/root/.local/share/lark-cli`，企业微信凭据在 `/root/.config/wecom`，中山大学的凭据与登录态（credentials、教务/LMS/场馆/网费会话）在 `/root/.config/sysu`；小米云 cookie 存放在 App 的加密 secrets。卸载插件时宿主会调用插件的清理逻辑（如中山大学插件会删除 `/root/.config/sysu` 和安装目录，飞书、企业微信插件会删除 CLI 与登录目录），但建议在不再使用时退出登录并修改相关密码。
- **签名密钥**：私钥丢失后无法再以官方发布者身份更新；私钥泄露则可能被用来伪造更新。官方公钥对应固定的 `keyId`，换钥等于换发布者。
- **第三方服务**：这些插件是个人维护的第三方集成，与飞书、腾讯、小米、中山大学无隶属关系。自动化访问第三方服务可能受其服务条款限制，存在账号受限或封禁的风险；第三方接口变更也可能让功能失效。
- **来源可信**：只从本仓库或官方市场获取 `.ddp`，不要安装来源不明的插件包。
- **发布前阶段**：DeepDeep 与插件契约仍在迭代，接口或行为可能变化。

## 权益与许可

本仓库的源码与文档采用 [Apache-2.0](LICENSE) 许可。插件访问的第三方服务、CLI 及其名称、商标归各自所有者所有，使用第三方服务需遵守对应条款；第三方名称与图标仅用于说明接入对象。

## 声明与反馈

- 本项目为个人维护的第三方集成，与飞书、腾讯、小米、中山大学及其关联公司无隶属或背书关系。
- 问题、建议与使用反馈请提交到 [GitHub Issues](https://github.com/Sadami3066/DeepDeep-Official-Plugins/issues)。涉及签名私钥、凭据或安全漏洞的内容请不要公开，先通过 GitHub 私下联系维护者。
- 项目按现状提供，尽力维护，不承诺服务可用性、响应时间或第三方接口的持续可用。
- 相关仓库：[DeepDeep 应用](https://github.com/Sadami3066/DeepDeep) · [deepdeep-market](https://github.com/Sadami3066/deepdeep-market) · [DeepDeep-Plugins](https://github.com/Sadami3066/DeepDeep-Plugins)
