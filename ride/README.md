# ride · 滴滴打车插件

通过滴滴出行 MCP 的 HTTP API 提供打车查询工具，不依赖宿主连接器：

- `ride_place_search`：地点搜索与经纬度
- `ride_estimate`：车型与参考价
- `ride_app_link`：生成滴滴 App / 小程序发单链接
- `ride_order_status`：订单状态、司机与车牌
- `ride_driver_location`：司机实时位置

插件页填写滴滴 MCP Key，可选择调试环境（模拟数据，不产生真实订单）或正式环境。发单链接交给用户在滴滴内完成；插件不直接创建真实订单。

构建：

```bash
./scripts/gradlew-locked :ride:verifyPlugin
```
