import * as readline from 'readline';
import * as https from 'https';
import * as http from 'http';
import * as fs from 'fs';
import * as path from 'path';
import { URL } from 'url';

const UA = 'Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0';
const MAX_BODY_CHARS = 50000;
const MAX_REDIRECTS = 5;
const BRAVE_KEY_PATH = path.join(process.env['HOME'] || '', '.config', 'mcphub', 'brave-api-key');

/** Load Brave Search API key from file. */
function loadBraveApiKey(): string | null {
  try {
    return fs.readFileSync(BRAVE_KEY_PATH, 'utf-8').trim();
  } catch {
    return null;
  }
}

const TOOLS = [
  {
    name: 'webfetch',
    description: 'Fetch content from a URL (HTTP/HTTPS). Use for web page reading, API calls, or downloading resources. Modifies external state. [modifies external state]',
    inputSchema: {
      type: 'object',
      properties: {
        url: { type: 'string', description: 'URL to fetch' },
        method: { type: 'string', default: 'GET', description: 'HTTP method (default: GET)' },
        body: { type: 'string', description: 'Request body (optional, for POST/PUT)' },
        headers: { type: 'object', description: 'Request headers as key-value pairs (optional)' }
      },
      required: ['url'],
      additionalProperties: false
    }
  },
  {
    name: 'websearch',
    description: 'Search the web for pages matching a query. Returns a list of results with titles, URLs, and snippets. Read-only. (read-only)',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Search query string' }
      },
      required: ['query'],
      additionalProperties: false
    }
  }
];

// --- HTTP helpers ---

interface HttpResponse {
  statusCode: number;
  headers: Record<string, string | string[] | undefined>;
  body: string;
  finalUrl: string;
}

