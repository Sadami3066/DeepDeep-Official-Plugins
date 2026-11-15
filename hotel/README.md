# hotel · RollingGo 酒店插件

连接 RollingGo，在对话里搜索酒店、查看实时房型、创建待支付订单和查询订单；插件页负责服务安装、更新和登录，不需要 API Key。

## 页面

- 安装 / 更新酒店服务；
- 登录 RollingGo：自动打开授权链接，也可手动打开或复制；
- 退出登录；
- 查看运行环境、服务版本与登录状态；失败可就地重试，登录可取消。

## 工具

- `hotel_search`：按地点、日期、人数、星级搜索酒店；
- `hotel_detail`：查看房型、床型、总价和取消政策；
- `hotel_book`：用户确认后锁价并创建待支付订单，返回官方支付链接；
- `hotel_orders`：查看订单。

搜索和房型只读；预订前会弹出原生确认，只创建待支付订单，支付由用户在 RollingGo 完成。搜索结果、房型和待支付订单都以卡片展示。

## 技能

`deepdeep-hotel` 指导 Agent 优先使用上述工具；用户明确确认酒店、房型、日期和总价，并提供联系人信息后，才调用 `hotel_book` 锁价并创建待支付订单，插件不代替用户支付。

## 构建

```bash
./scripts/gradlew-locked :hotel:test :hotel:verifyPlugin
```
