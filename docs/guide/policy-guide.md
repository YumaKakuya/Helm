# Policy Configuration

MCPHUB's policy engine controls which tools the AI can access. Every tool call passes through policy evaluation before reaching a provider.

## Policy decisions

There are three possible outcomes for any tool:

| Decision | Effect on AI (`tools/list`) | Effect on `tools/call` |
|----------|-----------------------------|------------------------|
| **ALLOW** | Tool is visible | Call is routed to provider |
| **DENY** | Tool is hidden from AI | Call returns `tool_denied` error |
| **HIDE** | Tool is hidden from AI | Call returns `tool_not_found` (tool appears nonexistent) |

The difference between DENY and HIDE: a denied tool tells the AI "this tool exists but you can't use it." A hidden tool pretends the tool doesn't exist at all.

## Default policy

Out of the box, MCPHUB allows all tools:

```yaml
policy:
  rules:
    - rule_id: "default-allow-all"
      tool_pattern: "*"
      action: "allow"
      priority: 1
      scope: "global"
```

This default rule exists in `capabilities.yaml` and applies to all tools with the lowest priority (1).

## Adding policy rules

Policy rules are defined in `capabilities.yaml` under the `policy.rules` key:

```yaml
policy:
  rules:
    - rule_id: "default-allow-all"
      tool_pattern: "*"
      action: "allow"
      priority: 1
      scope: "global"

    - rule_id: "deny-websearch"
      tool_pattern: "websearch"
      action: "deny"
      priority: 10
      scope: "global"

    - rule_id: "hide-batch"
      tool_pattern: "batch"
      action: "hide"
      priority: 10
      scope: "global"
```

Restart the daemon after editing `capabilities.yaml` for changes to take effect.

## Rule format

| Field | Required | Description |
|-------|----------|-------------|
| `rule_id` | Yes | Unique identifier for the rule (for logging and debugging) |
| `tool_pattern` | Yes | Tool name pattern to match (see below) |
| `action` | Yes | `allow`, `deny`, or `hide` |
| `priority` | Yes | Integer. Higher numbers win over lower numbers |
| `scope` | Yes | `global` (persistent) or `session` (cleared on session close) |

## Pattern matching

| Pattern | Matches |
|---------|---------|
| `*` | All tools |
| `webfetch` | Exact tool name |
| `web*` | Any tool starting with "web" (e.g., `webfetch`, `websearch`) |

## Conflict resolution

When multiple rules match the same tool:

1. **Highest priority wins.** A rule with `priority: 10` beats `priority: 1`.
2. **On equal priority, more specific pattern wins.** `webfetch` (exact) beats `web*` (prefix) beats `*` (wildcard).
3. **On equal priority and specificity:** `hide` > `deny` > `allow`.

### Example

```yaml
policy:
  rules:
    - rule_id: "allow-all"
      tool_pattern: "*"
      action: "allow"
      priority: 1
      scope: "global"

    - rule_id: "deny-web"
      tool_pattern: "web*"
      action: "deny"
      priority: 5
      scope: "global"

    - rule_id: "allow-webfetch"
      tool_pattern: "webfetch"
      action: "allow"
      priority: 10
      scope: "global"
```

Result:
- `webfetch` -> ALLOW (priority 10, exact match)
- `websearch` -> DENY (priority 5, prefix match)
- `todowrite` -> ALLOW (priority 1, wildcard)

## Session-scoped rules

Session rules are temporary and automatically cleared when the session ends (transitions to `COOLING_DOWN`). They are injected programmatically, not via config file.

Session rules effectively override global rules because they receive a +10000 priority boost internally. This means any session rule will beat any global rule.

## Operator vs AI visibility

The policy filter applies differently depending on the viewer:

| Viewer | ALLOW | DENY | HIDE |
|--------|-------|------|------|
| AI (`tools/list`) | Visible | Hidden | Hidden |
| Operator (`capabilities`) | Visible | Visible + shows `policy_decision: denied` | Hidden |

This means operators can always see denied tools and their policy decisions via `mcphub capabilities`, but the AI only sees allowed tools.

## Practical patterns

### Allow only specific tools

```yaml
policy:
  rules:
    - rule_id: "deny-all"
      tool_pattern: "*"
      action: "deny"
      priority: 1
      scope: "global"

    - rule_id: "allow-webfetch"
      tool_pattern: "webfetch"
      action: "allow"
      priority: 10
      scope: "global"

    - rule_id: "allow-codesearch"
      tool_pattern: "codesearch"
      action: "allow"
      priority: 10
      scope: "global"
```

### Deny destructive tools

```yaml
policy:
  rules:
    - rule_id: "default-allow-all"
      tool_pattern: "*"
      action: "allow"
      priority: 1
      scope: "global"

    - rule_id: "deny-apply-patch"
      tool_pattern: "apply_patch"
      action: "deny"
      priority: 10
      scope: "global"

    - rule_id: "deny-batch"
      tool_pattern: "batch"
      action: "deny"
      priority: 10
      scope: "global"
```

### Hide all relay tools

```yaml
policy:
  rules:
    - rule_id: "default-allow-all"
      tool_pattern: "*"
      action: "allow"
      priority: 1
      scope: "global"

    - rule_id: "hide-coffer"
      tool_pattern: "coffer_*"
      action: "hide"
      priority: 10
      scope: "global"
```

## Verifying policy

Check which tools are visible and their policy decisions:

```bash
./mcphub open
./mcphub capabilities --json
```

Each capability entry includes:

```json
{
  "capability_id": "webfetch",
  "policy_decision": "allowed",
  "policy_rule_id": "default-allow-all"
}
```

Check the route log for denied calls:

```bash
./mcphub query --sql "SELECT tool_name, route_decision, policy_rule_id, error_code FROM route_log WHERE route_decision = 'denied'"
```

## Emergency lock

The emergency lock is a global override that prevents any session from opening:

```bash
./mcphub lock                    # Lock: no sessions can start
./mcphub unlock                  # Clear lock
```

The lock persists across daemon restarts (stored in SQLite). While locked, `mcphub open` returns an error: `Cannot arm: hub is locked.`

---

*MCPHUB Policy Configuration Guide -- BL-10*
