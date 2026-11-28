import { AiProviderId, ApiProcessingMode, ProviderUsage } from './models';

export interface GatewayModelEntry {
  id: string;
  inputPriceUsd: number | null;
  outputPriceUsd: number | null;
  supportsTextOutput: boolean;
}

const BLOCKED_HOST_SUFFIXES = [
  '.local',
  '.localhost',
  '.internal',
  '.invalid',
  '.test',
  '.example',
  '.arpa',
  '.onion',
  '.alt',
];

function isPrivateOrReservedIpLiteral(host: string): boolean {
  const clean = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (/^\d+\.\d+\.\d+\.\d+$/.test(clean)) {
    const parts = clean.split('.').map(Number);
    if (parts.some((p) => Number.isNaN(p) || p < 0 || p > 255)) return true;
    const [a, b] = parts;
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 169 && b === 254) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
    if (a >= 224) return true;
    return false;
  }
  if (clean.includes(':')) {
    if (clean === '::' || clean === '::1') return true;
    if (clean.startsWith('fc') || clean.startsWith('fd') || clean.startsWith('fe80')) return true;
    if (clean.startsWith('::ffff:')) {
      return isPrivateOrReservedIpLiteral(clean.slice(7));
    }
    return false;
  }
  return false;
}

export function assertSafePublicHttpsUrl(rawUrl: string): URL {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl.trim());
  } catch {
    throw new Error('Invalid HTTPS URL.');
  }
  if (parsed.protocol !== 'https:') {
    throw new Error('Only public HTTPS endpoints are permitted.');
  }
  if (parsed.username || parsed.password || parsed.hash) {
    throw new Error('URLs with embedded credentials or fragments are not permitted.');
  }
  const hostname = parsed.hostname.toLowerCase().replace(/\.$/, '');
  if (
    !hostname ||
    hostname === 'localhost' ||
    BLOCKED_HOST_SUFFIXES.some((s) => hostname.endsWith(s)) ||
    isPrivateOrReservedIpLiteral(hostname)
  ) {
    throw new Error('Local, loopback, and private network endpoints are blocked.');
  }
  return parsed;
}

function formatFetchError(err: unknown, context: string): string {
  if (err instanceof TypeError) {
    return `${context}: Direct browser request blocked by network or CORS policy on target host.`;
  }
  return err instanceof Error ? err.message : `${context}: Request failed.`;
}

export interface StreamChatParams {
  provider: AiProviderId;
  model: string;
  apiKey?: string;
  customUrl?: string;
  systemPrompt?: string;
  messages: Array<{ role: 'user' | 'assistant'; text: string }>;
  apiProcessingMode?: ApiProcessingMode;
  signal: AbortSignal;
  onDelta: (text: string) => void;
  onUsage: (usage: ProviderUsage | undefined, resolvedModel?: string) => void;
  onError: (message: string) => void;
}

