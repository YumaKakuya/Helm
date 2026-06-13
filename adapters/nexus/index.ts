import * as readline from 'readline';
import * as fs from 'fs';
import * as path from 'path';

const NEXUS_ISSUE_BASE = process.env['MCPHUB_NEXUS_BASE'] || path.join(process.env['HOME'] || '/tmp', 'nexus', 'issue');

const TOOLS = [
  {
    name: 'nexus_issue_create',
    description: 'Create a new issue in the Nexus issue tracker under a project. Returns the created file path. Modifies local state.',
    inputSchema: {
      type: 'object',
      properties: {
        project: { type: 'string', description: 'Project name (e.g. mcphub, hatch, axis)' },
        title: { type: 'string', description: 'Issue title (used as filename slug)' },
        body: { type: 'string', description: 'Markdown body of the issue' },
        priority: { type: 'string', enum: ['HIGH', 'MEDIUM', 'LOW', 'INFO'], default: 'MEDIUM', description: 'Issue priority' }
      },
      required: ['project', 'title', 'body']
    }
  },
  {
    name: 'nexus_issue_list',
    description: 'List open issues for a project in the Nexus issue tracker. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        project: { type: 'string', description: 'Project name (e.g. mcphub, hatch, axis)' }
      },
      required: ['project']
    }
  },
  {
    name: 'nexus_issue_close',
    description: 'Close an issue by moving it to the closed/ subdirectory with an optional resolution note. Modifies local state.',
    inputSchema: {
      type: 'object',
      properties: {
        project: { type: 'string', description: 'Project name' },
        file: { type: 'string', description: 'Issue filename (from nexus_issue_list)' },
        resolution: { type: 'string', description: 'Optional resolution note to append before closing' }
      },
      required: ['project', 'file']
    }
  },
  {
    name: 'nexus_issue_update',
    description: 'Append a status update or additional evidence to an existing issue. Modifies local state.',
    inputSchema: {
      type: 'object',
      properties: {
        project: { type: 'string', description: 'Project name' },
        file: { type: 'string', description: 'Issue filename (from nexus_issue_list)' },
        addition: { type: 'string', description: 'Text to append to the issue' }
      },
      required: ['project', 'file', 'addition']
    }
  }
];

// --- Security: path traversal prevention (LESSON-002) ---

function sanitizePath(base: string, ...segments: string[]): string {
  const resolved = path.resolve(base, ...segments);
  if (!resolved.startsWith(base + path.sep) && resolved !== base) {
    throw new Error(`Path traversal detected: resolved path escapes base directory`);
  }
  return resolved;
}

function slugify(title: string): string {
  return title
    .replace(/[^a-zA-Z0-9\-_]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_|_$/g, '')
    .toUpperCase()
    .slice(0, 80);
}

function ensureDir(dir: string): void {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
}

function projectDir(project: string): string {
  return sanitizePath(NEXUS_ISSUE_BASE, project);
}

function issuePath(project: string, file: string): string {
  return sanitizePath(NEXUS_ISSUE_BASE, project, file);
}

// --- JSON-RPC ---

const out = process.stdout;
const rl = readline.createInterface({ input: process.stdin });

