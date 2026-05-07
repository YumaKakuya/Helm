import * as readline from 'readline';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as child_process from 'child_process';

type NextAction = 'retry' | 'use_alternative' | 'disambiguate' | 'abort' | 'wait_session' | 'call_mcphub_session_open';

/** Check whether a patch uses non-git-style headers that will fail with patch -p1. */
function hasNonGitStyleHeaders(patchContent: string): boolean {
  for (const line of patchContent.split('\n')) {
    if (line.startsWith('--- ')) {
      const p = line.slice(4).trim();
      if (p !== '/dev/null' && !p.startsWith('a/')) return true;
    }
    if (line.startsWith('+++ ')) {
      const p = line.slice(4).trim();
      if (p !== '/dev/null' && !p.startsWith('b/')) return true;
    }
  }
  return false;
}

function formatPatchMessage(message: string, nextAction: NextAction): string {
  return `[apply_patch] ${message} | next_action: ${nextAction}`;
}

/** Classify patch failure output into AI-actionable diagnostic text. */
function classifyPatchFailure(
  patchContent: string,
  spawnErr: Error | null,
  stdout: string,
  stderr: string
): string {
  const output = (stderr + '\n' + stdout).trim();

  if (spawnErr) {
    const msg = spawnErr.message || '';
    if (msg.includes('ETIMEDOUT') || msg.includes('timed out')) {
      return formatPatchMessage(
        'patch timed out (30s). The patch may be too large or the filesystem is slow. Try a smaller patch or check the target filesystem.',
        'retry'
      );
    }
    if (msg.includes('ENOENT')) {
      return formatPatchMessage(
        "patch binary not found on PATH. Install 'patch' (e.g. 'apt install patch' / 'brew install patchutils') and retry.",
        'retry'
      );
    }
    return formatPatchMessage(`patch spawn error: ${msg}`, 'retry');
  }

  if (hasNonGitStyleHeaders(patchContent)) {
    return formatPatchMessage(
      "Patch uses plain diff headers (e.g. '--- file' instead of '--- a/file'). " +
      "The adapter applies patches with 'patch -p1' from supplied cwd - git-style 'a/' and 'b/' path prefixes are required. " +
      "Re-generate the patch with 'git diff', 'git format-patch', or manually prefix paths with 'a/' and 'b/'.",
      'abort'
    );
  }

  if (output.includes("can't find file to patch")) {
    return formatPatchMessage(
      "Cannot find file to patch. Verify the target file exists relative to the working directory (cwd), " +
      "or adjust the 'cwd' parameter. Ensure git-style headers ('--- a/path', '+++ b/path') are used.",
      'disambiguate'
    );
  }

  if (output.includes('File to patch:') || output.includes('Skip this patch?') || output.includes('Ignore this patch?')) {
    return formatPatchMessage(
      'Patch required interactive input. The file referenced in the patch may not exist, or the strip level (-p1) is wrong for the header format. Check paths relative to cwd and use git-style headers.',
      'disambiguate'
    );
  }

  const hunkFails = (output.match(/Hunk #\d+ FAILED/g) || []).length;
  if (hunkFails > 0) {
    return formatPatchMessage(
      `${hunkFails} hunk(s) failed to apply. Patch may be stale - file content has changed since the patch was generated, or the context lines don't match. Re-generate the patch against the current file content.`,
      'retry'
    );
  }

  if (output.includes('reversed') || output.includes('previously applied') || output.includes('already applied')) {
    return formatPatchMessage(
      'Patch appears to be already applied (reversed or previously applied detected). Skip this patch or re-generate from a clean state.',
      'abort'
    );
  }

  if (output.includes('unexpectedly ends') || output.includes('malformed') || output.includes('Not a unified diff')) {
    return formatPatchMessage(
      "Patch content is malformed - not a valid unified diff. Verify the patch has correct '---', '+++', and '@@' headers.",
      'abort'
    );
  }

  if (!output) {
    return formatPatchMessage(
      'patch exited with non-zero status but produced no output. The patch may be invalid or the filesystem is in an unexpected state.',
      'abort'
    );
  }

  return formatPatchMessage(`patch failed: ${output.slice(0, 400)}`, 'abort');
}

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
    // Write patch to temp file and apply.
    const tmpFile = path.join(os.tmpdir(), `mcphub_patch_${Date.now()}.patch`);
    fs.writeFileSync(tmpFile, patch, 'utf8');
    try {
      // -f forces non-interactive behavior so the adapter returns diagnostics instead of waiting for input.
      const result = child_process.spawnSync('patch', ['-p1', '-f', '--input', tmpFile], { cwd, encoding: 'utf8', timeout: 30000 });
      fs.unlinkSync(tmpFile);
      if (result.error || result.status !== 0) {
        const text = classifyPatchFailure(patch, result.error ?? null, result.stdout ?? '', result.stderr ?? '');
        return { content: [{ type: 'text', text }], isError: true };
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