export async function streamProviderChatDirect(params: StreamChatParams): Promise<void> {
  const {
    provider,
    model,
    apiKey,
    customUrl,
    systemPrompt,
    messages,
    apiProcessingMode,
    signal,
    onDelta,
    onUsage,
    onError,
  } = params;

  if (!messages.length) {
    onError('No conversation messages supplied.');
    return;
  }

  try {
    if (provider === 'gemini') {
      if (!apiKey) {
        onError('Gemini API key is required.');
        return;
      }
      const safeModel = encodeURIComponent(model || 'gemini-2.5-flash');
      const url = `https://generativelanguage.googleapis.com/v1beta/models/${safeModel}:streamGenerateContent?alt=sse`;
      const contents = messages.map((m) => ({
        role: m.role === 'assistant' ? 'model' : 'user',
        parts: [{ text: m.text }],
      }));
      const body: Record<string, unknown> = { contents };
      if (systemPrompt?.trim()) {
        body.systemInstruction = { parts: [{ text: systemPrompt }] };
      }
      const upstream = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-goog-api-key': apiKey,
        },
        body: JSON.stringify(body),
        signal,
      });
      if (!upstream.ok || !upstream.body) {
        const errText = await upstream.text().catch(() => '');
        onError(`Gemini HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`);
        return;
      }
      await readSseLines(upstream.body, (jsonStr) => {
        if (jsonStr === '[DONE]') return;
        const parsed = JSON.parse(jsonStr);
        const parts = parsed?.candidates?.[0]?.content?.parts;
        if (Array.isArray(parts)) {
          for (const part of parts) {
            if (typeof part?.text === 'string' && part.text.length > 0 && !part.thought) {
              onDelta(part.text);
            }
          }
        }
        if (parsed?.usageMetadata) {
          const um = parsed.usageMetadata;
          onUsage({
            inputTokens: um.promptTokenCount ?? undefined,
            outputTokens: um.candidatesTokenCount ?? undefined,
            totalTokens: um.totalTokenCount ?? undefined,
            cachedTokens: um.cachedContentTokenCount ?? undefined,
            reasoningTokens: um.thoughtsTokenCount ?? undefined,
          });
        }
      });
      return;
    }

    if (provider === 'claude') {
      if (!apiKey) {
        onError('Anthropic Claude API key is required.');
        return;
      }
      const body: Record<string, unknown> = {
        model: model || 'claude-sonnet-4-5',
        max_tokens: 4096,
        stream: true,
        messages: messages.map((m) => ({ role: m.role, content: m.text })),
      };
      if (systemPrompt?.trim()) {
        body.system = systemPrompt;
      }
      const upstream = await fetch('https://api.anthropic.com/v1/messages', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': apiKey,
          'anthropic-version': '2023-06-01',
          'anthropic-dangerous-direct-browser-access': 'true',
        },
        body: JSON.stringify(body),
        signal,
      });
      if (!upstream.ok || !upstream.body) {
        const errText = await upstream.text().catch(() => '');
        onError(`Claude HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`);
        return;
      }
      await readSseLines(upstream.body, (jsonStr) => {
        const evt = JSON.parse(jsonStr);
        if (
          evt.type === 'content_block_delta' &&
          evt.delta?.type === 'text_delta' &&
          typeof evt.delta?.text === 'string'
        ) {
          onDelta(evt.delta.text);
        } else if (evt.type === 'message_start' && evt.message?.model) {
          onUsage(
            evt.message.usage
              ? {
                  inputTokens: evt.message.usage.input_tokens,
                  cachedTokens: evt.message.usage.cache_read_input_tokens,
                }
              : undefined,
            evt.message.model,
          );
        } else if (evt.type === 'message_delta' && evt.usage) {
          onUsage({ outputTokens: evt.usage.output_tokens });
        } else if (evt.type === 'error') {
          onError(evt.error?.message || 'Anthropic stream error');
        }
      });
      return;
    }

    if (provider === 'chatgpt') {
      if (!apiKey) {
        onError('OpenAI API key is required.');
        return;
      }
      const body: Record<string, unknown> = {
        model: model || 'gpt-4o',
        stream: true,
        store: false,
        input: messages.map((m) => ({ role: m.role, content: m.text })),
      };
      if (systemPrompt?.trim()) {
        body.instructions = systemPrompt;
      }
      if (apiProcessingMode && apiProcessingMode !== 'auto') {
        body.service_tier = apiProcessingMode === 'fast' ? 'priority' : apiProcessingMode;
      }
      const upstream = await fetch('https://api.openai.com/v1/responses', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify(body),
        signal,
      });
      if (!upstream.ok || !upstream.body) {
        const errText = await upstream.text().catch(() => '');
        onError(`OpenAI HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`);
        return;
      }
      await readSseLines(upstream.body, (jsonStr) => {
        if (jsonStr === '[DONE]') return;
        const evt = JSON.parse(jsonStr);
        if (evt.type === 'response.output_text.delta' && typeof evt.delta === 'string') {
          onDelta(evt.delta);
        } else if (evt.type === 'response.completed' && evt.response) {
          const u = evt.response.usage;
          onUsage(
            u
              ? {
                  inputTokens: u.input_tokens,
                  outputTokens: u.output_tokens,
                  totalTokens: u.total_tokens,
                  cachedTokens: u.input_tokens_details?.cached_tokens,
                  reasoningTokens: u.output_tokens_details?.reasoning_tokens,
                }
              : undefined,
            evt.response.model,
          );
        } else if (evt.type === 'response.failed') {
          onError(evt.response?.error?.message || 'OpenAI response failed');
        }
      });
      return;
    }

    // OpenAI-compatible gateways
    let targetUrl = '';
    if (provider === 'deepseek') targetUrl = 'https://api.deepseek.com/chat/completions';
    else if (provider === 'kimi') targetUrl = 'https://api.moonshot.ai/v1/chat/completions';
    else if (provider === 'openrouter') targetUrl = 'https://openrouter.ai/api/v1/chat/completions';
    else if (provider === 'aihubmix') targetUrl = 'https://aihubmix.com/v1/chat/completions';
    else if (provider === 'vercel') targetUrl = 'https://ai-gateway.vercel.sh/v1/chat/completions';
    else if (provider === 'custom') {
      if (!customUrl) {
        onError('Custom HTTPS endpoint URL is required.');
        return;
      }
      const verified = assertSafePublicHttpsUrl(customUrl);
      targetUrl = `${verified.toString().replace(/\/+$/, '')}/chat/completions`;
    } else {
      onError(`Unsupported provider: ${provider}`);
      return;
    }

    if (provider !== 'custom' && !apiKey) {
      onError(`API key for ${provider} is required.`);
      return;
    }

    const compatMessages: Array<{ role: string; content: string }> = [];
    if (systemPrompt?.trim()) {
      compatMessages.push({ role: 'system', content: systemPrompt });
    }
    for (const m of messages) {
      compatMessages.push({ role: m.role, content: m.text });
    }

    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

    const upstream = await fetch(targetUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        model,
        messages: compatMessages,
        stream: true,
      }),
      signal,
    });
    if (!upstream.ok || !upstream.body) {
      const errText = await upstream.text().catch(() => '');
      onError(`Provider HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`);
      return;
    }
    await readSseLines(upstream.body, (jsonStr) => {
      if (jsonStr === '[DONE]') return;
      const evt = JSON.parse(jsonStr);
      const delta = evt?.choices?.[0]?.delta?.content;
      if (typeof delta === 'string' && delta.length > 0) {
        onDelta(delta);
      }
      if (evt?.model || evt?.usage) {
        onUsage(
          evt.usage
            ? {
                inputTokens: evt.usage.prompt_tokens ?? evt.usage.input_tokens,
                outputTokens: evt.usage.completion_tokens ?? evt.usage.output_tokens,
                totalTokens: evt.usage.total_tokens,
                cachedTokens:
                  evt.usage.prompt_tokens_details?.cached_tokens ??
                  evt.usage.prompt_cache_hit_tokens,
              }
            : undefined,
          evt.model,
        );
      }
    });
  } catch (err) {
    if ((err as Error)?.name === 'AbortError') {
      throw err;
    }
    onError(formatFetchError(err, provider));
  }
}

