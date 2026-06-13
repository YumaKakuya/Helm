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
  },
  {
    name: 'task_create',
    description: 'Create a persistent task for cross-session tracking. Modifies local state.',
    inputSchema: { type: 'object', properties: { title: { type: 'string', description: 'Task title' }, description: { type: 'string', description: 'Task description or details' }, priority: { type: 'string', enum: ['HIGH', 'MEDIUM', 'LOW'], default: 'MEDIUM' } }, required: ['title'] }
  },
  {
    name: 'task_list',
    description: 'List persistent tasks with optional status filter. Read-only.',
    inputSchema: { type: 'object', properties: { status: { type: 'string', enum: ['open', 'in_progress', 'done', 'all'], default: 'all', description: 'Filter by status' } }, required: [] }
  },
  {
    name: 'task_update',
    description: 'Update task status. done = 消込. Modifies local state.',
    inputSchema: { type: 'object', properties: { id: { type: 'string', description: 'Task ID from task_list' }, status: { type: 'string', enum: ['open', 'in_progress', 'done'], description: 'New status' }, note: { type: 'string', description: 'Optional note to record with this update' } }, required: ['id', 'status'] }
  },
  {
    name: 'task_delete',
    description: 'Delete a task permanently. Modifies local state.',
    inputSchema: { type: 'object', properties: { id: { type: 'string', description: 'Task ID from task_list' } }, required: ['id'] }
  }
];

const TODO_FILE = process.env['MCPHUB_TODO_FILE'] || path.join(process.env['HOME'] || '/tmp', '.mcphub_todos.json');
const TASKS_FILE = path.join(process.env['HOME'] || '/tmp', '.config', 'mcphub', 'tasks.json');

// ---------------------------------------------------------------------------
// Persistent Task Store (LESSON-002: validate enums, detect corruption)
// ---------------------------------------------------------------------------

interface Task {
  id: string;
  title: string;
  description: string;
  status: string;
  priority: string;
  createdAt: string;
  updatedAt: string;
  notes: string[];
}

function loadTasks(): Task[] {
  try {
    if (fs.existsSync(TASKS_FILE)) {
      const data = fs.readFileSync(TASKS_FILE, 'utf-8');
      const parsed = JSON.parse(data);
      if (!Array.isArray(parsed)) {
        // LESSON-002: corruption detected — backup and throw
        const backup = TASKS_FILE + '.corrupted.' + Date.now();
        fs.copyFileSync(TASKS_FILE, backup);
        throw new Error('tasks.json is corrupted (not an array). Backup saved to ' + backup);
      }
      return parsed;
    }
  } catch (e) {
    if (e instanceof SyntaxError || (e as Error).message?.includes('corrupted')) {
      throw e; // Re-throw corruption errors
    }
    // For other errors (e.g., file not found), start fresh
  }
  return [];
}

function saveTasks(tasks: Task[]): void {
  const dir = path.dirname(TASKS_FILE);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(TASKS_FILE, JSON.stringify(tasks, null, 2), 'utf-8');
}

function nextTaskId(tasks: Task[]): string {
  let max = 0;
  for (const t of tasks) {
    const m = t.id.match(/^task_(\d+)$/);
    if (m) { const n = parseInt(m[1], 10); if (n > max) max = n; }
  }
  return `task_${String(max + 1).padStart(3, '0')}`;
}

function now(): string {
  return new Date().toISOString();
}

// ---------------------------------------------------------------------------
// LSP integration — Alpha implementation
// Supports: definition, references, hover
// Language servers: typescript-language-server (TS/JS), gopls (Go)
// ---------------------------------------------------------------------------

interface LspMessage {
  jsonrpc: string;
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code: number; message: string };
}

function detectLanguage(filePath: string): 'typescript' | 'go' | 'unknown' {
  const ext = path.extname(filePath).toLowerCase();
  if (['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs'].includes(ext)) return 'typescript';
  if (ext === '.go') return 'go';
  return 'unknown';
}

function findTsServerPath(): string | null {
  const candidates = [
    path.join(__dirname, '..', 'node_modules', 'typescript', 'lib'),
    path.join(__dirname, '..', '..', 'node_modules', 'typescript', 'lib'),
    path.join(__dirname, '..', '..', '..', 'node_modules', 'typescript', 'lib'),
  ];
  for (const c of candidates) {
    if (fs.existsSync(path.join(c, 'tsserver.js'))) return c;
  }
  try {
    const result = child_process.execSync('node -e "console.log(require.resolve(\'typescript/lib/tsserver.js\'))"',
      { encoding: 'utf8', timeout: 5000, stdio: ['pipe', 'pipe', 'pipe'] }).trim();
    if (result && fs.existsSync(result)) return path.dirname(result);
  } catch {}
  return null;
}

