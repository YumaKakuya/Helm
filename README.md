# Helm.

A tool hub and governance layer for AI coding agents. One MCP endpoint, many providers, less context waste.

## The Problem

AI coding agents load ALL tool schemas into every API request. As MCP servers grow, this becomes a critical bottleneck:

- **Context bloat**: 5+ MCP servers consume 40,000-67,000 tokens before any user interaction
- **API failures**: Heavy setups hit 147,000 tokens (73% of 200K context window), triggering API errors
- **Deferred loading latency**: ToolSearch workarounds add 3-8 seconds per tool use
- **Cost waste**: Tool schemas burn tokens every turn, regardless of whether tools are used

This is a known problem in the OpenCode ecosystem: [#9350](https://github.com/anomalyco/opencode/issues/9350), [#8625](https://github.com/anomalyco/opencode/issues/8625), [#16206](https://github.com/anomalyco/opencode/issues/16206), [#20489](https://github.com/anomalyco/opencode/issues/20489).

## What Helm Does

Helm sits between your AI agent and your tool providers. Instead of loading every tool schema into every API call, the agent connects to one MCP endpoint and Helm routes requests to the right provider.

```
AI Agent (OpenCode / Claude Code / Cursor)
    |
    |  stdio (single MCP connection)
    v
+-- Helm. -----------------------------------------+
|                                                   |
|   Go Launcher         Java Daemon                 |
|   (JRE discovery,     (state machine, policy,     |
|    process mgmt)       routing, registry)         |
|        |                    |                     |
|        |          UDS (Unix Domain Socket)         |
|        |                    |                     |
|   MCP Bridge (stdio) <------+                     |
|        |                                          |
+--------|------------------------------------------+
         |
     +---------+---------+---------+---------+-----------+
     |  web    |  edit   | project | session | synthetic |  <-- TS adapters
     +---------+---------+---------+---------+-----------+
     | relay providers (your existing MCP servers)        |
     +---------------------------------------------------+
```

Core capabilities:

- **Single MCP endpoint** aggregating multiple tool providers
- **Classifier-invisible path** for tools (avoids Anthropic API body-size limits)
- **Session lifecycle** (Closed / Armed / Open / CoolingDown) with idle timeout (suppressed while a bridge is connected)
- **Allow/deny policy** per tool, with session-scoped rule injection
- **Structured failure responses** with error codes, recovery guidance, and fallback suggestions
- **Route logging** for observability, with intent annotation support
- **Contract-based disambiguation** (AI asks the hub which tool to use; hub resolves from capability contracts)
- **Body-budget monitoring** to detect regressions early
- **Secret scrub** on intent annotations before persistence

## Quick Start

### Prerequisites

- Java 21+
- Go 1.21+
- Node.js 18+
- Linux or macOS (Windows via WSL)

### Build

```bash
git clone https://github.com/YumaKakuya/helm.git
cd helm

# Build Go launcher
go build -o helm ./cmd/mcphub

# Build Java daemon
cd java && ./gradlew jar && cd ..

# Build TypeScript adapters
cd adapters && npm install && npx tsc && cd ..
```

### Configure your AI agent

Add Helm as an MCP server in your agent's config. Example for OpenCode (`opencode.jsonc`):

```jsonc
{
  "mcp": {
    "helm": {
      "type": "local",
      "command": ["sh", "-c", "cd /path/to/helm && (./helm health >/dev/null 2>&1 || (./helm _daemon </dev/null >/dev/null 2>/dev/null & sleep 3)) && ./helm open >/dev/null 2>&1 && exec ./helm bridge"],
      "enabled": true
    }
  }
}
```

### Verify

```bash
./helm start
./helm health       # {"status":"ok","state":"CLOSED"}
./helm open         # {"state":"OPEN","session_id":"..."}
./helm status
```

### Web search setup (optional)

```bash
# Get a free API key at https://api-dashboard.search.brave.com/
mkdir -p ~/.config/mcphub
echo "YOUR_BRAVE_API_KEY" > ~/.config/mcphub/brave-api-key
chmod 600 ~/.config/mcphub/brave-api-key
```

## What It Solves

| Problem | Status |
|---------|--------|
| Tool body-size API errors | Resolved |
| ToolSearch deferred loading (3-8s) | Eliminated |
| Tool exposure governance | Working |
| Route logging and observability | Working |
| Multi-MCP-server aggregation | Working |
| Disambiguation | Working (contract-based resolution) |
| Body-budget monitoring | Working |
| Intent annotation | Working (schema-documented, route-logged, secret-scrubbed) |
| Session-scoped policy rules | Working |
| Structured failure recovery | Working (error codes, fallback guidance, next actions) |

## What It Does Not Solve Yet

| Item | Status |
|------|--------|
| Task-context filtering | Planned |
| Result caching | Planned |
| Dry-run mode | Planned |
| Management UI | Not started |
| Full LSP support | Alpha stub |

## Hosted Tools

| Adapter | Tools | Type |
|---------|-------|------|
| web | `webfetch`, `websearch` | builtin |
| edit | `apply_patch` | builtin |
| project | `todowrite`, `list`, `codesearch`, `lsp` | builtin |
| session | `plan_enter`, `plan_exit`, `skill`, `batch` | builtin |

## Relay Providers

Relay providers are external MCP servers that Helm connects to via subprocess stdio. Any MCP server that speaks JSON-RPC over stdio can be added as a relay provider.

To configure relay providers:

1. Copy the example config:
   ```bash
   mkdir -p ~/.config/mcphub
   cp java/src/main/resources/relays-example.yaml ~/.config/mcphub/relays.yaml
   ```

2. Edit `~/.config/mcphub/relays.yaml` and add your relay entries. Each relay defines a subprocess command and the tools it exposes.

3. Alternatively, set a custom path via environment variable:
   ```bash
   export MCPHUB_RELAYS_PATH=/path/to/custom-relays.yaml
   ```

Relay tools are loaded dynamically at startup and appear alongside built-in tools in `tools/list`.

## Performance

| Metric | Value | Target |
|--------|-------|--------|
| p50 latency | 1ms | <50ms |
| p99 latency | 3ms | <100ms |

## CLI

```
helm start          Start the daemon
helm stop           Stop the daemon
helm health         Liveness check
helm status         Current state and session info
helm open           Open a session (idempotent)
helm close          Close the current session
helm lock           Emergency lock
helm capabilities   List registered tools
helm bridge         Run stdio bridge (used by AI agents)
```

The daemon also exposes a control surface for runtime policy management:

```
mcphub.control.add_session_rule   Add a session-scoped allow/deny/hide rule
```

Session rules are automatically purged when the session closes.

### Bridge Lifecycle

When an AI agent connects via `helm bridge`, the bridge automatically registers with the daemon (`bridge_attach`). While at least one bridge is connected, the session idle timeout (default 300s) is suspended — tools remain visible for the entire agent session.

When the last bridge disconnects, a 300s grace period begins. If no bridge reconnects within that window, the session closes and providers are stopped. A 60s heartbeat detects crashed bridges that failed to send a clean disconnect.

## Project Status

**Alpha (daily driver since 2026-04-19)**

Built by [Sorted.](https://github.com/YumaKakuya) for AI multi-agent orchestration.

## License

MIT -- see [LICENSE](LICENSE).