async function readSseLines(body: ReadableStream<Uint8Array>, onEvent: (jsonStr: string) => void): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line.startsWith('data:')) continue;
      const jsonStr = line.slice(5).trim();
      if (!jsonStr) continue;
      try {
        onEvent(jsonStr);
      } catch {
        // ignore partial JSON frame
      }
    }
  }
}

export async function fetchGatewayCatalogDirect(
  provider: AiProviderId,
  apiKey?: string,
  customUrl?: string | null,
): Promise<GatewayModelEntry[]> {
  let url = '';
  if (provider === 'openrouter') url = 'https://openrouter.ai/api/v1/models';
  else if (provider === 'aihubmix') url = 'https://aihubmix.com/v1/models';
  else if (provider === 'vercel') url = 'https://ai-gateway.vercel.sh/v1/models';
  else if (provider === 'custom') {
    if (!customUrl) throw new Error('Custom URL required.');
    const verified = assertSafePublicHttpsUrl(customUrl);
    url = `${verified.toString().replace(/\/+$/, '')}/models`;
  } else {
    throw new Error('Unsupported gateway provider.');
  }

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  let upstream: Response;
  try {
    upstream = await fetch(url, { headers });
  } catch (err) {
    throw new Error(formatFetchError(err, provider));
  }
  if (!upstream.ok) {
    throw new Error(`HTTP ${upstream.status}`);
  }
  const json = (await upstream.json()) as { data?: Array<Record<string, unknown>> };
  return Array.isArray(json?.data)
    ? json.data
        .map((item) => {
          const id = typeof item.id === 'string' ? item.id.trim() : '';
          const pricing = item.pricing as Record<string, unknown> | undefined;
          const inputPrice =
            pricing?.prompt !== undefined
              ? Number(pricing.prompt)
              : pricing?.input !== undefined
                ? Number(pricing.input)
                : null;
          const outputPrice =
            pricing?.completion !== undefined
              ? Number(pricing.completion)
              : pricing?.output !== undefined
                ? Number(pricing.output)
                : null;
          return {
            id,
            inputPriceUsd: Number.isFinite(inputPrice) ? inputPrice : null,
            outputPriceUsd: Number.isFinite(outputPrice) ? outputPrice : null,
            supportsTextOutput: true,
          };
        })
        .filter((e) => e.id.length > 0)
    : [];
}

