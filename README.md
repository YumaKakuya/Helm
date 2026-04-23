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
    +---------+---------+---------+---------+
    |  web    |  edit   | project | session |  <-- TypeScript adapters
    +---------+---------+---------+---------+
    | relay providers (Coffer, custom MCP)  |
    +--------------------------------------+
```

Core capabilities:

- **Single MCP endpoint** aggregating multiple tool providers
- **Classifier-invisible path** for tools (avoids Anthropic API body-size limits)
- **Session lifecycle** (Closed / Armed / Open / CoolingDown) with auto-close
- **Allow/deny policy** per tool
- **Route logging** for observability
- **Disambiguation endpoint** (AI asks the hub which tool to use)
- **Body-budget monitoring** to detect regressions early

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
| Disambiguation | Working |
| Body-budget monitoring | Working |

## What It Does Not Solve Yet

| Item | Status |
|------|--------|
| Task-context filtering | Planned |
| Intent annotation | Planned |
| Result caching | Planned |
| Dry-run mode | Planned |
| Management UI | Not started |
| Full LSP support | Alpha stub |

## Hosted Tools

| Adapter | Tools |
|---------|-------|
| web | `webfetch`, `websearch` |
| edit | `apply_patch` |
| project | `todowrite`, `list`, `codesearch`, `lsp` |
| session | `plan_enter`, `plan_exit`, `skill`, `batch` |

If your agent already has these capabilities built in, use Helm purely as a relay aggregator for your other MCP servers.

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

## Project Status

**Alpha (daily driver since 2026-04-19)**

Built by [Sorted.](https://github.com/YumaKakuya) as part of the AXIOM product line for AI multi-agent orchestration.

## License

MIT -- see [LICENSE](LICENSE).
