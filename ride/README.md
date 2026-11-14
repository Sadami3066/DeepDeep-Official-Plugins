# ride · 滴滴打车插件

通过滴滴出行开放接口提供打车查询，不依赖宿主连接器：

- `ride_place_search`：地点搜索与经纬度
- `ride_estimate`：车型与参考价
- `ride_app_link`：生成滴滴 App / 小程序发单链接
- `ride_order_status`：订单状态、司机与车牌
- `ride_driver_location`：司机实时位置

滴滴 Key 保存在本机加密存储（页面不回显），保存时会验证连接；「高级设置」里可切换正式环境与体验模式（体验模式使用模拟数据，不产生真实订单）。发单与支付在滴滴 App / 小程序内完成，插件不会自动下单。

构建：

```bash
./scripts/gradlew-locked :ride:test :ride:verifyPlugin
```
