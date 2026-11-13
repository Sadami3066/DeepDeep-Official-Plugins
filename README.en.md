# DeepDeep Official Plugins

English | [简体中文](README.md)

The official external-plugin source repository maintained by the DeepDeep project. It brings Feishu, WeCom, SYSU campus services, WeChat export, Xiaomi Cloud and standard IMAP/SMTP mailboxes into DeepDeep, packaged as Ed25519-signed `.ddp` plugin bundles.

## Overview

DeepDeep is a personal AI agent on Android ([app repository](https://github.com/Sadami3066/DeepDeep)). Plugins implement the host contract `plugin-api` in Kotlin, load inside the App process, and extend the agent with tools, skills, prompt sections and self-drawn pages. Plugins are installed only from `.ddp` files, and every plugin is a separate Android application module.

This repository contains six official plugins, published by `milous`:

| Module | Plugin | Version | What it does | For |
|---|---|---|---|---|
| `feishu` | Feishu | 2.0.0 | Read and write messages, calendar, docs, drive, mail and tasks through the official `lark-cli` | Office users who work in Feishu every day |
| `wecom` | WeCom | 2.0.0 | Operate messages, mail, docs, schedules and to-dos through the official `wecom-cli` | Teams that use WeCom |
| `sysu` | SYSU CLI | 2.0.0 | Run the Node.js `sysu` CLI inside the Linux environment: timetable, grades, assignments, course files, venue booking, campus network fees and SSO tickets | Students and staff at Sun Yat-sen University |
| `wechat` | WeChat Export | 0.3.0 | Sync and decrypt the local WeChat database, export complete chat records for a date range and let the agent summarize | Users who need a complete WeChat export |
| `micloud` | Xiaomi Cloud | 2.0.0 | Sign in to a Xiaomi account and read notes, to-dos, SMS, contacts, photos and recordings | Users of Xiaomi Cloud services |
| `mail` | Mail | 1.0.0 | Connect an IMAP/SMTP mailbox with an authorization code: inbox, conversations, composing and account management, with agent search, reading, archiving and draft replies | Users of standard IMAP/SMTP mailboxes such as QQ, 163, Gmail or iCloud |

Versions follow `version` in each module's `src/main/assets/plugin.json`. All six manifests declare `apiLevel` 6 and are built against the current `plugin-api` release; Mail requires DeepDeep 1.0.5+ and the other five require 1.0.4+. Runtime dependencies: Feishu uses `lark-cli` 1.0.98, WeCom uses `wecom-cli` 1.3.4, SYSU uses the Node.js `sysu` CLI 3.4.2; WeChat Export bundles its own `wechat` CLI and needs Root plus local WeChat data; Xiaomi Cloud has no CLI and signs in through a WebView on its plugin page; the Mail plugin needs no CLI and connects to IMAP/SMTP directly over the JVM network stack (QQ, 163, 126, Tencent Exmail, Alibaba Mail, Gmail, iCloud, Fastmail, Yahoo, Zoho and custom servers).

## What's in the repository

| Path | Description |
|---|---|
| `feishu/`, `wecom/`, `sysu/`, `wechat/`, `micloud/`, `mail/` | The six plugin modules: Kotlin sources, `src/main/assets/plugin.json` manifests, icons and pages |
| `gradle/plugin-bundle.gradle.kts` | Shared packaging script providing the `assemblePlugin`, `signPlugin` and `verifyPlugin` tasks |
| `settings.gradle.kts` | Module list and repositories; dependencies come from `io.github.sadami3066.deepdeep:plugin-api` on `https://sadami3066.github.io/deepdeep-maven` (use the latest release there; `api()` brings in the matching `design`), Google and Maven Central |
| `gradle/wrapper/`, `gradlew` | Gradle 9.8.2 wrapper; no local Gradle installation needed |
| `scripts/gradlew-locked` | Serialized Gradle wrapper: flock build lock, single worker by default, capped JVM memory to avoid OOM on small machines |
| `scripts/check.sh` | Builds, signs and verifies all six plugins |
| `keys/` | Local signing-key directory; key files are not committed (`.gitignore` ignores `keys/*.pem` and `keys/*.pub`) |

All modules share the same compile settings: `compileSdk`/`targetSdk` 36, `minSdk` 29, Java 17. The repository stores only sources and packaging scripts — no private keys and no signed artifacts; `.ddp` bundles are produced at build time.

## Quick start

The steps below are for repository maintainers and people building from source.

### Requirements

- JDK 17 or newer (modules compile against Java 17)
- Android SDK Platform 36 and matching Build-Tools
- Git; the first build downloads the Gradle wrapper and Maven dependencies

Point Android Studio or the command line at the SDK in one of two ways: set `ANDROID_HOME`, or create `local.properties` in the repository root with `sdk.dir=/path/to/Android/Sdk` (the file is not committed). When `ANDROID_HOME` is unset, `scripts/check.sh` reads `local.properties`.

### Install the signing keys

```bash
mkdir -p keys
cp /path/to/plugin-signing.pem keys/
cp /path/to/plugin-signing.pub keys/
chmod 600 keys/plugin-signing.pem
```

- The official signing keys are not distributed with the repository. When they are missing, `signPlugin` fails immediately and never generates a new key.
- Keys can live anywhere; point Gradle at the path without the suffix: `-PpluginSigningKey=/secure/plugin-signing` (the script then reads `.pem` and `.pub` in the same directory).
- Back up the private key offline: if it is lost you can no longer publish updates as the same publisher, and if it leaks someone can forge official updates.
- To build for yourself, you can also generate your own Ed25519 key pair:

  ```bash
  mkdir -p keys
  openssl genpkey -algorithm Ed25519 -out keys/plugin-signing.pem
  openssl pkey -in keys/plugin-signing.pem -pubout -out keys/plugin-signing.pub
  ```

  Bundles signed this way belong to your own publisher identity and have nothing to do with the official `keyId` below.

### Build, sign and verify

```bash
./scripts/check.sh                              # all plugins: build + sign + verify
./scripts/gradlew-locked :feishu:verifyPlugin   # one plugin, includes build and signing
./scripts/gradlew-locked :sysu:assemblePlugin   # only repack the unsigned .ddp
```

The task chain is `assemblePlugin → signPlugin → verifyPlugin`:

- `assemblePlugin` repacks `classes*.dex`, `assets/**` and `lib/**` from the debug APK into `<module>/build/plugin/<module>.unsigned.ddp`.
- `signPlugin` signs the canonical entry digest with Ed25519 and produces `<module>/build/plugin/<module>.ddp`; the signature is stored in `META-INF/plugin-signature.json`. The task prints the `publisher keyId` and `bundle sha256`; market entries use that sha256 as `artifact.sha256`.
- `verifyPlugin` recomputes the `keyId` from the embedded public key and verifies the bundle.

`scripts/gradlew-locked` accepts `DEEPDEEP_GRADLE_WORKERS` (default 1) and `DEEPDEEP_GRADLE_JVMARGS` to tune the build.

### Common failures

| Symptom | Cause and fix |
|---|---|
| `missing signing key: ...` | `.pem`/`.pub` missing under `keys/`; restore the backup or point `-PpluginSigningKey` at the right path |
| SDK location not found | Neither `ANDROID_HOME` nor `sdk.dir` in `local.properties` is set |
| `另一个构建正在运行，等待中…` (another build is running) | A Gradle build holds the lock; wait (up to 60 minutes) or avoid concurrent builds |
| `assets/plugin.json is missing` | The manifest must live at the module's `src/main/assets/plugin.json` |
| Verification fails | The bundle was modified after signing, or the signature and public key do not match; run `signPlugin` again |

## Usage

### Install from the plugin market

Search and install from DeepDeep's "Plugins → Market". Market entry versions match this repository, and artifacts are published in the GitHub Releases of [deepdeep-market](https://github.com/Sadami3066/deepdeep-market) (releases are named `plugins-YYYYMMDD`, assets look like `feishu-2.0.0.ddp`). Each entry records the official `keyId` as `publisherKeyId`.

### Import a .ddp manually

1. Download a `.ddp` from a market release, or build one as described above.
2. In DeepDeep, open the Plugins page, tap "Import plugin" and choose the file.
3. The confirmation screen shows the publisher, `keyId`, whole-bundle sha256, size and declared `capabilities`; after you confirm, the key is pinned in the trust store and the plugin is installed and enabled.
4. To update, import a newer `.ddp` with the same `id`: it must be signed by the same key. With no busy sessions the update activates immediately; otherwise it stays pending and activates when sessions go idle or the app restarts.

### Official publisher

- Publisher name: `milous`
- Official `keyId`:

  ```
  ed25519:cb08701a59d66606aa5cf461d1c865e3787dbf571ef53b2cd2c3d750a6b53ee2
  ```

Both `plugin.publisherKeyId` in the market entry and `keyId` in the bundle's `META-INF/plugin-signature.json` must match the value above; do not install bundles that do not. Rotating the signing key creates a new publisher, and installed users cannot upgrade directly.

## Risks

- **Same process, same permissions**: plugins load inside the DeepDeep process and have the same Android permissions and data access as the App. Only install plugins signed by a publisher you trust.
- **Capabilities**: plugins declare what they need in `plugin.json` under `capabilities` (this repository uses `files`, `linux`, `network`, `secrets` and `ui`), and the first-install confirmation lists them. The Feishu, WeCom and SYSU plugins install and run CLIs inside the App's Linux environment and need network access and account credentials; Xiaomi Cloud requests only `secrets`.
- **Account credentials**: Feishu sign-in state lives in `/root/.lark-cli` and `/root/.local/share/lark-cli`, WeCom credentials in `/root/.config/wecom`, and SYSU credentials and sessions (credentials, jwxt/LMS/venue/network sessions) in `/root/.config/sysu`; Xiaomi Cloud stores its cookie in the App's encrypted secrets. Uninstalling a plugin invokes its cleanup logic (for example, the SYSU plugin removes `/root/.config/sysu` and its install directory, while Feishu and WeCom remove their CLIs and sign-in directories), but it is still a good idea to sign out and change passwords once you stop using them.
- **Signing key**: losing the private key means no more updates as the official publisher; leaking it allows forged updates. The official public key maps to a fixed `keyId`, and a new key means a new publisher.
- **Third-party services**: these plugins are third-party integrations maintained by an individual and are not affiliated with Feishu, Tencent, Xiaomi or Sun Yat-sen University. Automated access to third-party services may be restricted by their terms of service and risks account limits or bans; API changes can also break features.
- **Trusted sources**: get `.ddp` files only from this repository or the official market, and never install bundles from unknown sources.
- **Pre-release stage**: DeepDeep and the plugin contract are still evolving, so interfaces and behavior may change.

## License

The source code and documentation in this repository are licensed under [Apache-2.0](LICENSE). Third-party services, CLIs, names and trademarks belong to their respective owners, and use of third-party services is governed by their terms; third-party names and icons are used only to describe what a plugin connects to.

## Disclaimer and feedback

- This is a third-party integration maintained by an individual, with no affiliation or endorsement from Feishu, Tencent, Xiaomi, Sun Yat-sen University or their affiliates.
- Please report problems, suggestions and feedback via [GitHub Issues](https://github.com/Sadami3066/DeepDeep-Official-Plugins/issues). Do not post signing private keys, credentials or security vulnerabilities publicly; contact the maintainer privately on GitHub first.
- The project is provided as-is and maintained on a best-effort basis, with no guarantee of service availability, response time or continued availability of third-party APIs.
- Related repositories: [DeepDeep app](https://github.com/Sadami3066/DeepDeep) · [deepdeep-market](https://github.com/Sadami3066/deepdeep-market) · [DeepDeep-Plugins](https://github.com/Sadami3066/DeepDeep-Plugins)
