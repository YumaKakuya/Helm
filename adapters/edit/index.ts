import * as readline from 'readline';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as child_process from 'child_process';

const TOOLS = [
  {
    name: 'apply_patch',
    description: 'Apply a unified diff patch to files in the workspace. Modifies local file state.',
    inputSchema: {
      type: 'object',
      properties: {
        patch: { type: 'string', description: 'Unified diff patch content' },
        cwd: { type: 'string', description: 'Working directory (optional, defaults to current)' }
      },
      required: ['patch']
    }
  }
];

async function dispatch(name: string, args: Record<string, unknown>): Promise<{ content: { type: string; text: string }[]; isError?: boolean }> {
  if (name === 'apply_patch') {
    const patch = args['patch'] as string;
    const cwd = (args['cwd'] as string) || process.cwd();
    if (!patch) throw new Error('patch is required');
    // Write patch to temp file and apply
    const tmpFile = path.join(os.tmpdir(), `mcphub_patch_${Date.now()}.patch`);
    fs.writeFileSync(tmpFile, patch, 'utf8');
    try {
      const result = child_process.spawnSync('patch', ['-p1', '--input', tmpFile], { cwd, encoding: 'utf8', timeout: 30000 });
      fs.unlinkSync(tmpFile);
      if (result.status !== 0) {
        return { content: [{ type: 'text', text: `patch failed:\n${result.stderr || result.stdout}` }], isError: true };
      }
      return { content: [{ type: 'text', text: result.stdout || 'Patch applied successfully.' }] };
    } catch (e) {
      try { fs.unlinkSync(tmpFile); } catch { /* ignore */ }
      throw e;
    }
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
    if (method === 'initialize') respond(id, { protocolVersion: '2024-11-05', serverInfo: { name: 'mcphub-edit', version: '0.1.0-alpha' }, capabilities: { tools: {} } });
    else if (method === 'notifications/initialized') { /* no-op */ }
    else if (method === 'tools/list') respond(id, { tools: TOOLS });
    else if (method === 'tools/call') { const p = params as { name?: string; arguments?: Record<string, unknown> }; respond(id, await dispatch(p?.name ?? '', p?.arguments ?? {})); }
    else respondError(id, -32601, `Unknown method: ${method}`);
  } catch (err) { respondError(id, -32603, err instanceof Error ? err.message : String(err)); }
});
