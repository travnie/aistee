import { customEndpointTag, type AiProviderId, type ModelChatMessage } from './models';

export function attachmentReplayScope(provider: AiProviderId, customUrl?: string): string | undefined {
  return provider === 'custom' ? (customUrl ? 'custom:' + customEndpointTag(customUrl) : undefined) : provider;
}

export function buildReplayHistory(messages: ModelChatMessage[], provider: AiProviderId, customUrl?: string): Array<{ role: 'user' | 'assistant'; text: string }> {
  const history: Array<{ role: 'user' | 'assistant'; text: string }> = [];
  const endpointTag = customUrl ? customEndpointTag(customUrl) : undefined;
  const scope = attachmentReplayScope(provider, customUrl);
  for (const message of messages) {
    if (message.isError || !message.text.trim()) continue;
    if (message.sender === 'user') {
      const canReplayAttachment = scope && message.attachmentReplayScopes?.includes(scope);
      history.push({ role: 'user', text: canReplayAttachment ? message.replayUserContent ?? message.text : message.text });
    } else if (message.provider === provider &&
      (provider !== 'custom' || (endpointTag && message.endpointTag === endpointTag))) {
      history.push({ role: 'assistant', text: message.text });
    }
  }
  return history;
}
