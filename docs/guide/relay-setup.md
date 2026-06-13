# Relay Provider Setup

MCPHUB can aggregate external MCP servers behind its single endpoint. Any MCP-compatible server that speaks stdio JSON-RPC can be added as a relay provider.

## How relay providers work

```
AI Agent
  |
  |  stdio
  v
MCPHUB (Java daemon)
  |
  |-- builtin adapters (web, edit, project, session)
  |-- relay: Coffer (secret vault)
  |-- relay: AXIS. (context engine)
  |-- relay: your-custom-server    <-- this guide
  +-- relay: any MCP server
```

When the AI calls a tool, MCPHUB routes the request to the correct provider based on tool-name-to-group mapping. The AI sees a single flat tool list regardless of how many providers exist behind MCPHUB.

## Configuration file

Relay providers are defined in:

```
~/.config/mcphub/relays.yaml
```

Override with the `MCPHUB_RELAYS_PATH` environment variable.

A starter example is bundled at `java/src/main/resources/relays-example.yaml`.

## Minimal example

Connect the official MCP filesystem server:

```bash
npm install -g @modelcontextprotocol/server-filesystem
```

Add to `~/.config/mcphub/relays.yaml`:

```yaml
relays:
  - id: "filesystem"
    command: ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp/test"]
    tools:
      - "read_file"
      - "write_file"
      - "list_directory"
    capabilities:
      - capability_id: "read_file"
        display_name: "read_file"
        provider_id: "filesystem"
        access_class: "safe"
        rw_boundary: "read"
        enabled: true
        priority: 10
        schema:
          type: object
          properties:
            path:
              type: string
              description: "File path to read"
          required: [path]
        contract:
          purpose: "Read a file from the configured filesystem root."
          may_do: ["Return file contents from the configured root"]
          must_not_do: ["Read outside the configured root"]
          when_to_call: ["A file from the relay root needs to be read"]
          side_effect_class: "none"
          timeout_hint_ms: 10000
      - capability_id: "write_file"
        display_name: "write_file"
        provider_id: "filesystem"
        access_class: "guarded"
        rw_boundary: "write"
        enabled: true
        priority: 10
        schema:
          type: object
          properties:
            path:
              type: string
              description: "File path to write"
            content:
              type: string
              description: "File contents to write"
          required: [path, content]
        contract:
          purpose: "Write a file under the configured filesystem root."
          may_do: ["Create or replace files under the configured root"]
          must_not_do: ["Write outside the configured root"]
          when_to_call: ["The user explicitly wants to write a file through this relay"]
          side_effect_class: "local_state"
          timeout_hint_ms: 10000
      - capability_id: "list_directory"
        display_name: "list_directory"
        provider_id: "filesystem"
        access_class: "safe"
        rw_boundary: "read"
        enabled: true
        priority: 10
        schema:
          type: object
          properties:
            path:
              type: string
              description: "Directory path to list"
          required: [path]
        contract:
          purpose: "List a directory under the configured filesystem root."
          may_do: ["Return directory entries under the configured root"]
          must_not_do: ["List outside the configured root"]
          when_to_call: ["Directory contents under the relay root are needed"]
          side_effect_class: "none"
          timeout_hint_ms: 10000
```

Restart MCPHUB to pick up the change:

```bash
./mcphub stop && ./mcphub start
```

Verify:

```bash
./mcphub open
./mcphub capabilities | grep filesystem
```

## Full format

Each relay entry supports:

```yaml
relays:
  - id: "unique-relay-id"           # Required. Group identifier.
    command: ["/path/to/binary", "arg1", "arg2"]  # Required. Stdio MCP server command.
    tools: ["tool_a", "tool_b"]     # Required. Tool names this relay provides.
    capabilities:                   # Required for AI-visible tools not built into MCPHUB.
      - capability_id: "tool_a"
        display_name: "tool_a"
        provider_id: "unique-relay-id"
        access_class: "safe"        # safe | guarded | restricted
        rw_boundary: "read"         # read | write | execute
        enabled: true
        priority: 10
        schema:
          type: object
          properties:
            query:
              type: string
              description: "Search query"
          required: [query]
        contract:
          purpose: "What this tool does"
          may_do:
            - "Allowed action 1"
          must_not_do:
            - "Forbidden action 1"
          when_to_call:
            - "Use when..."
          when_not_to_call:
            - "Don't use when..."
          side_effect_class: "none"  # none | local_state | external_state
          timeout_hint_ms: 30000
          disambiguates_from:
            - capability_id: "other_tool"
              distinction: "This tool does X; other_tool does Y"
```

