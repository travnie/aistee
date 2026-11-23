import express from 'express';
import { createServer as createViteServer } from 'vite';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import dns from 'node:dns/promises';
import net from 'node:net';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = 3000;
const HOST = '0.0.0.0';

function isPrivateOrReservedIp(ip: string): boolean {
  if (net.isIPv4(ip)) {
    const parts = ip.split('.').map(Number);
    const [a, b] = parts;
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 169 && b === 254) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
    if (a >= 224) return true;
    return false;
  }
  if (net.isIPv6(ip)) {
    const norm = ip.toLowerCase();
    if (norm === '::' || norm === '::1') return true;
    if (norm.startsWith('fc') || norm.startsWith('fd') || norm.startsWith('fe80')) return true;
    if (norm.startsWith('::ffff:')) {
      return isPrivateOrReservedIp(norm.slice(7));
    }
    return false;
  }
  return true;
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

async function assertPublicHttpsUrl(rawUrl: string): Promise<URL> {
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
    BLOCKED_HOST_SUFFIXES.some((s) => hostname.endsWith(s))
  ) {
    throw new Error('Local or reserved hostnames are blocked.');
  }
  if (net.isIP(hostname)) {
    if (isPrivateOrReservedIp(hostname)) {
      throw new Error('Private or reserved IP addresses are blocked.');
    }
    return parsed;
  }
  try {
    const records = await dns.lookup(hostname, { all: true });
    if (records.length === 0) {
      throw new Error('DNS resolution returned no addresses.');
    }
    for (const rec of records) {
      if (isPrivateOrReservedIp(rec.address)) {
        throw new Error('Endpoint resolves to a private or reserved network address.');
      }
    }
  } catch (err) {
    if (err instanceof Error && err.message.includes('blocked') || (err instanceof Error && err.message.includes('private'))) {
      throw err;
    }
    throw new Error('Unable to verify public DNS resolution for endpoint.');
  }
  return parsed;
}

