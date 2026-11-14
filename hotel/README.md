# hotel · RollingGo 酒店 CLI 插件

管理官方 `rgh` CLI（`@rollinggo/hotel`）的安装、更新与 OAuth 登录，并提供 `deepdeep-hotel` 技能。酒店数据、房型、锁价和下单全部由 `rgh` CLI 完成，不需要 API Key。

## 页面

- 安装 / 更新 `rgh` CLI；
- 登录 RollingGo：打开或复制 OAuth 授权链接；
- 退出登录；
- 显示 CLI 版本与当前登录状态。

## 技能

`deepdeep-hotel` 指导 Agent 使用：

```bash
rgh status
rgh search-hotels --origin-query "杭州西湖附近五星酒店" --place "杭州西湖" --place-type 景点 --check-in-date 2026-06-20 --size 5
rgh hotel-detail --hotel-id 12345 --check-in-date 2026-06-20 --check-out-date 2026-06-21
rgh price-confirm ...
rgh book ...
rgh orders
rgh pre-cancel ...
rgh confirm-cancel ...
```

搜索和详情是只读操作；锁价、下单、取消需要在对话里让用户明确确认。

## 构建

```bash
./scripts/gradlew-locked :hotel:verifyPlugin
```
