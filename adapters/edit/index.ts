import * as readline from 'readline';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as child_process from 'child_process';

type NextAction = 'retry' | 'use_alternative' | 'disambiguate' | 'abort' | 'wait_session' | 'call_mcphub_session_open';

type PatchFormat = 'begin-patch' | 'git-unified' | 'plain-unified' | 'mixed-unified' | 'unknown';

function formatPatchMessage(message: string, nextAction: NextAction): string {
  return `[apply_patch] ${message} | next_action: ${nextAction}`;
}

/**
 * Detect the patch format.
 * - 'begin-patch':   starts with or contains '*** Begin Patch'
 * - 'git-unified':   unified diff with git-style headers only (--- a/, +++ b/)
 * - 'plain-unified': unified diff with plain headers only (--- path, +++ path)
 * - 'mixed-unified': unified diff mixing git-style AND plain headers (ambiguous strip level)
 * - 'unknown':       none of the above
 */
function detectPatchFormat(patchContent: string): PatchFormat {
  if (patchContent.includes('*** Begin Patch')) {
    return 'begin-patch';
  }
  // Look for diff headers
  let hasGitStyle = false;
  let hasPlainStyle = false;
  for (const line of patchContent.split('\n')) {
    if (line.startsWith('--- ')) {
      const p = line.slice(4).trim();
      if (p === '/dev/null' || p.startsWith('a/')) {
        hasGitStyle = true;
      } else {
        hasPlainStyle = true;
      }
    }
    if (line.startsWith('+++ ')) {
      const p = line.slice(4).trim();
      if (p === '/dev/null' || p.startsWith('b/')) {
        hasGitStyle = true;
      } else {
        hasPlainStyle = true;
      }
    }
  }
  if (hasGitStyle && hasPlainStyle) return 'mixed-unified';
  if (hasGitStyle) return 'git-unified';
  if (hasPlainStyle) return 'plain-unified';
  return 'unknown';
}

/**
 * Check whether any path segment in filePath is exactly '..'.
 * This rejects '../escape' and 'a/../b' while allowing 'version..txt', 'foo..bar'.
 * Splits on both '/' and '\' to be cross-platform safe.
 */
function hasParentTraversalSegment(filePath: string): boolean {
  return filePath.split(/[\\/]/).some(seg => seg === '..');
}

/**
 * Validate that a path is safe: no absolute paths, no .. segments, resolves inside cwd.
 * Returns an error string if unsafe, null if safe.
 */
function validateSafePath(filePath: string, cwd: string): string | null {
  if (path.isAbsolute(filePath)) {
    return `Path "${filePath}" is absolute. Only relative paths are allowed.`;
  }
  if (hasParentTraversalSegment(filePath)) {
    return `Path "${filePath}" contains ".." traversal segment. This is not allowed.`;
  }
  const resolved = path.resolve(cwd, filePath);
  const cwdResolved = path.resolve(cwd);
  if (!resolved.startsWith(cwdResolved + path.sep) && resolved !== cwdResolved) {
    return `Path "${filePath}" resolves outside the working directory.`;
  }
  return null; // safe
}

/**
 * Validate paths embedded in unified diff headers before invoking the patch binary.
 * Scans '--- ' and '+++ ' header lines, skips /dev/null.
 * For git-unified (-p1), strips the first path component (a/ or b/ prefix).
 * For plain-unified (-p0), uses the path as-is.
 * Returns an error string if any header path is unsafe, null if all are safe.
 */
function validateUnifiedDiffPaths(patchContent: string, format: PatchFormat, cwd: string): string | null {
  for (const line of patchContent.split('\n')) {
    let rawPath: string | null = null;
    if (line.startsWith('--- ')) {
      rawPath = line.slice(4).split('\t')[0].trim(); // strip optional tab+timestamp
    } else if (line.startsWith('+++ ')) {
      rawPath = line.slice(4).split('\t')[0].trim();
    }
    if (rawPath === null) continue;
    if (rawPath === '/dev/null' || rawPath === '') continue;

    let checkPath = rawPath;
    if (format === 'git-unified') {
      // Strip first component: 'a/foo/bar' → 'foo/bar', 'b/foo/bar' → 'foo/bar'
      const slashIdx = rawPath.indexOf('/');
      if (slashIdx !== -1) {
        checkPath = rawPath.slice(slashIdx + 1);
      } else {
        // No slash — after stripping there's nothing; skip (e.g. bare 'a')
        continue;
      }
    }

    if (checkPath === '' || checkPath === '/dev/null') continue;

    const err = validateSafePath(checkPath, cwd);
    if (err) {
      return `Unsafe path in patch header: ${err}`;
    }
  }
  return null;
}