export interface DiscoveredMcpTool {
  name: string;
  title?: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export type McpDiscoverResult =
  | { status: 'success'; endpoint: string; tools: DiscoveredMcpTool[] }
  | {
      status: 'authorization_required';
      challenge: { resourceMetadataUrl: string | null; scopes: string[] };
    }
  | { status: 'failure'; reason: string };

export async function discoverMcpToolsDirect(endpoint: string): Promise<McpDiscoverResult> {
  try {
    const url = assertSafePublicHttpsUrl(endpoint);
    const upstream = await fetch(url.toString(), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        Accept: 'application/json, text/event-stream',
        'MCP-Protocol-Version': '2026-07-28',
        'Mcp-Method': 'tools/list',
      },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'tools/list',
        params: {},
      }),
    });

    if (upstream.status === 401) {
      const wwwAuth = upstream.headers.get('www-authenticate') || '';
      const resourceMatch = wwwAuth.match(/resource_metadata="([^"]+)"/i);
      const scopeMatch = wwwAuth.match(/scope="([^"]+)"/i);
      return {
        status: 'authorization_required',
        challenge: {
          resourceMetadataUrl: resourceMatch?.[1] || null,
          scopes: scopeMatch?.[1]?.split(/\s+/).filter(Boolean) || [],
        },
      };
    }

    if (!upstream.ok) {
      return { status: 'failure', reason: `HTTP_${upstream.status}` };
    }

    const raw = await upstream.text();
    if (raw.length > 256_000) {
      return { status: 'failure', reason: 'RESPONSE_TOO_LARGE' };
    }
    const parsed = JSON.parse(raw);
    const tools = Array.isArray(parsed?.result?.tools) ? parsed.result.tools : null;
    if (!tools) {
      return { status: 'failure', reason: 'INVALID_RESPONSE' };
    }
    return {
      status: 'success',
      endpoint: url.toString(),
      tools: tools.slice(0, 128).map((t: Record<string, unknown>) => ({
        name: String(t.name ?? ''),
        title: typeof t.title === 'string' ? t.title : undefined,
        description: typeof t.description === 'string' ? t.description : '',
        inputSchema: (t.inputSchema as Record<string, unknown>) ?? { type: 'object', properties: {} },
      })),
    };
  } catch (err) {
    return { status: 'failure', reason: formatFetchError(err, 'MCP discover') };
  }
}

