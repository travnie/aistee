import test from 'node:test';
import assert from 'node:assert/strict';
import { formatJsonText } from '../src/lib/docbench';
import { buildReplayHistory } from '../src/lib/chatReplay';
import { customEndpointTag, type ModelChatMessage } from '../src/lib/models';
import { assertPublicHttpsUrl, isPrivateOrReservedIp, readBoundedText } from '../src/lib/publicProxy';

test('JSON formatting preserves numeric lexemes, duplicate keys and escaped strings', () => {
  const result = formatJsonText('{"id":9223372036854775807,"id":1e999,"v":[-0,1.0000,"a\\\"b",{}]}');
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.text, '{\n  "id": 9223372036854775807,\n  "id": 1e999,\n  "v": [\n    -0,\n    1.0000,\n    "a\\\"b",\n    {}\n  ]\n}\n');
  }
  assert.equal(formatJsonText('{"a":}').ok, false);
});

test('replay includes attached user content and only matching provider/endpoint replies', () => {
  const url = 'https://gateway.example.com/v1';
  const messages: ModelChatMessage[] = [
    { id: '1', sender: 'user', text: 'Attachment', replayUserContent: 'Attachment\nOriginal contents', timestamp: 0 },
    { id: '2', sender: 'assistant', provider: 'gemini', text: 'Other provider', timestamp: 1 },
    { id: '3', sender: 'assistant', provider: 'custom', endpointTag: customEndpointTag(url), text: 'Matching', timestamp: 2 },
    { id: '4', sender: 'assistant', provider: 'custom', endpointTag: customEndpointTag('https://other.example.com'), text: 'Other gateway', timestamp: 3 },
    { id: '5', sender: 'assistant', provider: 'custom', text: 'Legacy untagged', timestamp: 4 },
  ];
  assert.deepEqual(buildReplayHistory(messages, 'custom', url), [
    { role: 'user', text: 'Attachment\nOriginal contents' }, { role: 'assistant', text: 'Matching' },
  ]);
  assert.deepEqual(buildReplayHistory(messages, 'gemini'), [
    { role: 'user', text: 'Attachment\nOriginal contents' }, { role: 'assistant', text: 'Other provider' },
  ]);
});

test('public proxy rejects private, mapped, reserved and local addresses', async () => {
  for (const ip of ['127.0.0.1','10.0.0.1','100.64.0.1','169.254.169.254','192.168.1.1','192.0.2.1','::1','::ffff:127.0.0.1','fe80::1','fc00::1','2001:db8::1','64:ff9b::1']) {
    assert.equal(isPrivateOrReservedIp(ip), true, ip);
    await assert.rejects(assertPublicHttpsUrl('https://' + (ip.includes(':') ? '[' + ip + ']' : ip)));
  }
  assert.equal(isPrivateOrReservedIp('8.8.8.8'), false);
  assert.equal(isPrivateOrReservedIp('2606:4700:4700::1111'), false);
  await assert.rejects(assertPublicHttpsUrl('https://localhost'));
  await assert.rejects(assertPublicHttpsUrl('http://8.8.8.8'));
});

test('response byte cap cancels streaming before the remaining body is read', async () => {
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) { controller.enqueue(new Uint8Array(8)); },
    cancel() { cancelled = true; },
  });
  await assert.rejects(readBoundedText(new Response(stream), 12), /RESPONSE_TOO_LARGE/);
  assert.equal(cancelled, true);
  assert.equal(await readBoundedText(new Response('abc'), 3), 'abc');
});