function respond(id: unknown, result: unknown): void {
  out.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function respondError(id: unknown, code: number, message: string): void {
  out.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

// --- Tool dispatch ---

type ToolResult = { content: { type: string; text: string }[]; isError?: boolean };

rl.on('line', async (line: string) => {
  let req: { id: unknown; method: string; params?: unknown };
  try {
    req = JSON.parse(line);
  } catch {
    return; // skip non-JSON
  }

  const { id, method, params } = req;
  if (method === 'tools/list') {
    respond(id, { tools: TOOLS });
    return;
  }

  if (method !== 'tools/call') return;
  const p = params as { name?: string; arguments?: Record<string, unknown> } | undefined;
  try {
    respond(id, await dispatch(p?.name ?? '', p?.arguments ?? {}));
  } catch (err) {
    respondError(id, -32603, err instanceof Error ? err.message : String(err));
  }
});

async function dispatch(name: string, args: Record<string, unknown>): Promise<ToolResult> {
  switch (name) {
    case 'nexus_issue_create': return handleCreate(args);
    case 'nexus_issue_list': return handleList(args);
    case 'nexus_issue_close': return handleClose(args);
    case 'nexus_issue_update': return handleUpdate(args);
    default: throw new Error(`Unknown tool: ${name}`);
  }
}

// --- Implementations ---

async function handleCreate(args: Record<string, unknown>): Promise<ToolResult> {
  const project = (args['project'] as string)?.trim();
  const title = (args['title'] as string)?.trim();
  const body = (args['body'] as string)?.trim();
  const priority = (args['priority'] as string) || 'MEDIUM';

  if (!project || !title || !body) {
    throw new Error('project, title, and body are required');
  }

  // Validate priority enum
  if (!['HIGH', 'MEDIUM', 'LOW', 'INFO'].includes(priority)) {
    throw new Error(`Invalid priority: ${priority}. Must be HIGH, MEDIUM, LOW, or INFO.`);
  }

  const dir = projectDir(project);
  ensureDir(dir);

  const today = new Date().toISOString().slice(0, 10);
  const filename = `ISSUE_${slugify(title)}.md`;
  const filePath = path.join(dir, filename);

  // Verify no path traversal in filename
  sanitizePath(dir, filename);

  const content = `# ${title}
# Project: ${project}
# Priority: ${priority}
# Created: ${today}
# Status: OPEN

---

${body}

---

*Nexus Issue | ${project} | ${today}*
`;

  fs.writeFileSync(filePath, content);
  return { content: [{ type: 'text', text: JSON.stringify({ created: filePath, project, filename }) }] };
}

async function handleList(args: Record<string, unknown>): Promise<ToolResult> {
  const project = (args['project'] as string)?.trim();
  if (!project) throw new Error('project is required');

  const dir = projectDir(project);
  if (!fs.existsSync(dir)) {
    return { content: [{ type: 'text', text: JSON.stringify({ project, issues: [], closed_count: 0 }) }] };
  }

  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const openIssues: { file: string; size: number; mtime: string }[] = [];
  let closedCount = 0;

  for (const entry of entries) {
    if (entry.name === 'closed' && entry.isDirectory()) {
      closedCount = fs.readdirSync(path.join(dir, 'closed')).filter(f => f.endsWith('.md')).length;
      continue;
    }
    if (entry.isFile() && entry.name.endsWith('.md')) {
      const stat = fs.statSync(path.join(dir, entry.name));
      openIssues.push({ file: entry.name, size: stat.size, mtime: stat.mtime.toISOString() });
    }
  }

  return { content: [{ type: 'text', text: JSON.stringify({ project, issues: openIssues, closed_count: closedCount }) }] };
}

async function handleClose(args: Record<string, unknown>): Promise<ToolResult> {
  const project = (args['project'] as string)?.trim();
  const file = (args['file'] as string)?.trim();
  const resolution = (args['resolution'] as string)?.trim();

  if (!project || !file) throw new Error('project and file are required');

  const srcPath = issuePath(project, file);
  if (!fs.existsSync(srcPath)) {
    throw new Error(`Issue not found: ${file}`);
  }

  // Read existing content
  let content = fs.readFileSync(srcPath, 'utf-8');

  // Append resolution note if provided
  if (resolution) {
    content += `\n\n### Resolution (${new Date().toISOString().slice(0, 10)})\n\n${resolution}\n`;
  }

  // Update status
  const today = new Date().toISOString().slice(0, 10);
  content = content.replace(/# Status: OPEN/, `# Status: CLOSED — ${today}`);
  content += `\n\n*Closed: ${today}*\n`;

  // Move to closed/
  const closedDir = path.join(projectDir(project), 'closed');
  ensureDir(closedDir);
  const destPath = path.join(closedDir, file);
  fs.writeFileSync(destPath, content);
  fs.unlinkSync(srcPath);

  return { content: [{ type: 'text', text: JSON.stringify({ closed: file, moved_to: destPath }) }] };
}

async function handleUpdate(args: Record<string, unknown>): Promise<ToolResult> {
  const project = (args['project'] as string)?.trim();
  const file = (args['file'] as string)?.trim();
  const addition = (args['addition'] as string)?.trim();

  if (!project || !file || !addition) throw new Error('project, file, and addition are required');

  const filePath = issuePath(project, file);
  if (!fs.existsSync(filePath)) {
    throw new Error(`Issue not found: ${file}`);
  }

  const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
  const append = `\n\n### Update ${now}\n\n${addition}\n`;
  fs.appendFileSync(filePath, append);

  return { content: [{ type: 'text', text: JSON.stringify({ updated: file, timestamp: now }) }] };
}