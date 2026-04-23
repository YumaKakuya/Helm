import * as readline from 'readline';
import * as fs from 'fs';
import * as path from 'path';

const TOOLS = [
  {
    name: 'plan_enter',
    description: 'Enter planning mode. Records the current plan before execution begins. Modifies local state.',
    inputSchema: { type: 'object', properties: { plan: { type: 'string', description: 'The plan to record' } } }
  },
  {
    name: 'plan_exit',
    description: 'Exit planning mode after execution is complete. Records plan completion. Modifies local state.',
    inputSchema: { type: 'object', properties: { summary: { type: 'string', description: 'Completion summary' } } }
  },
  {
    name: 'skill',
    description: 'Load and apply a named skill definition. Read-only.',
    inputSchema: { type: 'object', properties: { name: { type: 'string', description: 'Skill name to load' } }, required: ['name'] }
  },
  {
    name: 'batch',
    description: 'Execute a batch of multiple tool operations in sequence.',
    inputSchema: { type: 'object', properties: { operations: { type: 'array', items: { type: 'object', properties: { tool: { type: 'string' }, arguments: { type: 'object' } } }, description: 'Tool operations to execute' } }, required: ['operations'] }
  }
];

const PLAN_FILE = process.env['MCPHUB_PLAN_FILE'] || path.join(process.env['HOME'] || '/tmp', '.mcphub_plan.json');
const SKILL_DIR = process.env['MCPHUB_SKILL_DIR'] || path.join(process.env['HOME'] || '/tmp', '.claude', 'skills');

async function dispatch(name: string, args: Record<string, unknown>): Promise<{ content: { type: string; text: string }[]; isError?: boolean }> {
  if (name === 'plan_enter') {
    const plan = (args['plan'] as string) || '(no plan provided)';
    const state = { plan, entered_at: new Date().toISOString(), status: 'active' };
    fs.writeFileSync(PLAN_FILE, JSON.stringify(state, null, 2), 'utf8');
    return { content: [{ type: 'text', text: `Plan recorded. Entering plan mode.\n${plan}` }] };
  }
  if (name === 'plan_exit') {
    const summary = (args['summary'] as string) || '(no summary)';
    let state: Record<string, unknown> = {};
    try { state = JSON.parse(fs.readFileSync(PLAN_FILE, 'utf8')); } catch { /* no existing plan */ }
    state['exited_at'] = new Date().toISOString();
    state['status'] = 'complete';
    state['summary'] = summary;
    fs.writeFileSync(PLAN_FILE, JSON.stringify(state, null, 2), 'utf8');
    return { content: [{ type: 'text', text: `Plan complete. Exiting plan mode.\nSummary: ${summary}` }] };
  }
  if (name === 'skill') {
    const skillName = args['name'] as string;
    if (!skillName) throw new Error('name is required');
    const skillPath = path.join(SKILL_DIR, skillName, 'SKILL.md');
    if (fs.existsSync(skillPath)) {
      return { content: [{ type: 'text', text: fs.readFileSync(skillPath, 'utf8') }] };
    }
    return { content: [{ type: 'text', text: `Skill '${skillName}' not found at ${skillPath}` }], isError: true };
  }
  if (name === 'batch') {
    const operations = args['operations'] as Array<{ tool: string; arguments?: Record<string, unknown> }>;
    if (!operations || !Array.isArray(operations)) throw new Error('operations array required');
    const results: string[] = [];
    for (const op of operations) {
      try {
        const result = await dispatch(op.tool, op.arguments || {});
        const text = result.content.map(c => c.text).join('');
        results.push(`[${op.tool}]: ${text}`);
      } catch (e) {
        results.push(`[${op.tool}] ERROR: ${e instanceof Error ? e.message : String(e)}`);
      }
    }
    return { content: [{ type: 'text', text: results.join('\n---\n') }] };
  }
  throw new Error(`Unknown tool: ${name}`);
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });
const out = process.stdout;
function respond(id: unknown, result: unknown): void { out.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n'); }
function respondError(id: unknown, code: number, message: string): void { out.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n'); }
rl.on('line', async (line) => {
  if (!line.trim()) return;
  let req: Record<string, unknown>;
  try { req = JSON.parse(line); } catch { respondError(null, -32700, 'Parse error'); return; }
  const { id, method, params } = req as { id?: unknown; method?: string; params?: Record<string, unknown> };
  try {
    if (method === 'initialize') respond(id, { protocolVersion: '2024-11-05', serverInfo: { name: 'mcphub-session', version: '0.1.0-alpha' }, capabilities: { tools: {} } });
    else if (method === 'notifications/initialized') { /* no-op */ }
    else if (method === 'tools/list') respond(id, { tools: TOOLS });
    else if (method === 'tools/call') { const p = params as { name?: string; arguments?: Record<string, unknown> }; respond(id, await dispatch(p?.name ?? '', p?.arguments ?? {})); }
    else respondError(id, -32601, `Unknown method: ${method}`);
  } catch (err) { respondError(id, -32603, err instanceof Error ? err.message : String(err)); }
});