/** Begin Patch operation */
interface BeginPatchOp {
  type: 'add' | 'update' | 'delete';
  filePath: string;
  hunks: BeginPatchHunk[];
}

interface BeginPatchHunk {
  /** Lines in the hunk: prefix ' ' = context, '-' = remove, '+' = add */
  lines: Array<{ prefix: ' ' | '-' | '+'; content: string }>;
}

interface FileSnapshot {
  absPath: string;
  existed: boolean;
  content?: Buffer;
}

interface RollbackResult {
  ok: boolean;
  restored: string[];
  removed: string[];
  errors: string[];
}

interface PlannedBeginPatchWrite {
  filePath: string;
  absPath: string;
  action: 'write' | 'delete';
  content?: string;
}

function uniquePaths(paths: string[]): string[] {
  return Array.from(new Set(paths));
}

function normalizeUnifiedDiffPath(rawPath: string, format: PatchFormat): string | null {
  if (rawPath === '/dev/null' || rawPath === '') return null;
  if (format === 'git-unified') {
    const slashIdx = rawPath.indexOf('/');
    if (slashIdx === -1) return null;
    return rawPath.slice(slashIdx + 1);
  }
  return rawPath;
}

function collectUnifiedDiffTargetPaths(patchContent: string, format: PatchFormat, cwd: string): string[] | string {
  const paths: string[] = [];
  for (const line of patchContent.split('\n')) {
    if (!line.startsWith('--- ') && !line.startsWith('+++ ')) continue;
    const rawPath = line.slice(4).split('\t')[0].trim();
    const normalized = normalizeUnifiedDiffPath(rawPath, format);
    if (!normalized) continue;
    const err = validateSafePath(normalized, cwd);
    if (err) return `Unsafe path in patch header: ${err}`;
    paths.push(path.resolve(cwd, normalized));
  }
  return uniquePaths(paths);
}

function patchArtifactPaths(targetPaths: string[]): string[] {
  const artifacts: string[] = [];
  for (const targetPath of targetPaths) {
    artifacts.push(`${targetPath}.orig`, `${targetPath}.rej`);
  }
  return uniquePaths(artifacts);
}

function validateNoSymlinkSegments(absPath: string, cwd: string): string | null {
  const cwdResolved = path.resolve(cwd);
  const relative = path.relative(cwdResolved, absPath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    return `Path resolves outside the working directory: ${absPath}`;
  }
  let current = cwdResolved;
  for (const segment of relative.split(path.sep)) {
    if (!segment) continue;
    current = path.join(current, segment);
    if (!fs.existsSync(current)) break;
    const stat = fs.lstatSync(current);
    if (stat.isSymbolicLink()) {
      return `Path contains symlink segment: ${current}. Symlink patch targets and artifacts are not allowed.`;
    }
  }
  return null;
}

function createFileSnapshots(absPaths: string[], cwd: string): FileSnapshot[] | string {
  const snapshots: FileSnapshot[] = [];
  for (const absPath of uniquePaths(absPaths)) {
    try {
      const symlinkErr = validateNoSymlinkSegments(absPath, cwd);
      if (symlinkErr) return symlinkErr;
      if (fs.existsSync(absPath)) {
        const stat = fs.lstatSync(absPath);
        if (!stat.isFile()) {
          return `Cannot snapshot non-file path: ${absPath}`;
        }
        snapshots.push({ absPath, existed: true, content: fs.readFileSync(absPath) });
      } else {
        snapshots.push({ absPath, existed: false });
      }
    } catch (err) {
      return `Cannot snapshot ${absPath}: ${err instanceof Error ? err.message : String(err)}`;
    }
  }
  return snapshots;
}

