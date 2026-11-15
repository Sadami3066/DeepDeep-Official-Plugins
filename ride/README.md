# ride · 滴滴打车插件

通过滴滴出行 MCP 提供从估价到下单、取消和订单跟踪的完整流程：

- `ride_place_search`：地点搜索与经纬度
- `ride_estimate`：车型与参考价，作为下单依据
- `ride_create_order`：用户确认后创建真实滴滴订单
- `ride_cancel_order`：用户确认后取消订单
- `ride_app_link`：生成滴滴 App / 小程序发单链接
- `ride_order_status`：订单状态、司机与车牌
- `ride_driver_location`：司机实时位置

滴滴 Key 保存在本机加密存储（页面不回显），保存时会验证连接；「高级设置」里可切换正式环境与体验模式（体验模式使用模拟数据，不产生真实订单）。创建订单前会弹出原生确认，支付在滴滴内完成，插件不会自动支付。

构建：

```bash
./scripts/gradlew-locked :ride:test :ride:verifyPlugin
```