export async function inspectMcpAuthMetadataDirect(rawUrl: string): Promise<Record<string, unknown>> {
  const parsed = assertSafePublicHttpsUrl(rawUrl);
  const upstream = await fetch(parsed.toString(), {
    method: 'GET',
    headers: { Accept: 'application/json' },
  });
  if (!upstream.ok) {
    throw new Error(`HTTP ${upstream.status}`);
  }
  const text = await upstream.text();
  if (text.length > 128_000) {
    throw new Error('Metadata document too large.');
  }
  return { metadata: JSON.parse(text) };
}

export async function executeMcpToolCallDirect(
  endpoint: string,
  toolName: string,
  argumentsObj: Record<string, unknown>,
): Promise<{ ok: boolean; response?: unknown; error?: string }> {
  try {
    const url = assertSafePublicHttpsUrl(endpoint);
    const upstream = await fetch(url.toString(), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        Accept: 'application/json, text/event-stream',
        'MCP-Protocol-Version': '2026-07-28',
      },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'tools/call',
        params: {
          name: toolName,
          arguments: argumentsObj ?? {},
        },
      }),
    });
    const text = await upstream.text();
    if (text.length > 256_000) {
      return { ok: false, error: 'Tool response exceeded 256 KB bound.' };
    }
    if (!upstream.ok) {
      return { ok: false, error: `HTTP ${upstream.status}` };
    }
    return { ok: true, response: JSON.parse(text) };
  } catch (err) {
    return { ok: false, error: formatFetchError(err, 'MCP tool call') };
  }
}