function restoreFileSnapshots(snapshots: FileSnapshot[], cwd: string): RollbackResult {
  const result: RollbackResult = { ok: true, restored: [], removed: [], errors: [] };
  for (const snapshot of snapshots) {
    try {
      const symlinkErr = validateNoSymlinkSegments(snapshot.absPath, cwd);
      if (symlinkErr) {
        result.ok = false;
        result.errors.push(`${snapshot.absPath}: ${symlinkErr}`);
        continue;
      }
      if (snapshot.existed) {
        fs.mkdirSync(path.dirname(snapshot.absPath), { recursive: true });
        fs.writeFileSync(snapshot.absPath, snapshot.content ?? Buffer.alloc(0));
        result.restored.push(snapshot.absPath);
      } else if (fs.existsSync(snapshot.absPath)) {
        fs.rmSync(snapshot.absPath, { force: true });
        result.removed.push(snapshot.absPath);
      }
    } catch (err) {
      result.ok = false;
      result.errors.push(`${snapshot.absPath}: ${err instanceof Error ? err.message : String(err)}`);
    }
  }
  return result;
}

function appendRollbackGuidance(message: string, rollback: RollbackResult): string {
  const rollbackStatus = rollback.ok ? 'rollback: restored pre-apply state' : `rollback: partial failure (${rollback.errors.join('; ')})`;
  const artifactStatus = rollback.removed.length > 0
    ? `artifacts_removed: ${rollback.removed.filter(p => p.endsWith('.orig') || p.endsWith('.rej')).length}`
    : 'artifacts_removed: 0';
  return `${message} | ${rollbackStatus} | ${artifactStatus} | verify: run git status --short and inspect intended target files; confirm no .rej/.orig artifacts remain | issue_guidance: create or update a Nexus issue if rollback is partial or diagnostics are unclear`;
}

function atomicNoWriteGuidance(message: string): string {
  return `${message} | rollback: not_needed_no_files_modified | verify: run git status --short if uncertain | issue_guidance: create or update a Nexus issue if diagnostics are unclear`;
}

/**
 * Parse a Begin Patch format string into structured operations.
 * Returns an error string on parse failure, or an array of ops on success.
 *
 * Rules:
 * - Must have '*** Begin Patch' and '*** End Patch' markers (required).
 * - Supports '*** Add File:', '*** Update File:', '*** Delete File:' directives.
 * - For Add File: accepts @@ hunks OR bare +/space lines (no @@ header needed).
 * - Top-level non-empty, non-directive, non-blank content → malformed error.
 * - Blank lines at top level are harmless.
 */
