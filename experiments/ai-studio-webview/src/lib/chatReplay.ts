import { customEndpointTag, type AiProviderId, type ModelChatMessage } from './models';

export function buildReplayHistory(messages: ModelChatMessage[], provider: AiProviderId, customUrl?: string): Array<{ role: 'user' | 'assistant'; text: string }> {
  const history: Array<{ role: 'user' | 'assistant'; text: string }> = [];
  const endpointTag = customUrl ? customEndpointTag(customUrl) : undefined;
  for (const message of messages) {
    if (message.isError || !message.text.trim()) continue;
    if (message.sender === 'user') {
      history.push({ role: 'user', text: message.replayUserContent ?? message.text });
    } else if (message.provider === provider &&
      (provider !== 'custom' || (endpointTag && message.endpointTag === endpointTag))) {
      history.push({ role: 'assistant', text: message.text });
    }
  }
  return history;
}
