# hotel · RollingGo 酒店 MCP 渲染插件

为 [RollingGo Hotel MCP](https://github.com/RollingGo-AI/RollingGo-hotel-MCP-CN) 的 `searchHotels` / `getHotelDetail` 工具提供聊天卡片。插件本身不保存 API Key，也不直接调用酒店接口；连接器和鉴权由宿主的 MCP 配置负责。

## 使用

1. 在 [RollingGo 合作方中心](https://travelportal-partner-center.dida.com/register?lang=zh) 申请 API Key。
2. 在宿主的「连接器 → 我的 → +」中导入：

```json
{
  "mcpServers": {
    "rollinggo-hotel": {
      "type": "streamable-http",
      "url": "https://mcp.rollinggo.cn/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_API_KEY"
      }
    }
  }
}
```

3. 在对话里说「帮我找杭州西湖附近明天入住的酒店」，Agent 调用 MCP 后，结果会显示成酒店价格卡片。

## 本地 mock

`server/mock_hotel_mcp.py` 提供固定酒店数据，用于没有 API Key 时验证渲染链路：

```bash
python3 server/mock_hotel_mcp.py 8766
adb reverse tcp:8766 tcp:8766
```

连接器地址填 `http://127.0.0.1:8766/mcp`。
