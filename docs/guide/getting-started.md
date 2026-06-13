# Getting Started with MCPHUB

Get MCPHUB running in under 5 minutes.

## Prerequisites

- Java 21+ (JRE or JDK)
- Go 1.25+ (for building from source)
- Node.js 20+ (for TypeScript adapters)

## 1. Build

```bash
git clone https://github.com/OWNER/REPO.git
cd MCPHUB
make
```

This builds three components:
- Java fat-JAR (`java/build/libs/mcphub-core.jar`)
- Go launcher binary (`./mcphub`)
- TypeScript adapters (`adapters/dist/`)

## 2. Start the daemon

```bash
./mcphub start
```

Verify it's running:

```bash
./mcphub health
# {"status":"ok","state":"CLOSED"}
```

The daemon starts in `CLOSED` state. No tools are exposed until you open a session.

## 3. Connect your AI agent

Add MCPHUB as an MCP server in your agent config. The shell command handles daemon auto-start, session opening, and bridge attachment.

Replace `/path/to/MCPHUB` with your actual MCPHUB directory.

### OpenCode / Hatch

File: `opencode.jsonc` or `~/.config/opencode/opencode.jsonc`

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

### Claude Code

File: `~/.claude.json`

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

### Cursor

In Cursor Settings > MCP > Add Server:

- Type: `stdio`
- Command: `sh`
- Args: `-c`, `cd /path/to/MCPHUB && (./mcphub health >/dev/null 2>&1 || (./mcphub _daemon </dev/null >/dev/null 2>/dev/null & sleep 3)) && ./mcphub open >/dev/null 2>&1 && exec ./mcphub bridge`

## 4. Verify tools are available

Once connected, the AI agent should see MCPHUB's tools. The default configuration includes 11 hosted tools plus `mcphub_disambiguate`.

You can manually verify the tool list:

```bash
./mcphub open
./mcphub capabilities
```

## 5. Optional: Web search

MCPHUB includes a `websearch` tool powered by Brave Search API.

```bash
# Get a free API key at https://api-dashboard.search.brave.com/
mkdir -p ~/.config/mcphub
echo "YOUR_BRAVE_API_KEY" > ~/.config/mcphub/brave-api-key
chmod 600 ~/.config/mcphub/brave-api-key
```

## Session lifecycle

MCPHUB uses an explicit session lifecycle:

```
CLOSED  -->  ARMED  -->  OPEN  -->  COOLING_DOWN  -->  CLOSED
```

- **CLOSED**: Daemon running, no tools exposed. Default state on startup.
- **ARMED**: Session prepared. Providers are starting. Auto-closes after 60s if not opened.
- **OPEN**: Tools are available to the AI agent. Auto-closes after 5 minutes of inactivity.
- **COOLING_DOWN**: Session ending, in-flight requests draining. Transitions to CLOSED.

The shell command in the agent config handles `open` automatically. For manual control:

```bash
./mcphub open        # CLOSED -> ARMED -> OPEN (idempotent)
./mcphub close       # OPEN -> COOLING_DOWN -> CLOSED
./mcphub lock        # Emergency: immediately CLOSED + locked
./mcphub unlock      # Clear emergency lock
```

## Stopping the daemon

```bash
./mcphub stop
```

## What's next

- [Relay Provider Setup](relay-setup.md) -- Connect external MCP servers
- [Policy Configuration](policy-guide.md) -- Allow/deny/hide rules per tool
- [API Reference](api-reference.md) -- Full control surface and MCP endpoints

---

*MCPHUB Getting Started Guide -- BL-08*