function parseBeginPatch(patchContent: string): BeginPatchOp[] | string {
  const lines = patchContent.split('\n');
  const ops: BeginPatchOp[] = [];

  // Find the Begin Patch marker
  let i = 0;
  while (i < lines.length && !lines[i].startsWith('*** Begin Patch')) {
    i++;
  }
  if (i >= lines.length) {
    return 'No "*** Begin Patch" marker found.';
  }
  i++; // skip the Begin Patch line

  let sawEnd = false;

  while (i < lines.length) {
    const line = lines[i];

    if (line.startsWith('*** End Patch')) {
      sawEnd = true;
      break;
    }

    // Blank lines at top level are harmless
    if (line.trim() === '') {
      i++;
      continue;
    }

    let opType: 'add' | 'update' | 'delete' | null = null;
    let filePath = '';

    if (line.startsWith('*** Add File: ')) {
      opType = 'add';
      filePath = line.slice('*** Add File: '.length).trim();
    } else if (line.startsWith('*** Update File: ')) {
      opType = 'update';
      filePath = line.slice('*** Update File: '.length).trim();
    } else if (line.startsWith('*** Delete File: ')) {
      opType = 'delete';
      filePath = line.slice('*** Delete File: '.length).trim();
    } else {
      // Non-empty, non-blank, non-directive top-level content → malformed
      return `Unexpected content at top level (expected "*** Add/Update/Delete File:" or "*** End Patch"): "${line}"`;
    }

    if (!filePath) {
      return `Empty file path in directive: "${line}"`;
    }

    i++;
    const hunks: BeginPatchHunk[] = [];

    if (opType === 'add') {
      // For Add File: accept @@ hunks OR bare content lines (+, space) with no @@ header.
      // Both styles are collected and merged into a single content stream.
      const bareLines: BeginPatchHunk['lines'] = [];
      while (i < lines.length) {
        const l = lines[i];
        if (l.startsWith('*** ')) break;

        if (l.startsWith('@@')) {
          // @@ header — collect subsequent content lines into this hunk
          i++;
          const hunkLines: BeginPatchHunk['lines'] = [];
          while (i < lines.length) {
            const hl = lines[i];
            if (hl.startsWith('@@') || hl.startsWith('*** ')) break;
            if (hl.startsWith('+')) {
              hunkLines.push({ prefix: '+', content: hl.slice(1) });
            } else if (hl.startsWith('-')) {
              hunkLines.push({ prefix: '-', content: hl.slice(1) });
            } else if (hl.startsWith(' ')) {
              hunkLines.push({ prefix: ' ', content: hl.slice(1) });
            } else if (hl === '') {
              hunkLines.push({ prefix: ' ', content: '' });
            } else {
              hunkLines.push({ prefix: ' ', content: hl });
            }
            i++;
          }
          if (hunkLines.length > 0) {
            hunks.push({ lines: hunkLines });
          }
        } else if (l.startsWith('+') || l.startsWith(' ') || l.startsWith('-')) {
          // Bare content line (no @@ header) — collect into bareLines
          if (l.startsWith('+')) {
            bareLines.push({ prefix: '+', content: l.slice(1) });
          } else if (l.startsWith('-')) {
            bareLines.push({ prefix: '-', content: l.slice(1) });
          } else {
            bareLines.push({ prefix: ' ', content: l.slice(1) });
          }
          i++;
        } else if (l === '') {
          // Blank line inside Add File body — treat as bare content (empty line)
          bareLines.push({ prefix: '+', content: '' });
          i++;
        } else {
          // Non-content, non-directive line inside Add File body — unexpected
          return `Unexpected line in Add File body: "${l}"`;
        }
      }
      // If we have bare lines, wrap them as a single synthetic hunk
      if (bareLines.length > 0) {
        hunks.push({ lines: bareLines });
      }
    } else {
      // Update File / Delete File: parse @@ hunks only
      while (i < lines.length) {
        const l = lines[i];
        if (l.startsWith('*** ')) break;

        if (l.startsWith('@@')) {
          i++;
          const hunkLines: BeginPatchHunk['lines'] = [];
          while (i < lines.length) {
            const hl = lines[i];
            if (hl.startsWith('@@') || hl.startsWith('*** ')) break;
            if (hl.startsWith('+')) {
              hunkLines.push({ prefix: '+', content: hl.slice(1) });
            } else if (hl.startsWith('-')) {
              hunkLines.push({ prefix: '-', content: hl.slice(1) });
            } else if (hl.startsWith(' ')) {
              hunkLines.push({ prefix: ' ', content: hl.slice(1) });
            } else if (hl === '') {
              hunkLines.push({ prefix: ' ', content: '' });
            } else {
              hunkLines.push({ prefix: ' ', content: hl });
            }
            i++;
          }
          if (hunkLines.length > 0) {
            hunks.push({ lines: hunkLines });
          }
        } else if (l.trim() === '') {
          // Blank lines between hunks are harmless
          i++;
        } else {
          // Non-blank, non-hunk line inside Update/Delete body
          return `Unexpected line in ${opType === 'update' ? 'Update' : 'Delete'} File body (expected @@ hunk or blank): "${l}"`;
        }
      }
    }

    ops.push({ type: opType, filePath, hunks });
  }

  // Finding 3: End Patch marker is required
  if (!sawEnd) {
    return 'Missing "*** End Patch" marker. Begin Patch block is incomplete.';
  }

  if (ops.length === 0) {
    return 'No file operations found in Begin Patch block.';
  }

  return ops;
}

/**
 * Apply a single hunk to file content lines using context matching.
 * Returns the new lines on success, or an error string.
 *
 * Pure insertion hunks (no context or removal lines) are rejected: they provide no
 * anchor for placement and would silently append to EOF, which is almost never correct
 * for Update File. Add context lines (space-prefixed) to pin the insertion location.
 */
