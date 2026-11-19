# MCP UI Demo

A development sample for the tool result renderer; not published to the market.

It proves two things:

1. A plugin can provide Compose cards for MCP tool results by `source/server/tool` instead of only registering slots for its own tool names;
2. MCP tools wrapped by `tool_execute` are restored to their real tool identity by the host, so the renderer does not need to care about the call layer.

## Build

Requires `plugin-api 1.3.0` (the SDK on this branch; before release, install it into the local Maven cache from the host repository with `./scripts/gradlew-locked :plugin-api:publishToMavenLocal`):

```bash
./scripts/gradlew-locked :mcpui-demo:verifyPlugin
```

Produces `mcpui-demo/build/plugin/mcpui-demo.ddp`; `plugin.json` requires host 1.1.0+.

## Verify

1. Start the mock MCP server locally:

   ```bash
   python3 mcpui-demo/server/mock_mcp.py 8765
   ```

2. Add it in the app's MCP settings:

   ```json
   {"mcpServers":{"demo":{"type":"streamable-http","url":"http://10.0.2.2:8765/mcp"}}}
   ```

   On a physical device use the computer's LAN IP; the emulator uses `10.0.2.2`.

3. Install the plugin and have the agent call `weather`. The tool result is wrapped by `tool_execute`, but the plugin renderer should show the weather card instead of the default text card.

The "MCP UI" plugin page also shows the same card as a static preview.