function writeSseEvent(res: express.Response, payload: unknown) {
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '4mb' }));

  // Health endpoint
  app.get('/api/health', (_req, res) => {
    res.json({ ok: true });
  });

  // Streaming native/API chat proxy (stateless, client provides key in request body)
  app.post('/api/chat/stream', async (req, res) => {
    const {
      provider,
      model,
      apiKey,
      customUrl,
      systemPrompt,
      messages,
      apiProcessingMode,
    } = req.body as {
      provider: string;
      model: string;
      apiKey?: string;
      customUrl?: string;
      systemPrompt?: string;
      messages: Array<{ role: 'user' | 'assistant'; text: string }>;
      apiProcessingMode?: string;
    };

    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');

    const abortController = new AbortController();
    req.on('close', () => abortController.abort());

    try {
      if (!Array.isArray(messages) || messages.length === 0) {
        writeSseEvent(res, { type: 'error', message: 'No conversation messages supplied.' });
        return res.end();
      }

      if (provider === 'gemini') {
        if (!apiKey) {
          writeSseEvent(res, { type: 'error', message: 'Gemini API key is required.' });
          return res.end();
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
          signal: abortController.signal,
        });
        if (!upstream.ok || !upstream.body) {
          const errText = await upstream.text().catch(() => '');
          writeSseEvent(res, {
            type: 'error',
            message: `Gemini HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`,
          });
          return res.end();
        }
        const reader = upstream.body.getReader();
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
            if (!jsonStr || jsonStr === '[DONE]') continue;
            try {
              const parsed = JSON.parse(jsonStr);
              const parts = parsed?.candidates?.[0]?.content?.parts;
              if (Array.isArray(parts)) {
                for (const part of parts) {
                  if (typeof part?.text === 'string' && part.text.length > 0 && !part.thought) {
                    writeSseEvent(res, { type: 'delta', text: part.text });
                  }
                }
              }
              if (parsed?.usageMetadata) {
                const um = parsed.usageMetadata;
                writeSseEvent(res, {
                  type: 'usage',
                  usage: {
                    inputTokens: um.promptTokenCount ?? undefined,
                    outputTokens: um.candidatesTokenCount ?? undefined,
                    totalTokens: um.totalTokenCount ?? undefined,
                    cachedTokens: um.cachedContentTokenCount ?? undefined,
                    reasoningTokens: um.thoughtsTokenCount ?? undefined,
                  },
                });
              }
            } catch {
              // ignore partial chunk
            }
          }
        }
        writeSseEvent(res, { type: 'done' });
        return res.end();
      }

      if (provider === 'claude') {
        if (!apiKey) {
          writeSseEvent(res, { type: 'error', message: 'Anthropic Claude API key is required.' });
          return res.end();
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
          },
          body: JSON.stringify(body),
          signal: abortController.signal,
        });
        if (!upstream.ok || !upstream.body) {
          const errText = await upstream.text().catch(() => '');
          writeSseEvent(res, {
            type: 'error',
            message: `Claude HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`,
          });
          return res.end();
        }
        const reader = upstream.body.getReader();
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
              const evt = JSON.parse(jsonStr);
              if (evt.type === 'content_block_delta' && evt.delta?.type === 'text_delta' && typeof evt.delta?.text === 'string') {
                writeSseEvent(res, { type: 'delta', text: evt.delta.text });
              } else if (evt.type === 'message_start' && evt.message?.model) {
                writeSseEvent(res, {
                  type: 'usage',
                  resolvedModel: evt.message.model,
                  usage: evt.message.usage
                    ? {
                        inputTokens: evt.message.usage.input_tokens,
                        cachedTokens: evt.message.usage.cache_read_input_tokens,
                      }
                    : undefined,
                });
              } else if (evt.type === 'message_delta' && evt.usage) {
                writeSseEvent(res, {
                  type: 'usage',
                  usage: {
                    outputTokens: evt.usage.output_tokens,
                  },
                });
              } else if (evt.type === 'error') {
                writeSseEvent(res, { type: 'error', message: evt.error?.message || 'Anthropic stream error' });
              }
            } catch {
              // skip malformed event
            }
          }
        }
        writeSseEvent(res, { type: 'done' });
        return res.end();
      }

      if (provider === 'chatgpt') {
        if (!apiKey) {
          writeSseEvent(res, { type: 'error', message: 'OpenAI API key is required.' });
          return res.end();
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
          signal: abortController.signal,
        });
        if (!upstream.ok || !upstream.body) {
          const errText = await upstream.text().catch(() => '');
          writeSseEvent(res, {
            type: 'error',
            message: `OpenAI HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`,
          });
          return res.end();
        }
        const reader = upstream.body.getReader();
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
            if (!jsonStr || jsonStr === '[DONE]') continue;
            try {
              const evt = JSON.parse(jsonStr);
              if (evt.type === 'response.output_text.delta' && typeof evt.delta === 'string') {
                writeSseEvent(res, { type: 'delta', text: evt.delta });
              } else if (evt.type === 'response.completed' && evt.response) {
                const u = evt.response.usage;
                writeSseEvent(res, {
                  type: 'usage',
                  resolvedModel: evt.response.model,
                  usage: u
                    ? {
                        inputTokens: u.input_tokens,
                        outputTokens: u.output_tokens,
                        totalTokens: u.total_tokens,
                        cachedTokens: u.input_tokens_details?.cached_tokens,
                        reasoningTokens: u.output_tokens_details?.reasoning_tokens,
                      }
                    : undefined,
                });
              } else if (evt.type === 'response.failed') {
                writeSseEvent(res, { type: 'error', message: evt.response?.error?.message || 'OpenAI response failed' });
              }
            } catch {
              // ignore partial
            }
          }
        }
        writeSseEvent(res, { type: 'done' });
        return res.end();
      }

      // OpenAI-compatible gateways: deepseek, kimi, openrouter, aihubmix, vercel, custom
      let targetUrl = '';
      if (provider === 'deepseek') targetUrl = 'https://api.deepseek.com/chat/completions';
      else if (provider === 'kimi') targetUrl = 'https://api.moonshot.ai/v1/chat/completions';
      else if (provider === 'openrouter') targetUrl = 'https://openrouter.ai/api/v1/chat/completions';
      else if (provider === 'aihubmix') targetUrl = 'https://aihubmix.com/v1/chat/completions';
      else if (provider === 'vercel') targetUrl = 'https://ai-gateway.vercel.sh/v1/chat/completions';
      else if (provider === 'custom') {
        if (!customUrl) {
          writeSseEvent(res, { type: 'error', message: 'Custom HTTPS endpoint URL is required.' });
          return res.end();
        }
        const verified = await assertPublicHttpsUrl(customUrl);
        const cleanBase = verified.toString().replace(/\/+$/, '');
        targetUrl = `${cleanBase}/chat/completions`;
      } else {
        writeSseEvent(res, { type: 'error', message: `Unsupported provider: ${provider}` });
        return res.end();
      }

      if (provider !== 'custom' && !apiKey) {
        writeSseEvent(res, { type: 'error', message: `API key for ${provider} is required.` });
        return res.end();
      }

      const compatMessages: Array<{ role: string; content: string }> = [];
      if (systemPrompt?.trim()) {
        compatMessages.push({ role: 'system', content: systemPrompt });
      }
      for (const m of messages) {
        compatMessages.push({ role: m.role, content: m.text });
      }

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      };
      if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

      const upstream = await fetch(targetUrl, {
        method: 'POST',
        headers,
        redirect: 'manual',
        body: JSON.stringify({
          model,
          messages: compatMessages,
          stream: true,
        }),
        signal: abortController.signal,
      });

      if (!upstream.ok || !upstream.body) {
        const errText = await upstream.text().catch(() => '');
        writeSseEvent(res, {
          type: 'error',
          message: `Provider HTTP ${upstream.status}${errText ? `: ${errText.slice(0, 300)}` : ''}`,
        });
        return res.end();
      }

      const reader = upstream.body.getReader();
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
          if (!jsonStr || jsonStr === '[DONE]') continue;
          try {
            const evt = JSON.parse(jsonStr);
            const delta = evt?.choices?.[0]?.delta?.content;
            if (typeof delta === 'string' && delta.length > 0) {
              writeSseEvent(res, { type: 'delta', text: delta });
            }
            if (evt?.model || evt?.usage) {
              writeSseEvent(res, {
                type: 'usage',
                resolvedModel: evt.model,
                usage: evt.usage
                  ? {
                      inputTokens: evt.usage.prompt_tokens ?? evt.usage.input_tokens,
                      outputTokens: evt.usage.completion_tokens ?? evt.usage.output_tokens,
                      totalTokens: evt.usage.total_tokens,
                      cachedTokens:
                        evt.usage.prompt_tokens_details?.cached_tokens ??
                        evt.usage.prompt_cache_hit_tokens,
                    }
                  : undefined,
              });
            }
          } catch {
            // skip partial
          }
        }
      }
      writeSseEvent(res, { type: 'done' });
      return res.end();
    } catch (err) {
      if ((err as Error)?.name !== 'AbortError') {
        writeSseEvent(res, {
          type: 'error',
          message: err instanceof Error ? err.message : 'Upstream connection failed.',
        });
      }
      return res.end();
    }
  });

  // Live gateway model catalog discovery
  app.post('/api/gateway/models', async (req, res) => {
    try {
      const { provider, apiKey, customUrl } = req.body as {
        provider: string;
        apiKey?: string;
        customUrl?: string;
      };
      let url = '';
      if (provider === 'openrouter') url = 'https://openrouter.ai/api/v1/models';
      else if (provider === 'aihubmix') url = 'https://aihubmix.com/v1/models';
      else if (provider === 'vercel') url = 'https://ai-gateway.vercel.sh/v1/models';
      else if (provider === 'custom') {
        if (!customUrl) return res.status(400).json({ error: 'Custom URL required.' });
        const verified = await assertPublicHttpsUrl(customUrl);
        url = `${verified.toString().replace(/\/+$/, '')}/models`;
      } else {
        return res.status(400).json({ error: 'Unsupported gateway provider.' });
      }
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
      const upstream = await fetch(url, { headers, redirect: 'manual' });
      if (!upstream.ok) {
        return res.status(upstream.status).json({ error: `HTTP ${upstream.status}` });
      }
      const json = (await upstream.json()) as { data?: Array<Record<string, unknown>> };
      const entries = Array.isArray(json?.data)
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
      return res.json({ models: entries });
    } catch (err) {
      return res.status(400).json({ error: err instanceof Error ? err.message : 'Catalog fetch failed.' });
    }
  });

  // MCP 2026-07-28 unauthenticated tools/list discovery
  app.post('/api/mcp/discover', async (req, res) => {
    try {
      const { endpoint } = req.body as { endpoint: string };
      const url = await assertPublicHttpsUrl(endpoint);
      const upstream = await fetch(url.toString(), {
        method: 'POST',
        redirect: 'manual',
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
        return res.json({
          status: 'authorization_required',
          challenge: {
            resourceMetadataUrl: resourceMatch?.[1] || null,
            scopes: scopeMatch?.[1]?.split(/\s+/).filter(Boolean) || [],
          },
        });
      }

      if (!upstream.ok) {
        return res.json({
          status: 'failure',
          reason: `HTTP_${upstream.status}`,
        });
      }

      const raw = await upstream.text();
      if (raw.length > 256_000) {
        return res.json({ status: 'failure', reason: 'RESPONSE_TOO_LARGE' });
      }
      const parsed = JSON.parse(raw);
      const tools = Array.isArray(parsed?.result?.tools) ? parsed.result.tools : null;
      if (!tools) {
        return res.json({ status: 'failure', reason: 'INVALID_RESPONSE' });
      }
      return res.json({
        status: 'success',
        endpoint: url.toString(),
        tools: tools.slice(0, 128).map((t: Record<string, unknown>) => ({
          name: String(t.name ?? ''),
          title: typeof t.title === 'string' ? t.title : undefined,
          description: typeof t.description === 'string' ? t.description : '',
          inputSchema: t.inputSchema ?? { type: 'object', properties: {} },
        })),
      });
    } catch (err) {
      return res.json({
        status: 'failure',
        reason: err instanceof Error ? err.message : 'NETWORK_FAILURE',
      });
    }
  });

  // Read-only RFC 9728 / OAuth / OIDC authorization metadata inspector
  app.post('/api/mcp/auth-inspect', async (req, res) => {
    try {
      const { url } = req.body as { url: string };
      const parsed = await assertPublicHttpsUrl(url);
      const upstream = await fetch(parsed.toString(), {
        method: 'GET',
        redirect: 'manual',
        headers: { Accept: 'application/json' },
      });
      if (!upstream.ok) {
        return res.status(upstream.status).json({ error: `HTTP ${upstream.status}` });
      }
      const text = await upstream.text();
      if (text.length > 128_000) {
        return res.status(413).json({ error: 'Metadata document too large.' });
      }
      return res.json({ metadata: JSON.parse(text) });
    } catch (err) {
      return res.status(400).json({ error: err instanceof Error ? err.message : 'Inspection failed.' });
    }
  });

  // One-shot approved manual MCP tool call
  app.post('/api/mcp/call', async (req, res) => {
    try {
      const { endpoint, toolName, argumentsObj } = req.body as {
        endpoint: string;
        toolName: string;
        argumentsObj: Record<string, unknown>;
      };
      const url = await assertPublicHttpsUrl(endpoint);
      const upstream = await fetch(url.toString(), {
        method: 'POST',
        redirect: 'manual',
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
        return res.json({ ok: false, error: 'Tool response exceeded 256 KB bound.' });
      }
      if (!upstream.ok) {
        return res.json({ ok: false, error: `HTTP ${upstream.status}` });
      }
      return res.json({ ok: true, response: JSON.parse(text) });
    } catch (err) {
      return res.json({ ok: false, error: err instanceof Error ? err.message : 'Call failed.' });
    }
  });

  // Async Background / Batch Jobs proxy
  app.post('/api/jobs/action', async (req, res) => {
    try {
      const { action, provider, apiKey, model, prompt, remoteId } = req.body as {
        action: 'start' | 'poll' | 'cancel';
        provider: 'chatgpt' | 'claude' | 'gemini';
        apiKey: string;
        model?: string;
        prompt?: string;
        remoteId?: string;
      };
      if (!apiKey) return res.status(400).json({ error: 'API key required.' });

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
          if (!r.ok) return res.status(r.status).json({ error: data?.error?.message || `HTTP ${r.status}` });
          return res.json({ remoteId: data.id, state: mapOpenAiStatus(data.status), outputText: extractOpenAiText(data) });
        }
        if (action === 'poll' && remoteId) {
          const r = await fetch(`https://api.openai.com/v1/responses/${encodeURIComponent(remoteId)}`, {
            headers: { Authorization: `Bearer ${apiKey}` },
          });
          const data = await r.json();
          if (!r.ok) return res.status(r.status).json({ error: data?.error?.message || `HTTP ${r.status}` });
          return res.json({ remoteId: data.id, state: mapOpenAiStatus(data.status), outputText: extractOpenAiText(data) });
        }
        if (action === 'cancel' && remoteId) {
          const r = await fetch(`https://api.openai.com/v1/responses/${encodeURIComponent(remoteId)}/cancel`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${apiKey}` },
          });
          const data = await r.json();
          if (!r.ok) return res.status(r.status).json({ error: data?.error?.message || `HTTP ${r.status}` });
          return res.json({ remoteId: data.id, state: 'CANCELLED' });
        }
      }

      if (provider === 'claude') {
        if (action === 'start') {
          const r = await fetch('https://api.anthropic.com/v1/messages/batches', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'x-api-key': apiKey,
              'anthropic-version': '2023-06-01',
            },
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
          if (!r.ok) return res.status(r.status).json({ error: data?.error?.message || `HTTP ${r.status}` });
          return res.json({
            remoteId: data.id,
            state: data.processing_status === 'ended' ? 'SUCCEEDED' : 'RUNNING',
          });
        }
        if (action === 'poll' && remoteId) {
          const r = await fetch(`https://api.anthropic.com/v1/messages/batches/${encodeURIComponent(remoteId)}`, {
            headers: {
              'x-api-key': apiKey,
              'anthropic-version': '2023-06-01',
            },
          });
          const data = await r.json();
          if (!r.ok) return res.status(r.status).json({ error: data?.error?.message || `HTTP ${r.status}` });
          let outputText: string | undefined;
          if (data.processing_status === 'ended' && data.results_url) {
            const rr = await fetch(data.results_url, {
              headers: { 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' },
            });
            if (rr.ok) {
              const jsonl = await rr.text();
              const firstLine = jsonl.split('\n').find((l) => l.trim());
              if (firstLine) {
                const parsed = JSON.parse(firstLine);
                outputText = parsed?.result?.message?.content?.map((c: { text?: string }) => c.text).join('') || undefined;
              }
            }
          }
          return res.json({
            remoteId: data.id,
            state: data.processing_status === 'ended' ? (outputText ? 'SUCCEEDED' : 'FAILED') : 'RUNNING',
            outputText,
          });
        }
        if (action === 'cancel' && remoteId) {
          const r = await fetch(`https://api.anthropic.com/v1/messages/batches/${encodeURIComponent(remoteId)}/cancel`, {
            method: 'POST',
            headers: {
              'x-api-key': apiKey,
              'anthropic-version': '2023-06-01',
            },
          });
          if (!r.ok) return res.status(r.status).json({ error: `HTTP ${r.status}` });
          return res.json({ remoteId, state: 'CANCELLED' });
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
          if (!r.ok) return res.status(r.status).json({ error: data?.error?.message || `HTTP ${r.status}` });
          return res.json({ remoteId: data.name, state: data.done ? 'SUCCEEDED' : 'RUNNING' });
        }
        if (action === 'poll' && remoteId) {
          const cleanName = remoteId.replace(/^\/+/, '');
          const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/${cleanName}`, {
            headers: { 'x-goog-api-key': apiKey },
          });
          const data = await r.json();
          if (!r.ok) return res.status(r.status).json({ error: data?.error?.message || `HTTP ${r.status}` });
          const state = data.done ? (data.error ? 'FAILED' : 'SUCCEEDED') : 'RUNNING';
          const outputText =
            data.response?.inlinedResponses?.inlinedResponses?.[0]?.response?.candidates?.[0]?.content?.parts
              ?.map((p: { text?: string }) => p.text)
              .join('') || undefined;
          return res.json({ remoteId, state, outputText, errorMessage: data.error?.message });
        }
        if (action === 'cancel' && remoteId) {
          const cleanName = remoteId.replace(/^\/+/, '');
          const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/${cleanName}:cancel`, {
            method: 'POST',
            headers: { 'x-goog-api-key': apiKey },
          });
          if (!r.ok) return res.status(r.status).json({ error: `HTTP ${r.status}` });
          return res.json({ remoteId, state: 'CANCELLED' });
        }
      }

      return res.status(400).json({ error: 'Invalid job action or provider.' });
    } catch (err) {
      return res.status(500).json({ error: err instanceof Error ? err.message : 'Job action failed.' });
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(__dirname, 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, HOST, () => {
    console.log(`Aistee server listening on http://${HOST}:${PORT}`);
  });
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
      if (Array.isArray(item?.content)) {
        for (const c of item.content) {
          if (typeof c?.text === 'string') texts.push(c.text);
        }
      }
    }
    if (texts.length > 0) return texts.join('\n');
  }
  return undefined;
}

startServer();
