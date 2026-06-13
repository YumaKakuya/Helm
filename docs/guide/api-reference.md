# API Reference

MCPHUB exposes two protocol surfaces: a **control surface** for daemon management (over UDS) and an **MCP surface** for AI tool access (over stdio).

## Architecture overview

```
CLI commands              AI Agent
    |                        |
    |  UDS                   |  stdio (JSON-RPC 2.0)
    v                        v
+-- Java Daemon ----------- Java Bridge --------+
|   (long-lived)             (per-session)       |
|   Control Surface          MCP Surface         |
|   mcphub.control.*         initialize          |
|                            tools/list          |
|                            tools/call          |
+------------------------------------------------+
```

- **Control surface**: UDS at `$XDG_RUNTIME_DIR/mcphub/daemon.sock` (or `$TMPDIR/mcphub/daemon.sock`). Override with `MCPHUB_SOCKET_PATH`.
- **MCP surface**: stdio (stdin/stdout) via `mcphub bridge`. The bridge connects to the daemon via UDS internally.

All messages use JSON-RPC 2.0 format.

---

## CLI Commands

The Go launcher binary (`mcphub`) passes all subcommands through to Java.

| Command | Description | Category |
|---------|-------------|----------|
| `mcphub start` | Start the daemon (backgrounds by default) | Lifecycle |
| `mcphub start --no-daemon` | Start in foreground | Lifecycle |
| `mcphub stop` | Stop the daemon gracefully | Lifecycle |
| `mcphub restart` | Stop then start | Lifecycle |
| `mcphub health` | Liveness check | Observability |
| `mcphub status` | State, session, uptime | Observability |
| `mcphub open` | Arm + open session (idempotent) | Session |
| `mcphub close` | Close session | Session |
| `mcphub lock` | Emergency lock | Session |
| `mcphub unlock` | Clear emergency lock | Session |
| `mcphub capabilities` | List all registered tools | Registry |
| `mcphub bridge` | Run stdio bridge for AI client | Transport |
| `mcphub version` | Print version | Info |
| `mcphub query --sql "..."` | Read-only SQL against mcphub.db | Debug |
| `mcphub config validate` | Validate config file | Config |

All commands accept `--json` for machine-readable output.

---

## Control Surface Methods

Accessible via UDS (used by CLI and bridge internally).

### mcphub.control.health

Liveness check.

**Response:**

```json
{"status": "ok", "state": "CLOSED"}
```

### mcphub.control.status

Full daemon status.

**Response:**

```json
{
  "state": "OPEN",
  "session_id": "a1b2c3d4-...",
  "seconds_since_transition": 42,
  "in_flight_count": 0,
  "locked_until_unlock": false,
  "uptime_seconds": 3600
}
```

### mcphub.control.arm

Transition: `CLOSED -> ARMED`. Creates a new session.

**Response:**

```json
{"state": "ARMED", "session_id": "a1b2c3d4-..."}
```

**Errors:**
- `-32001`: `Cannot arm: hub is locked.` (if emergency lock is active)
- `-32001`: `Illegal transition` (if not in CLOSED state)

### mcphub.control.open

Transition: `ARMED -> OPEN`. Starts provider processes.

**Response:**

```json
{"state": "OPEN", "session_id": "a1b2c3d4-..."}
```

### mcphub.control.close

Transition: `OPEN -> COOLING_DOWN -> CLOSED` or `ARMED -> CLOSED`.

**Response:**

```json
{"state": "CLOSED"}
```

### mcphub.control.lock

Emergency lock. Immediately transitions to CLOSED from any state. Prevents new sessions until unlocked.

**Params (optional):**

```json
{"lock_reason": "manual"}
```

**Response:**

```json
{"state": "CLOSED", "locked_until_unlock": true, "lock_reason": "manual"}
```

The lock persists across daemon restarts.

### mcphub.control.unlock

Clear emergency lock.

**Response:**

```json
{"state": "CLOSED", "locked_until_unlock": false}
```

### mcphub.control.capabilities

