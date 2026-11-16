# ride · 滴滴打车插件

通过滴滴出行 MCP 提供从估价到下单、取消和订单跟踪的完整流程：

- `ride_place_search`：地点搜索，列出接口本次返回的全部地点及名称、完整地址、城市与经纬度
- `ride_estimate`：列出全部车型和参考价/券后价、优惠、币种、接驾等待、距离、时长、车型 ID/品类、手机尾号与 traceId，作为下单依据
- `ride_create_order`：用户确认后创建真实滴滴订单
- `ride_cancel_order`：用户确认后取消订单
- `ride_app_link`：生成滴滴 App / 小程序发单链接
- `ride_order_status`：订单状态、司机、车型、车牌、电话、评分、距上车点距离、预计到达与订单时间；查询限流时明确提示稍后重试
- `ride_driver_location`：司机实时位置经纬度、地址，以及接口给出的司机、车牌、距离、ETA、状态与更新时间

滴滴 Key 保存在本机加密存储（页面不回显），保存时会验证连接；「高级设置」里可切换正式环境与体验模式（体验模式使用模拟数据，不产生真实订单）。创建订单与取消订单都会弹出原生确认，取消需用户再次确认；支付在滴滴内完成，插件不会自动支付。

构建：

```bash
./scripts/gradlew-locked :ride:testDebugUnitTest :ride:verifyPlugin
```
