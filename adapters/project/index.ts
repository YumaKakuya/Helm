import * as readline from 'readline';
import * as fs from 'fs';
import * as path from 'path';
import * as child_process from 'child_process';

const TOOLS = [
  {
    name: 'todowrite',
    description: 'Write or update the AI task list (todos). Modifies local state.',
    inputSchema: { type: 'object', properties: { todos: { type: 'array', items: { type: 'object' }, description: 'Todo items array' } }, required: ['todos'] }
  },
  {
    name: 'list',
    description: 'List files and directories at a given path. Read-only filesystem operation.',
    inputSchema: { type: 'object', properties: { path: { type: 'string', description: 'Directory path to list' } }, required: ['path'] }
  },
  {
    name: 'codesearch',
    description: 'Search the local codebase for files or content matching a pattern. Read-only.',
    inputSchema: { type: 'object', properties: { pattern: { type: 'string', description: 'Search pattern (regex or glob)' }, path: { type: 'string', description: 'Root path to search in (optional)' }, include: { type: 'string', description: 'File glob to include (optional)' } }, required: ['pattern'] }
  },
  {
    name: 'lsp',
    description: 'Query Language Server Protocol information (go-to-definition, hover, references). Read-only.',
    inputSchema: { type: 'object', properties: { command: { type: 'string', description: 'LSP command (definition, references, hover)' }, file: { type: 'string', description: 'File path' }, line: { type: 'number', description: 'Line number (1-indexed)' }, character: { type: 'number', description: 'Character offset' } }, required: ['command', 'file'] }
  }
];

const TODO_FILE = process.env['MCPHUB_TODO_FILE'] || path.join(process.env['HOME'] || '/tmp', '.mcphub_todos.json');

async function dispatch(name: string, args: Record<string, unknown>): Promise<{ content: { type: string; text: string }[]; isError?: boolean }> {
  if (name === 'todowrite') {
    const todos = args['todos'];
    fs.writeFileSync(TODO_FILE, JSON.stringify(todos, null, 2), 'utf8');
    return { content: [{ type: 'text', text: `Written ${Array.isArray(todos) ? todos.length : 0} todo(s) to ${TODO_FILE}` }] };
  }
  if (name === 'list') {
    const dirPath = args['path'] as string;
    if (!dirPath) throw new Error('path is required');
    const entries = fs.readdirSync(dirPath, { withFileTypes: true });
    const lines = entries.map(e => (e.isDirectory() ? `${e.name}/` : e.name)).join('\n');
    return { content: [{ type: 'text', text: lines || '(empty directory)' }] };
  }
  if (name === 'codesearch') {
    const pattern = args['pattern'] as string;
    const searchPath = (args['path'] as string) || process.cwd();
    const include = args['include'] as string | undefined;
    if (!pattern) throw new Error('pattern is required');
    const rgArgs = ['-n', '--no-heading', pattern, searchPath];
    if (include) rgArgs.push('--glob', include);
    // Try ripgrep, fall back to grep
    let cmd = 'rg';
    let cmdArgs = rgArgs;
    try { child_process.execSync('which rg', { stdio: 'ignore' }); } catch {
      cmd = 'grep'; cmdArgs = ['-r', '-n', pattern, searchPath, ...(include ? ['--include', include] : [])];
    }
    const result = child_process.spawnSync(cmd, cmdArgs, { encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024 });
    return { content: [{ type: 'text', text: result.stdout || '(no matches)' }] };
  }
  if (name === 'lsp') {
    // Alpha: stub — LSP integration requires a running language server
    const command = args['command'] as string;
    const file = args['file'] as string;
    return { content: [{ type: 'text', text: `LSP ${command} for ${file}: LSP subprocess integration is alpha+ scope. Tool registered and callable; full language server protocol requires Session 4.` }] };
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
    if (method === 'initialize') respond(id, { protocolVersion: '2024-11-05', serverInfo: { name: 'mcphub-project', version: '0.1.0-alpha' }, capabilities: { tools: {} } });
    else if (method === 'notifications/initialized') { /* no-op */ }
    else if (method === 'tools/list') respond(id, { tools: TOOLS });
    else if (method === 'tools/call') { const p = params as { name?: string; arguments?: Record<string, unknown> }; respond(id, await dispatch(p?.name ?? '', p?.arguments ?? {})); }
    else respondError(id, -32601, `Unknown method: ${method}`);
  } catch (err) { respondError(id, -32603, err instanceof Error ? err.message : String(err)); }
});
