import dns from 'node:dns/promises';
import net from 'node:net';
import { Agent } from 'undici';
import ipaddr from 'ipaddr.js';

export function isPrivateOrReservedIp(ip: string): boolean {
  try {
    const address = ipaddr.process(ip);
    if (address.range() !== 'unicast') return true;
    // Public IPv6 global unicast only; reject transition/local ranges.
    if (address.kind() === 'ipv6' && !address.match(ipaddr.parseCIDR('2000::/3') as never)) return true;
    return false;
  } catch {
    return true;
  }
}

// Validate the actual socket lookup, not just a preflight DNS answer.
const publicDispatcher = new Agent({
  connect: {
    timeout: 10_000,
    lookup(hostname, options, callback) {
      dns.lookup(hostname, { all: true }).then((records) => {
        if (!records.length || records.some((record) => isPrivateOrReservedIp(record.address))) {
          callback(new Error('Endpoint resolves to a private or reserved network address.'), '', 0);
          return;
        }
        const family = typeof options === 'number' ? options : options.family;
        const candidates = records.filter((record) => !family || record.family === family);
        if (!candidates.length) {
          callback(new Error('No public address for requested family.'), '', 0);
          return;
        }
        if (typeof options !== 'number' && options.all) callback(null, candidates);
        else callback(null, candidates[0].address, candidates[0].family);
      }, (error: Error) => callback(error, '', 0));
    },
  },
  headersTimeout: 30_000,
  bodyTimeout: 30_000,
  connections: 4,
});

export async function publicFetch(rawUrl: string, init: RequestInit = {}): Promise<Response> {
  const parsed = await assertPublicHttpsUrl(rawUrl);
  const timeout = AbortSignal.timeout(60_000);
  const signal = init.signal ? AbortSignal.any([init.signal, timeout]) : timeout;
  const options: RequestInit & { dispatcher: Agent } = {
    ...init, signal, redirect: 'manual', dispatcher: publicDispatcher,
  };
  return fetch(parsed, options);
}

export async function readBoundedText(response: Response, maxBytes: number): Promise<string> {
  if (!response.body) return '';
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let size = 0;
  let text = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return text + decoder.decode();
      size += value.byteLength;
      if (size > maxBytes) throw new Error('RESPONSE_TOO_LARGE');
      text += decoder.decode(value, { stream: true });
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
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

export async function assertPublicHttpsUrl(rawUrl: string): Promise<URL> {
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
  const hostname = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
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
