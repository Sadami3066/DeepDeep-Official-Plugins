# ride · 滴滴打车插件

通过滴滴出行 MCP 提供从估价到下单、取消和订单跟踪的完整流程：

- `ride_place_search`：地点搜索，文本给出名称、完整地址与经纬度
- `ride_estimate`：车型、参考价/券后价、接驾等待、距离、时长、车型品类与 traceId，作为下单依据
- `ride_create_order`：用户确认后创建真实滴滴订单
- `ride_cancel_order`：用户确认后取消订单
- `ride_app_link`：生成滴滴 App / 小程序发单链接
- `ride_order_status`：订单状态、司机、车型、车牌、电话、距上车点距离与预计到达；查询限流时明确提示稍后重试
- `ride_driver_location`：司机实时位置经纬度

滴滴 Key 保存在本机加密存储（页面不回显），保存时会验证连接；「高级设置」里可切换正式环境与体验模式（体验模式使用模拟数据，不产生真实订单）。创建订单与取消订单都会弹出原生确认，取消需用户再次确认；支付在滴滴内完成，插件不会自动支付。

构建：

```bash
./scripts/gradlew-locked :ride:test :ride:verifyPlugin
```