export async function runAsyncJobActionDirect(params: {
  action: 'start' | 'poll' | 'cancel';
  provider: 'chatgpt' | 'claude' | 'gemini';
  apiKey: string;
  model?: string;
  prompt?: string;
  remoteId?: string;
}): Promise<{ remoteId: string; state: string; outputText?: string; errorMessage?: string }> {
  const { action, provider, apiKey, model, prompt, remoteId } = params;
  if (!apiKey) throw new Error('API key required.');

  if (provider === 'chatgpt') {
    if (action === 'start') {
      const r = await fetch('https://api.openai.com/v1/responses', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
          model: model || 'gpt-4o',
          input: prompt,
          background: true,
          store: true,
        }),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data?.error?.message || `HTTP ${r.status}`);
      return { remoteId: data.id, state: mapOpenAiStatus(data.status), outputText: extractOpenAiText(data) };
    }
    if (action === 'poll' && remoteId) {
      const r = await fetch(`https://api.openai.com/v1/responses/${encodeURIComponent(remoteId)}`, {
        headers: { Authorization: `Bearer ${apiKey}` },
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data?.error?.message || `HTTP ${r.status}`);
      return { remoteId: data.id, state: mapOpenAiStatus(data.status), outputText: extractOpenAiText(data) };
    }
    if (action === 'cancel' && remoteId) {
      const r = await fetch(`https://api.openai.com/v1/responses/${encodeURIComponent(remoteId)}/cancel`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}` },
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data?.error?.message || `HTTP ${r.status}`);
      return { remoteId: data.id, state: 'CANCELLED' };
    }
  }

  if (provider === 'claude') {
    const headers = {
      'Content-Type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'anthropic-dangerous-direct-browser-access': 'true',
    };
    if (action === 'start') {
      const r = await fetch('https://api.anthropic.com/v1/messages/batches', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          requests: [
            {
              custom_id: 'aistee-1',
              params: {
                model: model || 'claude-sonnet-4-5',
                max_tokens: 4096,
                messages: [{ role: 'user', content: prompt }],
              },
            },
          ],
        }),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data?.error?.message || `HTTP ${r.status}`);
      return {
        remoteId: data.id,
        state: data.processing_status === 'ended' ? 'SUCCEEDED' : 'RUNNING',
      };
    }
    if (action === 'poll' && remoteId) {
      const r = await fetch(`https://api.anthropic.com/v1/messages/batches/${encodeURIComponent(remoteId)}`, {
        headers,
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data?.error?.message || `HTTP ${r.status}`);
      let outputText: string | undefined;
      if (data.processing_status === 'ended' && data.results_url) {
        const rr = await fetch(data.results_url, { headers });
        if (rr.ok) {
          const jsonl = await rr.text();
          const firstLine = jsonl.split('\n').find((l) => l.trim());
          if (firstLine) {
            const parsed = JSON.parse(firstLine);
            outputText =
              parsed?.result?.message?.content?.map((c: { text?: string }) => c.text).join('') || undefined;
          }
        }
      }
      return {
        remoteId: data.id,
        state: data.processing_status === 'ended' ? (outputText ? 'SUCCEEDED' : 'FAILED') : 'RUNNING',
        outputText,
      };
    }
    if (action === 'cancel' && remoteId) {
      const r = await fetch(`https://api.anthropic.com/v1/messages/batches/${encodeURIComponent(remoteId)}/cancel`, {
        method: 'POST',
        headers,
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return { remoteId, state: 'CANCELLED' };
    }
  }

  if (provider === 'gemini') {
    if (action === 'start') {
      const safeModel = encodeURIComponent(model || 'gemini-2.5-flash');
      const r = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${safeModel}:batchGenerateContent`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'x-goog-api-key': apiKey,
          },
          body: JSON.stringify({
            batch: {
              display_name: 'Aistee batch job',
              input_config: {
                requests: {
                  requests: [{ request: { contents: [{ parts: [{ text: prompt }] }] } }],
                },
              },
            },
          }),
        },
      );
      const data = await r.json();
      if (!r.ok) throw new Error(data?.error?.message || `HTTP ${r.status}`);
      return { remoteId: data.name, state: data.done ? 'SUCCEEDED' : 'RUNNING' };
    }
    if (action === 'poll' && remoteId) {
      const cleanName = remoteId.replace(/^\/+/, '');
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/${cleanName}`, {
        headers: { 'x-goog-api-key': apiKey },
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data?.error?.message || `HTTP ${r.status}`);
      const state = data.done ? (data.error ? 'FAILED' : 'SUCCEEDED') : 'RUNNING';
      const outputText =
        data.response?.inlinedResponses?.inlinedResponses?.[0]?.response?.candidates?.[0]?.content?.parts
          ?.map((p: { text?: string }) => p.text)
          .join('') || undefined;
      return { remoteId, state, outputText, errorMessage: data.error?.message };
    }
    if (action === 'cancel' && remoteId) {
      const cleanName = remoteId.replace(/^\/+/, '');
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/${cleanName}:cancel`, {
        method: 'POST',
        headers: { 'x-goog-api-key': apiKey },
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return { remoteId, state: 'CANCELLED' };
    }
  }

  throw new Error('Invalid job action or provider.');
}

function mapOpenAiStatus(status: string): string {
  switch (status) {
    case 'queued':
      return 'QUEUED';
    case 'in_progress':
      return 'RUNNING';
    case 'completed':
      return 'SUCCEEDED';
    case 'failed':
      return 'FAILED';
    case 'cancelled':
      return 'CANCELLED';
    case 'incomplete':
      return 'INCOMPLETE';
    default:
      return 'RUNNING';
  }
}

function extractOpenAiText(data: Record<string, unknown>): string | undefined {
  if (typeof data.output_text === 'string' && data.output_text) return data.output_text;
  const output = data.output;
  if (Array.isArray(output)) {
    const texts: string[] = [];
    for (const item of output) {
      if (Array.isArray((item as { content?: unknown[] })?.content)) {
        for (const c of (item as { content: Array<{ text?: string }> }).content) {
          if (typeof c?.text === 'string') texts.push(c.text);
        }
      }
    }
    if (texts.length > 0) return texts.join('\n');
  }
  return undefined;
}
