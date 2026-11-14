# hotel · RollingGo 酒店插件

连接 RollingGo，在对话里搜索酒店、查看实时房型与订单；插件页负责服务安装、更新和登录，不需要 API Key。

## 页面

- 安装 / 更新酒店服务；
- 登录 RollingGo：自动打开授权链接，也可手动打开或复制；
- 退出登录；
- 查看运行环境、服务版本与登录状态；失败可就地重试，登录可取消。

## 工具

- `hotel_search`：按地点、日期、人数、星级搜索酒店；
- `hotel_detail`：查看房型、床型、总价和取消政策；
- `hotel_orders`：查看订单。

工具只读，不锁价、不自动下单。搜索结果和房型以卡片展示，需要预订时打开卡片里的 RollingGo 官方页面完成。

## 技能

`deepdeep-hotel` 指导 Agent 优先使用上述工具；用户明确要求代为锁价或下单时，才使用 `rgh price-confirm` / `rgh book`，并遵守两步确认。取消同样先查违约金再确认。

## 构建

```bash
./scripts/gradlew-locked :hotel:test :hotel:verifyPlugin
```
