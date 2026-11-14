# maps · 交互地图 spike

`maps` 是一个验证无 API Key 交互地图的插件模块，尚未进入市场发布清单。

- 页面用 Leaflet（内联在 `map.html`）显示高德栅格瓦片，支持拖动、双指缩放和右下角缩放控件。
- 瓦片地址使用高德的公开栅格端点，仅供本地验证；正式产品需要确认服务条款并换用有授权的底图或 Key。
- 标记点使用杭州坐标；高德瓦片是 GCJ-02，后续接搜索/路线时需要做 WGS-84 ↔ GCJ-02 转换。
- `PluginPageRoute` 已修复全屏插件页的剩余高度约束，`DdWebView` 现在能拿到实际页面高度。
- 当前页面只做底图与手势验证，不接 Agent 工具、POI 搜索或路线规划。

构建：

```bash
./scripts/gradlew-locked :maps:verifyPlugin
```