function applyHunk(contentLines: string[], hunk: BeginPatchHunk): string[] | string {
  // Build old-side pattern (context + removes)
  const oldPattern = hunk.lines
    .filter(l => l.prefix === ' ' || l.prefix === '-')
    .map(l => l.content);

  if (oldPattern.length === 0) {
    return 'Pure insertion hunk has no context or removal lines. ' +
      'Provide at least one surrounding context line (space-prefixed) to anchor the insertion. ' +
      'Re-generate the patch with context lines.';
  }

  // Search for the old pattern in contentLines
  const matchAt = (startIdx: number): boolean => {
    if (startIdx + oldPattern.length > contentLines.length) return false;
    for (let j = 0; j < oldPattern.length; j++) {
      if (contentLines[startIdx + j] !== oldPattern[j]) return false;
    }
    return true;
  };

  let matchIndex = -1;
  let matchCount = 0;
  for (let i = 0; i <= contentLines.length - oldPattern.length; i++) {
    if (matchAt(i)) {
      matchIndex = i;
      matchCount++;
    }
  }

  if (matchCount === 0) {
    return `Context not found in file. Expected lines:\n${oldPattern.map(l => '  ' + l).join('\n')}`;
  }
  if (matchCount > 1) {
    return `Context is ambiguous — found ${matchCount} matches. Provide more surrounding context lines to identify the correct location.`;
  }

  // Build replacement lines (context + adds, no removes)
  const replacementLines = hunk.lines
    .filter(l => l.prefix === ' ' || l.prefix === '+')
    .map(l => l.content);

  return [
    ...contentLines.slice(0, matchIndex),
    ...replacementLines,
    ...contentLines.slice(matchIndex + oldPattern.length),
  ];
}

function beginPatchContentLines(rawContent: string): string[] {
  const trailingNewline = rawContent.endsWith('\n');
  let contentLines = rawContent.split('\n');
  if (trailingNewline && contentLines[contentLines.length - 1] === '') {
    contentLines = contentLines.slice(0, -1);
  }
  return contentLines;
}

function beginPatchLinesToContent(lines: string[]): string {
  return lines.join('\n') + '\n';
}

function planBeginPatch(parsed: BeginPatchOp[], cwd: string): { writes: PlannedBeginPatchWrite[]; results: string[] } | string {
  const states = new Map<string, { filePath: string; absPath: string; exists: boolean; lines: string[] }>();
  const results: string[] = [];

  const stateFor = (filePath: string): { filePath: string; absPath: string; exists: boolean; lines: string[] } | string => {
    const pathErr = validateSafePath(filePath, cwd);
    if (pathErr) return `Path safety error: ${pathErr}`;

    const absPath = path.resolve(cwd, filePath);
    const symlinkErr = validateNoSymlinkSegments(absPath, cwd);
    if (symlinkErr) return symlinkErr;
    const existing = states.get(absPath);
    if (existing) return existing;

    const exists = fs.existsSync(absPath);
    let lines: string[] = [];
    if (exists) {
      const stat = fs.lstatSync(absPath);
      if (!stat.isFile()) return `Path is not a file: "${filePath}"`;
      lines = beginPatchContentLines(fs.readFileSync(absPath, 'utf8'));
    }
    const state = { filePath, absPath, exists, lines };
    states.set(absPath, state);
    return state;
  };

  for (const op of parsed) {
    const state = stateFor(op.filePath);
    if (typeof state === 'string') return state;

    if (op.type === 'add') {
      if (state.exists) {
        return `Add File failed: "${op.filePath}" already exists. Use "Update File" to modify it.`;
      }
      const newLines: string[] = [];
      for (const hunk of op.hunks) {
        for (const l of hunk.lines) {
          if (l.prefix === '+') newLines.push(l.content);
        }
      }
      state.exists = true;
      state.lines = newLines;
      results.push(`Added: ${op.filePath}`);

    } else if (op.type === 'delete') {
      if (!state.exists) {
        return `Delete File failed: "${op.filePath}" does not exist.`;
      }
      state.exists = false;
      state.lines = [];
      results.push(`Deleted: ${op.filePath}`);

    } else if (op.type === 'update') {
      if (op.hunks.length === 0) {
        return `Update File "${op.filePath}" has no hunks — no changes to apply. Add @@ hunk content or use Delete File to remove.`;
      }
      if (!state.exists) {
        return `Update File failed: "${op.filePath}" does not exist. Use "Add File" to create it.`;
      }
      let contentLines = [...state.lines];
      for (const hunk of op.hunks) {
        const applied = applyHunk(contentLines, hunk);
        if (typeof applied === 'string') {
          return `Hunk failed for "${op.filePath}": ${applied}`;
        }
        contentLines = applied;
      }
      state.lines = contentLines;
      results.push(`Updated: ${op.filePath}`);
    }
  }

  const writes: PlannedBeginPatchWrite[] = [];
  for (const state of states.values()) {
    writes.push({
      filePath: state.filePath,
      absPath: state.absPath,
      action: state.exists ? 'write' : 'delete',
      content: state.exists ? beginPatchLinesToContent(state.lines) : undefined,
    });
  }
  return { writes, results };
}

