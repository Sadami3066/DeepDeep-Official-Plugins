# amap · 高德地图 / Amap Maps

在对话里搜索地点、查看周边、把坐标还原成地址、规划驾车/公交/步行/骑行路线和查询天气。结果以卡片展示，点卡片可在高德地图里查看和导航；导航与打车在高德地图内完成。

## 配置

1. 在高德开放平台创建应用并申请 Web 服务 Key。
2. 打开「高德地图」插件页，粘贴 Key 并保存；插件会验证连接，成功后才保存。
3. Key 只保存在本机，用于向高德请求地图数据。

## 工具

| 工具 | 用途 |
|---|---|
| `amap_place_search` | 按关键词和城市搜索地点 |
| `amap_around_search` | 搜索坐标附近的地点 |
| `amap_regeo` | 把经纬度转换为行政区划地址 |
| `amap_route_driving` | 驾车路线 |
| `amap_route_transit` | 公交、地铁和火车路线 |
| `amap_route_walking` | 步行路线 |
| `amap_route_bicycling` | 骑行路线 |
| `amap_weather` | 城市天气预报 |

所有工具都是只读查询，不会下单、导航或打车；这些动作由用户在高德地图内完成。

## 页面

插件页负责 Key 配置、连接状态和错误重试；聊天里的地点、路线和天气结果以卡片呈现，卡片底部可打开高德地图。

## 构建

```bash
./scripts/gradlew-locked :amap:test :amap:verifyPlugin
```
