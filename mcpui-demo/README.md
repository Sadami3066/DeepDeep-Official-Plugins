# MCP UI Demo

工具结果渲染器的开发示例，不上市场。

它证明两件事：

1. 插件可以按 `source/server/tool` 给 MCP 工具结果提供 Compose 卡片，而不是只能给插件自己的工具名注册卡槽；
2. `tool_execute` 包装的 MCP 工具会被宿主还原成真实工具身份，渲染器不需要关心调用层。

## 构建

需要 `plugin-api 1.3.0`（本分支的 SDK；发布前用 `./scripts/gradlew-locked :plugin-api:publishToMavenLocal` 从宿主仓库装到本地）：

```bash
./scripts/gradlew-locked :mcpui-demo:verifyPlugin
```

产物 `mcpui-demo/build/plugin/mcpui-demo.ddp`；`plugin.json` 要求宿主 1.1.0+。

## 验证

1. 本机启动 mock MCP 服务器：

   ```bash
   python3 mcpui-demo/server/mock_mcp.py 8765
   ```

2. 在 App 的 MCP 设置添加：

   ```json
   {"mcpServers":{"demo":{"type":"streamable-http","url":"http://10.0.2.2:8765/mcp"}}}
   ```

   真机改成电脑局域网 IP；模拟器用 `10.0.2.2`。

3. 安装插件，让 Agent 调 `weather`。工具结果会被 `tool_execute` 包一层，但插件渲染器应显示天气卡片，而不是默认文本卡片。

插件页「MCP UI」也直接展示了同一张卡片的静态效果。