function commitBeginPatchPlan(plan: { writes: PlannedBeginPatchWrite[]; results: string[] }, cwd: string): { ok: true } | { ok: false; text: string } {
  const snapshots = createFileSnapshots(plan.writes.map(w => w.absPath), cwd);
  if (typeof snapshots === 'string') {
    return { ok: false, text: formatPatchMessage(atomicNoWriteGuidance(`Begin Patch preflight snapshot error: ${snapshots}`), 'abort') };
  }

  try {
    for (const write of plan.writes) {
      const symlinkErr = validateNoSymlinkSegments(write.absPath, cwd);
      if (symlinkErr) throw new Error(symlinkErr);
      if (write.action === 'write') {
        fs.mkdirSync(path.dirname(write.absPath), { recursive: true });
        fs.writeFileSync(write.absPath, write.content ?? '', 'utf8');
      } else {
        fs.rmSync(write.absPath, { force: true });
      }
    }
    return { ok: true };
  } catch (err) {
    const rollback = restoreFileSnapshots(snapshots, cwd);
    return {
      ok: false,
      text: appendRollbackGuidance(
        formatPatchMessage(`Begin Patch commit error: ${err instanceof Error ? err.message : String(err)}`, 'retry'),
        rollback
      ),
    };
  }
}

/**
 * Apply Begin Patch format to the given cwd.
 * Returns a result object with success text or error info.
 */
function applyBeginPatch(patchContent: string, cwd: string): { content: { type: string; text: string }[]; isError?: boolean } {
  const parsed = parseBeginPatch(patchContent);
  if (typeof parsed === 'string') {
    return {
      content: [{ type: 'text', text: formatPatchMessage(`Begin Patch parse error: ${parsed}`, 'abort') }],
      isError: true,
    };
  }

  const plan = planBeginPatch(parsed, cwd);
  if (typeof plan === 'string') {
    return {
      content: [{ type: 'text', text: formatPatchMessage(atomicNoWriteGuidance(plan), plan.includes('Hunk failed') ? 'retry' : 'abort') }],
      isError: true,
    };
  }

  const commitResult = commitBeginPatchPlan(plan, cwd);
  if (!commitResult.ok) {
    return { content: [{ type: 'text', text: commitResult.text }], isError: true };
  }

  const summary = plan.results.join('\n');
  return { content: [{ type: 'text', text: `[apply_patch] Begin Patch applied successfully.\n${summary}` }] };
}