function httpRequest(
  urlStr: string,
  method: string,
  body?: string,
  extraHeaders?: Record<string, string>,
  maxRedirects = MAX_REDIRECTS
): Promise<HttpResponse> {
  return new Promise((resolve, reject) => {
    const url = new URL(urlStr);
    const lib = url.protocol === 'https:' ? https : http;
    const headers: Record<string, string> = {
      'User-Agent': UA,
      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'Accept-Language': 'en-US,en;q=0.5',
      ...extraHeaders
    };
    const opts = {
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: url.pathname + url.search,
      method: method || 'GET',
      headers,
      timeout: 30000
    };
    const req = lib.request(opts, (res) => {
      // Follow redirects
      if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && maxRedirects > 0) {
        let redirectUrl = res.headers.location;
        // Handle relative redirects
        if (redirectUrl.startsWith('/')) {
          redirectUrl = `${url.protocol}//${url.host}${redirectUrl}`;
        }
        res.resume(); // consume response body
        httpRequest(redirectUrl, method, body, extraHeaders, maxRedirects - 1).then(resolve, reject);
        return;
      }
      let data = '';
      let truncated = false;
      res.on('data', (chunk: Buffer) => {
        if (truncated) return;
        data += chunk.toString();
        if (data.length > MAX_BODY_CHARS * 2) {
          truncated = true;
          res.destroy();
          resolve({
            statusCode: res.statusCode || 0,
            headers: res.headers as Record<string, string | string[] | undefined>,
            body: data.slice(0, MAX_BODY_CHARS * 2),
            finalUrl: urlStr
          });
        }
      });
      res.on('end', () => {
        if (truncated) return;
        resolve({
          statusCode: res.statusCode || 0,
          headers: res.headers as Record<string, string | string[] | undefined>,
          body: data,
          finalUrl: urlStr
        });
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Request timed out (30s)')); });
    if (body) req.write(body);
    req.end();
  });
}

/** Strip HTML tags and collapse whitespace. */
function htmlToText(html: string): string {
  return html
    // Remove script/style blocks entirely
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, '')
    .replace(/<noscript[^>]*>[\s\S]*?<\/noscript>/gi, '')
    // Convert common block elements to newlines
    .replace(/<\/(p|div|h[1-6]|li|tr|br\s*\/?)>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    // Strip remaining tags
    .replace(/<[^>]+>/g, '')
    // Decode common HTML entities
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#x27;/g, "'")
    .replace(/&nbsp;/g, ' ')
    // Collapse whitespace
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s*\n+/g, '\n\n')
    .trim();
}

// --- Tool dispatch ---

type ToolResult = { content: { type: string; text: string }[]; isError?: boolean };

async function dispatch(name: string, args: Record<string, unknown>): Promise<ToolResult> {
  if (name === 'webfetch') {
    return handleWebfetch(args);
  }
  if (name === 'websearch') {
    return handleWebsearch(args);
  }
  throw new Error(`Unknown tool: ${name}`);
}

async function handleWebfetch(args: Record<string, unknown>): Promise<ToolResult> {
  const url = args['url'] as string;
  const method = (args['method'] as string) || 'GET';
  const body = args['body'] as string | undefined;
  const headers = args['headers'] as Record<string, string> | undefined;
  if (!url) throw new Error('url is required');

  const resp = await httpRequest(url, method, body, headers);

  // Determine content type
  const contentType = (resp.headers['content-type'] as string) || '';
  const isHtml = contentType.includes('text/html') || contentType.includes('application/xhtml');
  const isJson = contentType.includes('application/json');

  let output: string;
  if (isJson) {
    // JSON: return as-is (truncated if needed)
    output = resp.body.slice(0, MAX_BODY_CHARS);
  } else if (isHtml) {
    // HTML: convert to readable text
    const text = htmlToText(resp.body);
    output = text.slice(0, MAX_BODY_CHARS);
  } else {
    // Other: return raw (truncated)
    output = resp.body.slice(0, MAX_BODY_CHARS);
  }

  const prefix = `HTTP ${resp.statusCode}`;
  const redirectNote = resp.finalUrl !== url ? ` (redirected to: ${resp.finalUrl})` : '';
  const truncNote = resp.body.length > MAX_BODY_CHARS ? `\n[Truncated: showing first ${MAX_BODY_CHARS} chars of ${resp.body.length}]` : '';

  return { content: [{ type: 'text', text: `${prefix}${redirectNote}${truncNote}\n${output}` }] };
}

async function handleWebsearch(args: Record<string, unknown>): Promise<ToolResult> {
  const query = args['query'] as string;
  if (!query) throw new Error('query is required');

  const apiKey = loadBraveApiKey();
  if (!apiKey) {
    return {
      content: [{
        type: 'text',
        text: '[websearch] Brave Search API key not found.\n' +
          `Expected at: ${BRAVE_KEY_PATH}\n` +
          'Run: echo "YOUR_KEY" > ~/.config/mcphub/brave-api-key && chmod 600 ~/.config/mcphub/brave-api-key'
      }],
      isError: true
    };
  }

  const encodedQuery = encodeURIComponent(query);
  const resp = await httpRequest(
    `https://api.search.brave.com/res/v1/web/search?q=${encodedQuery}&count=10`,
    'GET',
    undefined,
    {
      'Accept': 'application/json',
      'X-Subscription-Token': apiKey
    },
    0 // no redirects for API calls
  );

  if (resp.statusCode === 401 || resp.statusCode === 403) {
    return {
      content: [{
        type: 'text',
        text: `[websearch] Brave Search API authentication failed (HTTP ${resp.statusCode}).\n` +
          'Check that the API key in ~/.config/mcphub/brave-api-key is valid.'
      }],
      isError: true
    };
  }

  if (resp.statusCode === 429) {
    return {
      content: [{
        type: 'text',
        text: '[websearch] Brave Search API rate limit exceeded.\n' +
          'Monthly credit quota may be exhausted. Check https://api-dashboard.search.brave.com/'
      }],
      isError: true
    };
  }

  if (resp.statusCode !== 200) {
    return {
      content: [{
        type: 'text',
        text: `[websearch] Brave Search API error (HTTP ${resp.statusCode}).\n${resp.body.slice(0, 500)}`
      }],
      isError: true
    };
  }

  // Parse Brave Search JSON response
  let data: { web?: { results?: Array<{ title?: string; url?: string; description?: string }> } };
  try {
    data = JSON.parse(resp.body);
  } catch {
    return {
      content: [{
        type: 'text',
        text: `[websearch] Failed to parse Brave Search response.\n${resp.body.slice(0, 500)}`
      }],
      isError: true
    };
  }

  const webResults = data?.web?.results;
  if (!webResults || webResults.length === 0) {
    return {
      content: [{
        type: 'text',
        text: `No results found for: "${query}"`
      }]
    };
  }

  const results = webResults.slice(0, 10).map((r, i) => {
    const title = r.title || '(no title)';
    const url = r.url || '';
    // Strip HTML tags from description
    const desc = r.description
      ? r.description.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
      : '';
    return `${i + 1}. ${title}\n   URL: ${url}${desc ? '\n   ' + desc : ''}`;
  });

  return { content: [{ type: 'text', text: results.join('\n\n') }] };
}

// --- MCP stdio server ---
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
  try { req = JSON.parse(line); } catch { respondError(null, -32700, 'Parse error'); return; }
  const { id, method, params } = req as { id?: unknown; method?: string; params?: Record<string, unknown> };
  try {
    if (method === 'initialize') {
      respond(id, {
        protocolVersion: '2024-11-05',
        serverInfo: { name: 'mcphub-web', version: '0.2.0-alpha' },
        capabilities: { tools: {} }
      });
    } else if (method === 'notifications/initialized') {
      /* no-op */
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
