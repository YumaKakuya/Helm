# MCPHUB

A tool hub and governance layer for AI coding agents. One MCP endpoint, many providers, less context waste.

## The Problem

AI coding agents load ALL tool schemas into every API request. As MCP servers grow, this becomes a critical bottleneck:

- **Context bloat**: 5+ MCP servers consume 40,000-67,000 tokens before any user interaction
- **API failures**: Heavy setups hit 147,000 tokens (73% of 200K context window), triggering API errors
- **Deferred loading latency**: ToolSearch workarounds add 3-8 seconds per tool use
- **Cost waste**: Tool schemas burn tokens every turn, regardless of whether tools are used

This is a known problem in the OpenCode ecosystem: [#9350](https://github.com/anomalyco/opencode/issues/9350) (85% token reduction request), [#8625](https://github.com/anomalyco/opencode/issues/8625), [#16206](https://github.com/anomalyco/opencode/issues/16206), [#20489](https://github.com/anomalyco/opencode/issues/20489).

## What MCPHUB Does

MCPHUB sits between your AI agent and your tool providers. Instead of loading every tool schema into every API call, the agent connects to one MCP endpoint and MCPHUB routes requests to the right provider.

```
AI Agent (OpenCode / Claude Code / Cursor)
    |
    |  stdio (single MCP connection)
    v
+-- MCPHUB ----------------------------------------+
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
- **Result caching** for read-only tools (session-scoped, eliminates duplicate calls)
- **Dry-run mode** (preview destructive tool effects before execution)
- **Task-context filtering** (show only relevant tools per task: coding/research/planning)
- **Capability-gap explanation** (structured feedback when tools are denied or missing)
- **LSP support** (hover, go-to-definition, references for TypeScript and Go)

## Quick Start

### Option 1: Prebuilt Binary (no build tools needed)

Download the latest release for your platform from the release page of the repository you are installing from:

```bash
# Linux (amd64)
curl -L https://github.com/<owner>/<repo>/releases/latest/download/mcphub-linux-amd64.tar.gz | tar xz
cd mcphub

# macOS (Apple Silicon)
curl -L https://github.com/<owner>/<repo>/releases/latest/download/mcphub-darwin-arm64.tar.gz | tar xz
cd mcphub
```

Requires: Java 21+ runtime, Node.js 18+

### Option 2: Docker (zero dependencies)

```bash
docker run --rm -it ghcr.io/<owner>/<image>
```

### Option 3: Build from source

Prerequisites: Java 21+, Go 1.25+, Node.js 20+, Linux or macOS (Windows via WSL)

```bash
git clone https://github.com/<owner>/<repo>.git
cd <repo>
make
```

This builds all three components (Java fat-JAR, Go launcher, TypeScript adapters) in one command.

Other useful targets:

```bash
make test       # Run all tests (Java + Go)
make install    # Build + install to ~/.local
make release    # Build release archives (all platforms)
make clean      # Remove all build artifacts
make dev        # Build + run daemon (foreground)
make help       # Show all targets
```

### Configure your AI agent

Add MCPHUB as an MCP server in your agent's config. The shell wrapper handles daemon startup, session opening, and bridge connection automatically.

Replace `/path/to/MCPHUB` with the actual path to your MCPHUB directory.

#### OpenCode / Hatch (`opencode.jsonc`)

```jsonc
{
  "mcp": {
    "mcphub": {
      "type": "local",
      "command": ["sh", "-c", "cd /path/to/MCPHUB && (./mcphub health >/dev/null 2>&1 || (./mcphub _daemon </dev/null >/dev/null 2>/dev/null & sleep 3)) && ./mcphub open >/dev/null 2>&1 && exec ./mcphub bridge"],
      "enabled": true
    }
  }
}
```

#### Claude Code (`~/.claude.json`)

```json
{
  "mcpServers": {
    "mcphub": {
      "command": "sh",
      "args": ["-c", "cd /path/to/MCPHUB && (./mcphub health >/dev/null 2>&1 || (./mcphub _daemon </dev/null >/dev/null 2>/dev/null & sleep 3)) && ./mcphub open >/dev/null 2>&1 && exec ./mcphub bridge"]
    }
  }
}
```

#### Cursor (MCP Settings)

In Cursor Settings > MCP, add a new server:

```json
{
  "mcpServers": {
    "mcphub": {
      "command": "sh",
      "args": ["-c", "cd /path/to/MCPHUB && (./mcphub health >/dev/null 2>&1 || (./mcphub _daemon </dev/null >/dev/null 2>/dev/null & sleep 3)) && ./mcphub open >/dev/null 2>&1 && exec ./mcphub bridge"]
    }
  }
}
```

### Verify

```bash
# Start daemon
./mcphub start

# Check health
./mcphub health
# {"status":"ok","state":"CLOSED"}

# Open session
./mcphub open
# {"state":"OPEN","session_id":"..."}

# Check status
./mcphub status
```

### Web search setup (optional)

MCPHUB includes a websearch tool powered by Brave Search API:

```bash
# Get a free API key at https://api-dashboard.search.brave.com/
mkdir -p ~/.config/mcphub
echo "YOUR_BRAVE_API_KEY" > ~/.config/mcphub/brave-api-key
chmod 600 ~/.config/mcphub/brave-api-key
```

## What It Solves

| Problem | Status | Details |
|---------|--------|---------|
| Tool body-size API errors | Resolved | Tools on classifier-invisible MCP path |
| ToolSearch deferred loading (3-8s) | Eliminated | Direct tool access, no roundtrip |
| Tool exposure governance | Working | Allow/deny policy, session lifecycle |
| Route logging | Working | Every tool call logged with routing decision |
| Multi-MCP-server aggregation | Working | Single endpoint for all providers |
| Disambiguation | Working | AI can ask which tool is appropriate |
| Body-budget monitoring | Working | Alerts before regressions become visible |

## What It Does Not Solve Yet

| Item | Status | Notes |
|------|--------|-------|
| Management UI | Not started | CLI only for now |

## Hosted Tools

MCPHUB includes adapters for commonly needed tools, plus hub-level tools:

| Adapter | Tools |
|---------|-------|
| web | `webfetch`, `websearch` |
| edit | `apply_patch` |
| project | `todowrite`, `list`, `codesearch`, `lsp`, `task_create`, `task_list`, `task_update`, `task_delete` |
| nexus | `nexus_issue_create`, `nexus_issue_list`, `nexus_issue_close`, `nexus_issue_update` |
| session | `plan_enter`, `plan_exit`, `skill`, `batch` |
| hub | `mcphub_disambiguate`, `mcphub_set_task_context`, `mcphub.session.open`, `mcphub_checkpoint` |

When the session is CLOSED or ARMED, only `mcphub.session.open` is exposed in the tool list. If the client/model cannot call that MCP tool directly, run `mcphub open` from the CLI.

## Relay Providers

MCPHUB can relay requests to external MCP servers. Any MCP-compatible server can be added as a relay provider, aggregated behind the single MCPHUB endpoint.

## Performance

Measured overhead (excluding upstream provider execution time):

| Metric | Value | Target |
|--------|-------|--------|
| p50 latency | 1ms | <50ms |
| p99 latency | 3ms | <100ms |

## CLI Reference

```
mcphub start          Start the daemon
mcphub stop           Stop the daemon
mcphub health         Liveness check
mcphub status         Current state, session info, uptime
mcphub open           Arm + open a session (idempotent)
mcphub close          Close the current session
mcphub lock           Emergency lock (rejects all tool calls)
mcphub unlock         Clear emergency lock
mcphub capabilities   List registered tools and their status
mcphub bridge         Run stdio bridge (used by AI agents)
mcphub version        Print version
mcphub query --sql    Read-only SQL against mcphub.db
```

## Documentation

- [Getting Started](docs/guide/getting-started.md) -- 5-minute onboarding
- [Relay Provider Setup](docs/guide/relay-setup.md) -- Connect external MCP servers
- [Policy Configuration](docs/guide/policy-guide.md) -- Allow/deny/hide rules
- [API Reference](docs/guide/api-reference.md) -- Full control surface and MCP endpoints
- [Benchmark Report](docs/guide/benchmark-report.md) -- Performance measurements
- [Context Reduction Report](docs/guide/context-reduction-report.md) -- Token savings analysis

## Project Structure

```
cmd/mcphub/       Go launcher (JRE discovery + process management)
java/             Java daemon (state machine, policy, routing, registry)
adapters/         TypeScript tool adapters (web, edit, project, session)
integration/      Integration tests
packaging/        systemd / launchd service templates
install.sh        Install script
```

## Project Status

**Alpha (daily driver since 2026-04-19)**

Built by [Sorted.](https://github.com/OWNER) as part of the AXIOM product line for AI multi-agent orchestration.

## License

MIT -- see [LICENSE](LICENSE).