/** Classify patch failure output into AI-actionable diagnostic text. */
function classifyPatchFailure(
  format: PatchFormat,
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

  if (output.includes("can't find file to patch")) {
    const strip = format === 'plain-unified' ? '-p0' : '-p1';
    return formatPatchMessage(
      `Cannot find file to patch (applied with ${strip}). Verify the target file exists relative to the working directory (cwd), ` +
      "or adjust the 'cwd' parameter. Ensure path prefixes match the strip level.",
      'disambiguate'
    );
  }

  if (output.includes('File to patch:') || output.includes('Skip this patch?') || output.includes('Ignore this patch?')) {
    return formatPatchMessage(
      'Patch required interactive input. The file referenced in the patch may not exist, or the strip level is wrong for the header format. Check paths relative to cwd.',
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
    description: [
      'Apply a patch to files in the workspace. Modifies local file state.',
      '',
      'Supported formats (auto-detected):',
      '1. Git-style unified diff (preferred): headers "--- a/path" / "+++ b/path", applied with patch -p1.',
      '2. Plain unified diff: headers "--- path" / "+++ path" (no a/ b/ prefix), applied with patch -p0.',
      '3. OpenAI/GPT-style Begin Patch: starts with "*** Begin Patch", supports',
      '   "*** Add File: path", "*** Update File: path", "*** Delete File: path" with @@ hunks.',
      '   Add File also accepts bare + lines without an @@ header.',
    ].join('\n'),
    inputSchema: {
      type: 'object',
      properties: {
        patch: {
          type: 'string',
          description: [
            'Patch content. Supported formats:',
            '• Git-style unified diff (preferred): "--- a/path" / "+++ b/path" headers. Generate with git diff or git format-patch.',
            '• Plain unified diff: "--- path" / "+++ path" headers (no a/ b/ prefix).',
            '• OpenAI/GPT Begin Patch: starts with "*** Begin Patch", must end with "*** End Patch".',
            '  Uses "*** Add/Update/Delete File: path" directives with @@ hunks.',
            '  Add File also accepts bare + lines (no @@ header).',
          ].join('\n'),
        },
        cwd: { type: 'string', description: 'Working directory to apply patch in (optional, defaults to current)' }
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

    const format = detectPatchFormat(patch);

    // Route Begin Patch to our own parser/applicator
    if (format === 'begin-patch') {
      return applyBeginPatch(patch, cwd);
    }

    // Mixed git-style and plain unified diff headers — ambiguous strip level, must reject
    if (format === 'mixed-unified') {
      return {
        content: [{
          type: 'text',
          text: formatPatchMessage(
            'Patch contains mixed unified diff header styles: some files use git-style headers ' +
            '("--- a/path" / "+++ b/path") and others use plain headers ("--- path" / "+++ path"). ' +
            'These require different strip levels (-p1 vs -p0) and cannot be applied together. ' +
            'Re-generate the patch in one consistent format: use git diff or git format-patch for ' +
            'git-style headers, or diff -u for plain headers.',
            'abort'
          )
        }],
        isError: true,
      };
    }

    // Unknown format — return a structured, AI-actionable error
    if (format === 'unknown') {
      return {
        content: [{
          type: 'text',
          text: formatPatchMessage(
            'Unrecognized patch format. Supported formats: ' +
            '(1) git-style unified diff with "--- a/path" / "+++ b/path" headers (preferred), ' +
            '(2) plain unified diff with "--- path" / "+++ path" headers, ' +
            '(3) OpenAI/GPT "*** Begin Patch" format. ' +
            'Re-generate the patch in one of these formats.',
            'abort'
          )
        }],
        isError: true,
      };
    }

    // Finding 5: Preflight path safety check for unified diffs before invoking patch binary
    const pathSafetyErr = validateUnifiedDiffPaths(patch, format, cwd);
    if (pathSafetyErr) {
      return {
        content: [{ type: 'text', text: formatPatchMessage(pathSafetyErr, 'abort') }],
        isError: true,
      };
    }

    // Unified diff (git-style or plain) — use the patch binary
    const stripLevel = format === 'plain-unified' ? '-p0' : '-p1';
    const targetPaths = collectUnifiedDiffTargetPaths(patch, format, cwd);
    if (typeof targetPaths === 'string') {
      return {
        content: [{ type: 'text', text: formatPatchMessage(atomicNoWriteGuidance(targetPaths), 'abort') }],
        isError: true,
      };
    }
    const snapshots = createFileSnapshots([...targetPaths, ...patchArtifactPaths(targetPaths)], cwd);
    if (typeof snapshots === 'string') {
      return {
        content: [{ type: 'text', text: formatPatchMessage(atomicNoWriteGuidance(`Patch preflight snapshot error: ${snapshots}`), 'abort') }],
        isError: true,
      };
    }
    const tmpFile = path.join(os.tmpdir(), `mcphub_patch_${Date.now()}.patch`);
    fs.writeFileSync(tmpFile, patch, 'utf8');
    try {
      // -f forces non-interactive behavior so the adapter returns diagnostics instead of waiting for input.
      const result = child_process.spawnSync('patch', [stripLevel, '-f', '--input', tmpFile], { cwd, encoding: 'utf8', timeout: 30000 });
      fs.unlinkSync(tmpFile);
      if (result.error || result.status !== 0) {
        const rollback = restoreFileSnapshots(snapshots, cwd);
        const text = appendRollbackGuidance(
          classifyPatchFailure(format, result.error ?? null, result.stdout ?? '', result.stderr ?? ''),
          rollback
        );
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
