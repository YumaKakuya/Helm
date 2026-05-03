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
    description: 'Update task status (e.g. mark as done for 消込) with optional note. Modifies local state.',
    inputSchema: { type: 'object', properties: { id: { type: 'string', description: 'Task ID from task_list' }, status: { type: 'string', enum: ['open', 'in_progress', 'done'], description: 'New status' }, note: { type: 'string', description: 'Optional note to record with this update' } }, required: ['id', 'status'] }
  },
  {
    name: 'task_delete',
    description: 'Delete a task permanently. Modifies local state.',
    inputSchema: { type: 'object', properties: { id: { type: 'string', description: 'Task ID from task_list' } }, required: ['id'] }
  },
  {
    name: 'mcphub_checkpoint',
    description: 'Save current session state (tasks, nexus issues, handover note) as a persistent checkpoint for next-session handoff. Modifies local state.',
    inputSchema: { type: 'object', properties: { message: { type: 'string', description: 'Handover message — what was done, what remains, context for next session' }, project: { type: 'string', description: 'Project name for nexus issue listing (optional)' } }, required: ['message'] }
  }
];

const TODO_FILE = process.env['MCPHUB_TODO_FILE'] || path.join(process.env['HOME'] || '/tmp', '.mcphub_todos.json');
const TASKS_FILE = path.join(process.env['HOME'] || '/tmp', '.config', 'mcphub', 'tasks.json');

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
      return JSON.parse(fs.readFileSync(TASKS_FILE, 'utf-8'));
    }
  } catch { /* corrupted file, start fresh */ }
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
  if (name === 'task_create') {
    const tasks = loadTasks();
    const task: Task = {
      id: nextTaskId(tasks),
      title: (args['title'] as string)?.trim() || 'Untitled',
      description: (args['description'] as string)?.trim() || '',
      status: 'open',
      priority: (args['priority'] as string) || 'MEDIUM',
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
    const before = tasks.length;
    const remaining = tasks.filter(t => t.id !== id);
    if (remaining.length === before) throw new Error(`Task not found: ${id}`);
    saveTasks(remaining);
    return { content: [{ type: 'text', text: JSON.stringify({ deleted: id, remaining: remaining.length }) }] };
  }
  if (name === 'mcphub_checkpoint') {
    const message = (args['message'] as string)?.trim();
    if (!message) throw new Error('message is required');
    const project = (args['project'] as string)?.trim();
    const tasks = loadTasks();
    const activeTasks = tasks.filter(t => t.status !== 'done');
    const doneTasks = tasks.filter(t => t.status === 'done');
    let nexusIssues: { file: string; mtime: string }[] = [];
    if (project) {
      const nexusDir = path.join(process.env['HOME'] || '/tmp', 'nexus', 'issue', project);
      try {
        if (fs.existsSync(nexusDir)) {
          nexusIssues = fs.readdirSync(nexusDir, { withFileTypes: true })
            .filter(e => e.isFile() && e.name.endsWith('.md'))
            .map(e => ({ file: e.name, mtime: fs.statSync(path.join(nexusDir, e.name)).mtime.toISOString() }));
        }
      } catch { /* no nexus dir yet */ }
    }
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const checkpointDir = path.join(process.env['HOME'] || '/tmp', '.config', 'mcphub', 'checkpoints');
    if (!fs.existsSync(checkpointDir)) fs.mkdirSync(checkpointDir, { recursive: true });
    const filePath = path.join(checkpointDir, `checkpoint-${timestamp}.json`);
    const doc = {
      timestamp: now(),
      message,
      project: project || null,
      tasks: { active: activeTasks.length, done: doneTasks.length, activeTasks, doneTasks },
      nexusIssues: project ? { project, open: nexusIssues.length, issues: nexusIssues } : null
    };
    fs.writeFileSync(filePath, JSON.stringify(doc, null, 2), 'utf-8');
    return { content: [{ type: 'text', text: JSON.stringify({ checkpoint: filePath, active_tasks: activeTasks.length, done_tasks: doneTasks.length, nexus_open: nexusIssues.length }) }] };
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