### Field reference

| Field | Required | Description |
|-------|----------|-------------|
| `id` | Yes | Unique identifier for this relay group |
| `command` | Yes | Array of strings: the command to spawn the MCP server process |
| `tools` | Yes | Array of tool names this relay provides |
| `capabilities` | For external tools | Full capability definitions with schema, contract, and policy metadata. External relay tools that are not already in MCPHUB's embedded registry need this block to appear in `tools/list`. |

### Capability fields

| Field | Description |
|-------|-------------|
| `capability_id` | Unique tool identifier (must match a name in `tools`) |
| `display_name` | Name exposed to the AI in `tools/list` |
| `provider_id` | Provider group identifier (use the relay `id`) |
| `access_class` | Risk classification: `safe`, `guarded`, or `restricted` |
| `rw_boundary` | Operation type: `read`, `write`, or `execute` |
| `enabled` | Whether the tool is active (`true` / `false`) |
| `priority` | Numeric priority for routing (higher = preferred) |
| `schema` | JSON Schema for the tool's input parameters |
| `contract` | Capability contract (purpose, may_do, must_not_do, etc.) |

### Contract fields

The contract helps MCPHUB and the AI understand when and how to use the tool:

| Field | Description |
|-------|-------------|
| `purpose` | One-line description shown to the AI |
| `may_do` | List of permitted actions |
| `must_not_do` | List of forbidden actions |
| `when_to_call` | Guidance on when the AI should use this tool |
| `when_not_to_call` | Guidance on when the AI should NOT use this tool |
| `side_effect_class` | `none`, `local_state`, or `external_state` |
| `timeout_hint_ms` | Expected max execution time in milliseconds |
| `disambiguates_from` | Array of `{capability_id, distinction}` pairs for conflict resolution |

## Without capability definitions

Do not omit the `capabilities` block for new external tools. MCPHUB confirms relay tools at session open, but it does not dynamically create AI-visible registry entries from a relay `tools/list` response. A tool that is listed under `tools` but lacks a matching capability entry will not be exposed in MCPHUB `tools/list` unless it is already present in the embedded registry.

For each external tool, define at least `schema` and `contract.purpose`; for production use, provide the full contract vocabulary so policy, disambiguation, and capability-gap diagnostics remain accurate.

When a relay provider updates its own `tools/list` schemas, refresh the static capability schemas in `~/.config/mcphub/relays.yaml` and restart MCPHUB so the AI-visible tool signatures match the provider.

## Provider lifecycle

Relay providers follow the same lifecycle as builtin adapters:

1. **Session Open**: MCPHUB spawns the relay process and calls `tools/list` to confirm available tools
2. **Active session**: MCPHUB routes `tools/call` requests to the relay via stdio JSON-RPC
3. **Session Close**: MCPHUB sends SIGTERM, waits 3 seconds, then SIGKILL if needed
4. **Crash recovery**: If the relay exits unexpectedly, MCPHUB retries up to 3 times within 60 seconds. After 3 failures, the provider is marked `degraded`.

## Error handling

If a relay is unreachable or its binary doesn't exist, MCPHUB returns a structured error:

```json
{
  "content": [{"type": "text", "text": "[MCPHUB error] provider_unavailable: ..."}],
  "isError": true
}
```

The AI receives this error and can suggest corrective action.

## Debugging

Check provider health:

```bash
./mcphub open
./mcphub capabilities --json | python3 -m json.tool
```

Each capability entry includes a `provider_health` field: `running`, `stopped`, or `unavailable`.

Check route logs for relay calls:

```bash
./mcphub query --sql "SELECT tool_name, provider_id, route_decision, error_code FROM route_log ORDER BY id DESC LIMIT 10"
```

---

*MCPHUB Relay Provider Setup Guide -- BL-09*