List all registered tools with full contract and health information.

**Response:**

```json
{
  "capabilities": [
    {
      "capability_id": "webfetch",
      "display_name": "webfetch",
      "provider_id": "builtin-hatch",
      "access_class": "restricted",
      "rw_boundary": "execute",
      "enabled": true,
      "provider_health": "running",
      "policy_decision": "allowed",
      "policy_rule_id": "default-allow-all",
      "contract": {
        "purpose": "Fetch content from a URL...",
        "side_effect_class": "external_state",
        "may_do": ["..."],
        "must_not_do": ["..."],
        "when_to_call": ["..."],
        "when_not_to_call": ["..."],
        "disambiguates_from": [
          {"capability_id": "websearch", "distinction": "..."}
        ]
      }
    }
  ],
  "loaded_count": 11,
  "rejected_count": 0
}
```

### mcphub.control.bridge_detach

Signals that a bridge process has disconnected. When the last bridge disconnects, the session returns to idle-timeout tracking. The default idle timeout is 300 seconds.

**Response:**

```json
{"status": "ok", "active_bridges": 0}
```

---

## MCP Surface (AI-facing)

Accessible via stdio through `mcphub bridge`. Follows the [MCP protocol spec](https://modelcontextprotocol.io/).

### initialize

MCP handshake.

**Response:**

```json
{
  "protocolVersion": "2024-11-05",
  "capabilities": {"tools": {}},
  "serverInfo": {"name": "mcphub", "version": "0.2.0-alpha"}
}
```

The `serverInfo.name` can be overridden via `server_name` in the config file.

### tools/list

Returns tools visible to the AI. Only returns results when the session is `OPEN`.

Filtering applied:
1. Tool must be `enabled` in the registry
2. Tool's provider must be confirmed (adapter registered via `tools/list`)
3. Policy decision must be `ALLOW` (denied and hidden tools are excluded)

**Response:**

```json
{
  "tools": [
    {
      "name": "webfetch",
      "description": "Fetch content from a URL... [modifies external state]",
      "inputSchema": {
        "type": "object",
        "properties": {
          "url": {"type": "string", "description": "URL to fetch"}
        },
        "required": ["url"]
      }
    },
    {
      "name": "mcphub_disambiguate",
      "description": "Query MCPHUB to determine which tool is most appropriate...",
      "inputSchema": {"type": "object", "properties": {...}}
    }
  ]
}
```

Description annotations:
- `[modifies external state]` is appended for tools with `access_class: restricted`
- `(read-only)` is appended for tools with `rw_boundary: read`

### tools/call

Route a tool call to the appropriate provider.

**Request params:**

```json
{
  "name": "webfetch",
  "arguments": {"url": "https://example.com"},
  "_intent": "Fetching the project homepage to check current status"
}
```

The optional `_intent` field is logged for debugging (max 500 chars).

**Success response:** Provider-dependent. Typically:

```json
{
  "content": [
    {"type": "text", "text": "...result..."}
  ]
}
```

**Error responses:**

| Error code | Meaning | `next_action` hint |
|------------|---------|-------------------|
| `session_not_open` | Session is not in OPEN state | `call_mcphub_session_open` |
| `tool_not_found` | Tool is not registered (or hidden) | `use_alternative` |
| `tool_denied` | Tool is denied by policy | `abort` |
| `provider_unreachable` | Provider process could not be reached | `wait_session` |
| `provider_error` | Provider returned an error | `retry` |
| `internal_error` | Hub-internal failure | `abort` |

Error response format:

```json
{
  "content": [
    {
      "type": "text",
      "text": "[MCPHUB error] tool_denied: Tool 'websearch' is denied by policy rule: deny-websearch | next_action: abort | policy: deny-websearch"
    },
    {
      "type": "text",
      "text": "[MCPHUB capability_gap] {\"gap_type\":\"policy_restriction\",\"explanation\":\"...\",\"premium_feature_id\":\"policy_governance\"}"
    }
  ],
  "isError": true,
  "capability_gap": {
    "gap_type": "policy_restriction",
    "explanation": "Tool 'websearch' is registered but denied by policy rule: deny-websearch",
    "premium_feature_id": "policy_governance"
  }
}
```

When `tool_not_found` or `provider_unreachable`, the error includes `available_tools` listing currently accessible tools. When the failure class maps deterministically to a known capability difference, the response may include `capability_gap` (OS-15 restored Alpha scope).

### mcphub_disambiguate (via tools/call)

AI asks MCPHUB which tool to use for a task.

**Request:**

```json
{
  "name": "mcphub_disambiguate",
  "arguments": {
    "task_description": "I need to search for files containing 'TODO'",
    "candidate_tools": ["codesearch", "websearch"]
  }
}
```

**Response (deterministic -- exactly one candidate):**

```json
{
  "recommended_tool": "codesearch",
  "confidence": "deterministic",
  "reason": "Exactly one tool is available...",
  "alternatives": []
}
```

**Response (multiple candidates -- no heuristic guessing):**

```json
{
  "recommended_tool": null,
  "confidence": "none",
  "reason": "2 tools are available. Hub cannot select without heuristic guessing...",
  "alternatives": [
    {"tool": "codesearch", "reason": "Search the local codebase..."},
    {"tool": "websearch", "reason": "Search the web..."}
  ]
}
```

MCPHUB never guesses. If there is exactly one candidate, it returns a deterministic answer. Otherwise, it returns all alternatives and lets the AI decide.

---

## Configuration

### Config file

Path: `~/.local/share/mcphub/config.yaml` (override with `MCPHUB_DATA_DIR`)

```yaml
# MCP server name (shown in initialize response)
server_name: "mcphub"

# Session timeouts
session:
  idle_timeout_seconds: 300      # Auto-close after inactivity (default: 300)
  armed_timeout_seconds: 60      # Auto-close ARMED if not opened (default: 60)

# Body-budget monitoring thresholds
body_budget:
  warning_tool_count: 20
  critical_tool_count: 30
  warning_byte_size: 40000
  critical_byte_size: 60000
  total_registered_hatch_tools: 19
  baseline_schema_bytes_per_tool: 2048
```

### Relay config

Path: `~/.config/mcphub/relays.yaml` (override with `MCPHUB_RELAYS_PATH`)

See [Relay Provider Setup](relay-setup.md).

### Environment variables

| Variable | Description | Default |
|----------|-------------|---------|
| `MCPHUB_DATA_DIR` | Data directory (DB, config, PID) | `~/.local/share/mcphub` |
| `MCPHUB_SOCKET_PATH` | UDS socket path | `$XDG_RUNTIME_DIR/mcphub/daemon.sock` |
| `MCPHUB_RELAYS_PATH` | Relay config file path | `~/.config/mcphub/relays.yaml` |
| `MCPHUB_ADAPTER_DIR` | TypeScript adapter directory | Auto-detected |
| `MCPHUB_TEST_FIXTURE` | Additional capabilities YAML (testing only) | None |

---

## Database

SQLite at `$MCPHUB_DATA_DIR/mcphub.db`.

### Tables

| Table | Purpose |
|-------|---------|
| `schema_version` | Migration tracking |
| `route_log` | Every tool call with routing decision, latency, policy |
| `failure_log` | Provider failures with recovery action |
| `body_budget_snapshot` | Context size metrics on session open/close |
| `session_log` | State transitions with timestamps |
| `lock_metadata` | Write lock and emergency lock persistence |
| `rollback_checkpoint` | Config snapshots (reserved) |
| `loaded_state` | Provider loaded state per session (reserved) |

### Querying

```bash
# Recent tool calls
./mcphub query --sql "SELECT timestamp_utc, tool_name, route_decision, latency_ms FROM route_log ORDER BY id DESC LIMIT 20"

# Failed calls
./mcphub query --sql "SELECT * FROM route_log WHERE route_decision != 'allowed' ORDER BY id DESC"

# Session history
./mcphub query --sql "SELECT * FROM session_log ORDER BY id DESC LIMIT 20"

# Body budget snapshots
./mcphub query --sql "SELECT * FROM body_budget_snapshot ORDER BY id DESC LIMIT 5"

# Provider failures
./mcphub query --sql "SELECT * FROM failure_log ORDER BY id DESC LIMIT 10"
```

---

## Tool Surface: Session Recovery

When the MCPHUB session is CLOSED or ARMED, the tool list returns exactly one tool: `mcphub.session.open`. If a client/model cannot call that MCP tool directly, run `mcphub open` from the CLI.

### mcphub.session.open

Open the MCPHUB session to make all tools available. CLI fallback: `mcphub open`.

**Request params:** `{}` (empty object)

**Response (success):**

```json
{
  "content": [{"type": "text", "text": "{\"state\":\"OPEN\",\"session_id\":\"...\",\"message\":\"Session opened. All tools are now available.\"}"}],
  "mcphub_providers": "start"
}
```

If the session is already OPEN, the response is idempotent: `{"state":"OPEN","message":"Session already open."}`.

If the session is in COOLING_DOWN or LOCKED state, the response includes an actionable error:

```json
{
  "content": [{"type": "text", "text": "[MCPHUB error] session_not_open: Cannot open session in state: COOLING_DOWN. Wait and retry. | next_action: wait_session"}],
  "isError": true
}
```

### Session State Error Recovery

When any tool is called while the session is CLOSED or ARMED, the error response provides an actionable recovery path:

```json
{
  "content": [{"type": "text", "text": "[MCPHUB error] session_not_open: Session is not Open. Call mcphub.session.open to reopen the session. If the client cannot call that MCP tool, run CLI: mcphub open | cli_recovery_command: mcphub open | next_action: call_mcphub_session_open"}],
  "isError": true
}
```

**Key change from prior versions:** The `next_action` is now `call_mcphub_session_open` (pointing to the available AI-facing tool) and the text includes `cli_recovery_command: mcphub open` for clients that cannot invoke the recovery tool.

---

## Nexus Issue Tools

MCPHUB provides persistent cross-session issue tracking via the Nexus adapter.

### nexus_issue_create

Create a new issue in `~/issue-store/<project>/`.

**Request params:**

```json
{
  "project": "mcphub",
  "title": "Critical bug found",
  "body": "Description of the issue...",
  "priority": "HIGH"
}
```

**Response:** File path of the created issue.

### nexus_issue_list

List open issues for a project. Read-only.

```json
{"project": "mcphub"}
```

### nexus_issue_close

Close an issue by moving it to the `closed/` subdirectory.

```json
{"project": "mcphub", "file": "ISSUE_CRITICAL_BUG_FOUND.md", "resolution": "Fixed in v0.2.1"}
```

### nexus_issue_update

Append a timestamped update to an existing issue.

```json
{"project": "mcphub", "file": "ISSUE_CRITICAL_BUG_FOUND.md", "addition": "Verified fix in staging environment"}
```

---

## Task Management Tools

Persistent cross-session task tracking via `~/.config/mcphub/tasks.json`.

### task_create

```json
{"title": "Fix login flow", "description": "Users report intermittent login failures", "priority": "HIGH"}
```

### task_list

```json
{"status": "all"}
```

Returns `{tasks: [...], counts: {open, in_progress, done, total}}`.

### task_update

```json
{"id": "task_001", "status": "done", "note": "Deployed to production"}
```

### task_delete

```json
{"id": "task_001"}
```

---

## mcphub_checkpoint

Save current session state for next-session handoff. Reads tasks and nexus issues, writes a checkpoint file.

**Request params:**

```json
{"message": "Completed login fix. Remaining: deploy monitoring dashboard.", "project": "mcphub"}
```

- `message` (required): Handover note
- `project` (optional): Project name to include nexus issue listing

**Response:** Checkpoint file path and session state summary.

---

*MCPHUB API Reference -- v0.2.0-alpha with session recovery and operational tools*
