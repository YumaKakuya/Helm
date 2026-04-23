import * as readline from 'readline';

const TOOLS = [
  {
    name: 'synthetic_delay',
    description: 'Test-only tool with configurable delay. VT-017 latency exclusion validation.',
    inputSchema: {
      type: 'object',
      properties: {
        delay_ms: { type: 'number', description: 'Milliseconds to sleep', default: 500 }
      }
    }
  }
];

async function dispatch(
  name: string,
  args: Record<string, unknown>
): Promise<{ content: { type: string; text: string }[]; isError?: boolean }> {
  if (name === 'synthetic_delay') {
    const delayMs = typeof args['delay_ms'] === 'number' ? (args['delay_ms'] as number) : 500;
    await new Promise((r) => setTimeout(r, delayMs));
    return { content: [{ type: 'text', text: `slept ${delayMs}ms` }] };
  }
  throw new Error(`Unknown tool: ${name}`);
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });
const out = process.stdout;

function respond(id: unknown, result: unknown): void {
  out.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function respondError(id: unknown, code: number, message: string): void {
  out.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

rl.on('line', async (line) => {
  if (!line.trim()) return;
  let req: Record<string, unknown>;
  try {
    req = JSON.parse(line);
  } catch {
    respondError(null, -32700, 'Parse error');
    return;
  }

  const { id, method, params } = req as {
    id?: unknown;
    method?: string;
    params?: Record<string, unknown>;
  };

  try {
    if (method === 'initialize') {
      respond(id, {
        protocolVersion: '2024-11-05',
        serverInfo: { name: 'mcphub-synthetic', version: '0.1.0-alpha' },
        capabilities: { tools: {} }
      });
    } else if (method === 'notifications/initialized') {
      // no-op
    } else if (method === 'tools/list') {
      respond(id, { tools: TOOLS });
    } else if (method === 'tools/call') {
      const p = params as { name?: string; arguments?: Record<string, unknown> };
      respond(id, await dispatch(p?.name ?? '', p?.arguments ?? {}));
    } else {
      respondError(id, -32601, `Unknown method: ${method}`);
    }
  } catch (err) {
    respondError(id, -32603, err instanceof Error ? err.message : String(err));
  }
});