function findTsLspBinary(): string | null {
  const candidates = [
    path.join(__dirname, '..', 'node_modules', '.bin', 'typescript-language-server'),
    path.join(__dirname, '..', '..', 'node_modules', '.bin', 'typescript-language-server'),
    path.join(__dirname, '..', '..', '..', 'node_modules', '.bin', 'typescript-language-server'),
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  try {
    const result = child_process.execSync('which typescript-language-server',
      { encoding: 'utf8', timeout: 3000, stdio: ['pipe', 'pipe', 'pipe'] }).trim();
    if (result) return result;
  } catch {}
  return null;
}

function lspServerCommand(lang: 'typescript' | 'go'): { cmd: string; args: string[]; env?: Record<string, string> } | null {
  if (lang === 'typescript') {
    const lspBinary = findTsLspBinary();
    if (!lspBinary) return null;
    const tsserverPath = findTsServerPath();
    const env: Record<string, string> = {};
    if (tsserverPath) {
      const nodeModulesDir = path.resolve(tsserverPath, '..', '..');
      env['NODE_PATH'] = nodeModulesDir;
    }
    return { cmd: lspBinary, args: ['--stdio'], env };
  }
  if (lang === 'go') {
    try {
      child_process.execSync('which gopls', { stdio: 'ignore' });
      return { cmd: 'gopls', args: ['serve'] };
    } catch {
      return null;
    }
  }
  return null;
}

function fileUri(filePath: string): string {
  const abs = path.resolve(filePath);
  return `file://${abs}`;
}

function findProjectRoot(filePath: string): string {
  let dir = path.dirname(path.resolve(filePath));
  for (let i = 0; i < 20; i++) {
    if (fs.existsSync(path.join(dir, 'tsconfig.json')) ||
        fs.existsSync(path.join(dir, 'package.json')) ||
        fs.existsSync(path.join(dir, 'go.mod')) ||
        fs.existsSync(path.join(dir, '.git'))) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return path.dirname(path.resolve(filePath));
}

async function runLspSession(
  serverCmd: { cmd: string; args: string[]; env?: Record<string, string> },
  filePath: string,
  lang: 'typescript' | 'go',
  command: string,
  line: number,
  character: number
): Promise<string> {
  return new Promise((resolve, reject) => {
    const absFile = path.resolve(filePath);
    const rootDir = findProjectRoot(absFile);
    const uri = fileUri(absFile);
    const rootUri = fileUri(rootDir);

    const proc = child_process.spawn(serverCmd.cmd, serverCmd.args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, ...(serverCmd.env || {}) },
    });

    let buffer = '';
    let reqId = 1;
    const pendingReqs: Map<number, { resolve: (v: unknown) => void; reject: (e: Error) => void }> = new Map();
    const timeout = setTimeout(() => {
      proc.kill();
      reject(new Error('LSP session timed out after 8s'));
    }, 8000);

    function send(msg: LspMessage): void {
      const body = JSON.stringify(msg);
      proc.stdin!.write(`Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
    }

    function sendRequest(method: string, params: unknown): Promise<unknown> {
      const id = reqId++;
      return new Promise((res, rej) => {
        pendingReqs.set(id, { resolve: res, reject: rej });
        send({ jsonrpc: '2.0', id, method, params });
      });
    }

    function sendNotification(method: string, params: unknown): void {
      send({ jsonrpc: '2.0', method, params });
    }

    proc.stdout!.on('data', (data: Buffer) => {
      buffer += data.toString();
      while (true) {
        const headerEnd = buffer.indexOf('\r\n\r\n');
        if (headerEnd < 0) break;
        const header = buffer.substring(0, headerEnd);
        const match = header.match(/Content-Length:\s*(\d+)/i);
        if (!match) { buffer = buffer.substring(headerEnd + 4); continue; }
        const contentLen = parseInt(match[1], 10);
        const bodyStart = headerEnd + 4;
        if (buffer.length < bodyStart + contentLen) break;
        const body = buffer.substring(bodyStart, bodyStart + contentLen);
        buffer = buffer.substring(bodyStart + contentLen);
        try {
          const msg = JSON.parse(body) as LspMessage;
          if (msg.id !== undefined && pendingReqs.has(msg.id as number)) {
            const p = pendingReqs.get(msg.id as number)!;
            pendingReqs.delete(msg.id as number);
            if (msg.error) p.reject(new Error(msg.error.message));
            else p.resolve(msg.result);
          }
        } catch {}
      }
    });

    proc.on('error', (err) => {
      clearTimeout(timeout);
      reject(err);
    });

    proc.on('close', () => {
      clearTimeout(timeout);
      for (const p of pendingReqs.values()) {
        p.reject(new Error('LSP server closed unexpectedly'));
      }
    });

    (async () => {
      try {
        await sendRequest('initialize', {
          processId: process.pid,
          rootUri,
          capabilities: {
            textDocument: {
              hover: { contentFormat: ['plaintext', 'markdown'] },
              definition: {},
              references: {},
            },
          },
        });
        sendNotification('initialized', {});

        let fileContent = '';
        try { fileContent = fs.readFileSync(absFile, 'utf8'); } catch { /* file may not exist */ }
        const langId = lang === 'typescript' ? (absFile.endsWith('.tsx') ? 'typescriptreact' : 'typescript') : 'go';
        sendNotification('textDocument/didOpen', {
          textDocument: { uri, languageId: langId, version: 1, text: fileContent },
        });

        await new Promise(r => setTimeout(r, 3000));

        const pos = { line: Math.max(0, line - 1), character };
        let result: unknown;
        if (command === 'hover') {
          result = await sendRequest('textDocument/hover', { textDocument: { uri }, position: pos });
        } else if (command === 'definition') {
          result = await sendRequest('textDocument/definition', { textDocument: { uri }, position: pos });
        } else if (command === 'references') {
          result = await sendRequest('textDocument/references', { textDocument: { uri }, position: pos, context: { includeDeclaration: true } });
        } else {
          throw new Error(`Unknown LSP command: ${command}. Supported: hover, definition, references`);
        }

        const formatted = formatLspResult(command, result, absFile);

        try {
          await sendRequest('shutdown', null);
          sendNotification('exit', null);
        } catch {}

        clearTimeout(timeout);
        proc.kill();
        resolve(formatted);
      } catch (err) {
        clearTimeout(timeout);
        proc.kill();
        reject(err);
      }
    })();
  });
}

function formatLspResult(command: string, result: unknown, filePath: string): string {
  if (result === null || result === undefined) {
    return `No ${command} information available at the specified position in ${path.basename(filePath)}.`;
  }
  if (command === 'hover') {
    const hover = result as { contents?: { value?: string; kind?: string } | string | Array<unknown> };
    if (!hover || !hover.contents) return 'No hover information available.';
    if (typeof hover.contents === 'string') return hover.contents;
    if (typeof hover.contents === 'object' && 'value' in hover.contents) return hover.contents.value || 'No hover information available.';
    if (Array.isArray(hover.contents)) {
      return hover.contents.map((c: unknown) => {
        if (typeof c === 'string') return c;
        if (typeof c === 'object' && c !== null && 'value' in c) return (c as { value: string }).value;
        return JSON.stringify(c);
      }).join('\n\n');
    }
    return JSON.stringify(hover.contents);
  }
  if (command === 'definition') {
    const defs = Array.isArray(result) ? result : [result];
    if (defs.length === 0) return 'No definition found.';
    return defs.map((d: unknown) => {
      const loc = d as { uri?: string; range?: { start: { line: number; character: number } } };
      if (!loc.uri) return JSON.stringify(d);
      const defFile = loc.uri.replace('file://', '');
      const line = loc.range ? loc.range.start.line + 1 : 0;
      const char = loc.range ? loc.range.start.character : 0;
      return `${defFile}:${line}:${char}`;
    }).join('\n');
  }
  if (command === 'references') {
    const refs = Array.isArray(result) ? result : [];
    if (refs.length === 0) return 'No references found.';
    return `Found ${refs.length} reference(s):\n` + refs.map((r: unknown) => {
      const loc = r as { uri?: string; range?: { start: { line: number; character: number } } };
      if (!loc.uri) return JSON.stringify(r);
      const refFile = loc.uri.replace('file://', '');
      const line = loc.range ? loc.range.start.line + 1 : 0;
      return `  ${refFile}:${line}`;
    }).join('\n');
  }
  return JSON.stringify(result, null, 2);
}

async function handleLsp(
  command: string,
  filePath: string,
  line: number,
  character: number
): Promise<{ content: { type: string; text: string }[]; isError?: boolean }> {
  if (!fs.existsSync(filePath)) {
    return { content: [{ type: 'text', text: `File not found: ${filePath}` }], isError: true };
  }
  const lang = detectLanguage(filePath);
  if (lang === 'unknown') {
    return {
      content: [{ type: 'text', text: `LSP not available for file type: ${path.extname(filePath)}. Supported: .ts, .tsx, .js, .jsx, .go` }],
      isError: true
    };
  }
  const serverCmd = lspServerCommand(lang);
  if (!serverCmd) {
    const serverName = lang === 'typescript' ? 'typescript-language-server' : 'gopls';
    return {
      content: [{ type: 'text', text: `Language server '${serverName}' not found. Install it to enable LSP for ${lang} files.` }],
      isError: true
    };
  }
  try {
    const result = await runLspSession(serverCmd, filePath, lang, command, line, character);
    return { content: [{ type: 'text', text: result }] };
  } catch (err) {
    return {
      content: [{ type: 'text', text: `LSP error: ${err instanceof Error ? err.message : String(err)}` }],
      isError: true
    };
  }
}

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
    let cmd = 'rg';
    let cmdArgs = rgArgs;
    try { child_process.execSync('which rg', { stdio: 'ignore' }); } catch {
      cmd = 'grep'; cmdArgs = ['-r', '-n', pattern, searchPath, ...(include ? ['--include', include] : [])];
    }
    const result = child_process.spawnSync(cmd, cmdArgs, { encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024 });
    return { content: [{ type: 'text', text: result.stdout || '(no matches)' }] };
  }
  if (name === 'lsp') {
    const command = args['command'] as string;
    const file = args['file'] as string;
    const line = (args['line'] as number) || 1;
    const character = (args['character'] as number) || 0;
    if (!command || !file) throw new Error('command and file are required');
    return await handleLsp(command, file, line, character);
  }

  // --- Task management tools ---
  if (name === 'task_create') {
    const tasks = loadTasks();
    const title = (args['title'] as string)?.trim() || 'Untitled';
    const description = (args['description'] as string)?.trim() || '';
    const priority = (args['priority'] as string) || 'MEDIUM';
    if (!['HIGH', 'MEDIUM', 'LOW'].includes(priority)) {
      throw new Error(`Invalid priority: ${priority}. Must be HIGH, MEDIUM, or LOW.`);
    }
    const task: Task = {
      id: nextTaskId(tasks),
      title,
      description,
      status: 'open',
      priority,
      createdAt: now(),
      updatedAt: now(),
      notes: []
    };
    tasks.push(task);
    saveTasks(tasks);
    return { content: [{ type: 'text', text: JSON.stringify(task) }] };
  }
  if (name === 'task_list') {
    const tasks = loadTasks();
    const filter = (args['status'] as string) || 'all';
    if (filter !== 'all' && !['open', 'in_progress', 'done'].includes(filter)) {
      throw new Error(`Invalid status filter: ${filter}. Must be open, in_progress, done, or all.`);
    }
    const filtered = filter === 'all' ? tasks : tasks.filter(t => t.status === filter);
    const open = tasks.filter(t => t.status === 'open').length;
    const progress = tasks.filter(t => t.status === 'in_progress').length;
    const done = tasks.filter(t => t.status === 'done').length;
    return { content: [{ type: 'text', text: JSON.stringify({ tasks: filtered, counts: { open, in_progress: progress, done, total: tasks.length }, filter }) }] };
  }
  if (name === 'task_update') {
    const tasks = loadTasks();
    const id = (args['id'] as string)?.trim();
    const status = (args['status'] as string)?.trim();
    const note = (args['note'] as string)?.trim();
    if (!id) throw new Error('id is required');
    if (!status || !['open', 'in_progress', 'done'].includes(status)) {
      throw new Error(`Invalid status: ${status}. Must be open, in_progress, or done.`);
    }
    const idx = tasks.findIndex(t => t.id === id);
    if (idx === -1) throw new Error(`Task not found: ${id}`);
    tasks[idx].status = status;
    tasks[idx].updatedAt = now();
    if (note) tasks[idx].notes.push(`[${now()}] ${note}`);
    saveTasks(tasks);
    return { content: [{ type: 'text', text: JSON.stringify(tasks[idx]) }] };
  }
  if (name === 'task_delete') {
    const tasks = loadTasks();
    const id = (args['id'] as string)?.trim();
    if (!id) throw new Error('id is required');
    const before = tasks.length;
    const remaining = tasks.filter(t => t.id !== id);
    if (remaining.length === before) throw new Error(`Task not found: ${id}`);
    saveTasks(remaining);
    return { content: [{ type: 'text', text: JSON.stringify({ deleted: id, remaining: remaining.length }) }] };
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
    if (method === 'initialize') respond(id, { protocolVersion: '2024-11-05', serverInfo: { name: 'mcphub-project', version: '0.2.0-alpha' }, capabilities: { tools: {} } });
    else if (method === 'notifications/initialized') { /* no-op */ }
    else if (method === 'tools/list') respond(id, { tools: TOOLS });
    else if (method === 'tools/call') { const p = params as { name?: string; arguments?: Record<string, unknown> }; respond(id, await dispatch(p?.name ?? '', p?.arguments ?? {})); }
    else respondError(id, -32601, `Unknown method: ${method}`);
  } catch (err) { respondError(id, -32603, err instanceof Error ? err.message : String(err)); }
});