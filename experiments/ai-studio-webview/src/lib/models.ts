export type WebAiServiceId =
  | 'claude'
  | 'chatgpt'
  | 'gemini'
  | 'deepseek'
  | 'kimi'
  | 'vibe'
  | 'qwen'
  | 'copilot'
  | 'zai'
  | 'grok'
  | 'character-ai'
  | 'venice'
  | 'meta-ai';

export interface WebAiService {
  id: WebAiServiceId;
  displayName: string;
  shortName: string;
  url: string;
  description: string;
  brandColor: string;
  isPrimary: boolean;
  status: 'Full' | 'Partial';
  notes: string;
}

export const WEB_AI_SERVICES: WebAiService[] = [
  {
    id: 'claude',
    displayName: 'Claude.ai',
    shortName: 'Claude',
    url: 'https://claude.ai',
    description: 'Anthropic Claude account chat with Projects & Artifacts',
    brandColor: '#D97706',
    isPrimary: true,
    status: 'Full',
    notes: 'Persistent account session; projects and artifacts',
  },
  {
    id: 'chatgpt',
    displayName: 'ChatGPT',
    shortName: 'ChatGPT',
    url: 'https://chatgpt.com',
    description: 'OpenAI ChatGPT account chat with tools and projects',
    brandColor: '#10B981',
    isPrimary: true,
    status: 'Full',
    notes: 'Persistent session; Codex Cloud shortcut available',
  },
  {
    id: 'gemini',
    displayName: 'Gemini Chat',
    shortName: 'Gemini',
    url: 'https://gemini.google.com',
    description: 'Google Gemini account chat with multimodal and Workspace tools',
    brandColor: '#0EA5E9',
    isPrimary: true,
    status: 'Partial',
    notes: 'Multimodal and Workspace tools via Google account',
  },
  {
    id: 'deepseek',
    displayName: 'DeepSeek Chat',
    shortName: 'DeepSeek',
    url: 'https://chat.deepseek.com',
    description: 'DeepSeek web chat with reasoning, coding, and long context',
    brandColor: '#2563EB',
    isPrimary: true,
    status: 'Full',
    notes: 'DeepSeek reasoning and long-context web chat',
  },
  {
    id: 'kimi',
    displayName: 'Kimi AI',
    shortName: 'Kimi',
    url: 'https://www.kimi.com',
    description: 'Moonshot AI Kimi chat with search, files, agents, and long context',
    brandColor: '#8B5CF6',
    isPrimary: true,
    status: 'Full',
    notes: 'Search, files, agents, and long-context support',
  },
  {
    id: 'vibe',
    displayName: 'Mistral Vibe',
    shortName: 'Vibe',
    url: 'https://chat.mistral.ai',
    description: 'Mistral Vibe account chat, formerly Le Chat, with work and agent tools',
    brandColor: '#FF7000',
    isPrimary: true,
    status: 'Full',
    notes: 'Mistral work and agent tools',
  },
  {
    id: 'qwen',
    displayName: 'Qwen',
    shortName: 'Qwen',
    url: 'https://chat.qwen.ai',
    description: 'Alibaba Qwen account chat with multimodal, search, and agent capabilities',
    brandColor: '#6366F1',
    isPrimary: false,
    status: 'Partial',
    notes: 'Multimodal, search, and agent workflows',
  },
  {
    id: 'copilot',
    displayName: 'Microsoft Copilot',
    shortName: 'Copilot',
    url: 'https://copilot.microsoft.com',
    description: 'Microsoft Copilot account chat with web-grounded assistance and Microsoft services',
    brandColor: '#2563EB',
    isPrimary: false,
    status: 'Partial',
    notes: 'Web-grounded assistance and Microsoft services',
  },
  {
    id: 'zai',
    displayName: 'Z.ai',
    shortName: 'Z.ai',
    url: 'https://chat.z.ai',
    description: 'Z.ai account chat powered by GLM models with chat and agent workflows',
    brandColor: '#7C3AED',
    isPrimary: false,
    status: 'Partial',
    notes: 'Powered by GLM models',
  },
  {
    id: 'grok',
    displayName: 'Grok',
    shortName: 'Grok',
    url: 'https://grok.com',
    description: 'xAI Grok account chat with search, reasoning, files, and multimodal tools',
    brandColor: '#64748B',
    isPrimary: false,
    status: 'Partial',
    notes: 'Search, reasoning, and multimodal tools',
  },
  {
    id: 'character-ai',
    displayName: 'Character.AI',
    shortName: 'Character.AI',
    url: 'https://character.ai',
    description: 'Character.AI account chat for character-driven conversations',
    brandColor: '#7C3AED',
    isPrimary: false,
    status: 'Partial',
    notes: 'Character-driven conversations',
  },
  {
    id: 'venice',
    displayName: 'Venice AI',
    shortName: 'Venice',
    url: 'https://venice.ai',
    description: 'Venice account chat focused on private AI and creative workflows',
    brandColor: '#0F766E',
    isPrimary: false,
    status: 'Partial',
    notes: 'Private AI and creative workflows',
  },
  {
    id: 'meta-ai',
    displayName: 'Meta AI',
    shortName: 'Meta AI',
    url: 'https://www.meta.ai',
    description: 'Meta AI account chat hosted at meta.ai',
    brandColor: '#0668E1',
    isPrimary: false,
    status: 'Partial',
    notes: 'Meta AI web assistant',
  },
];

export function webChatSections(favorites: WebAiServiceId[]) {
  const favSet = new Set(favorites);
  return {
    favorites: WEB_AI_SERVICES.filter((s) => favSet.has(s.id)),
    primary: WEB_AI_SERVICES.filter((s) => s.isPrimary && !favSet.has(s.id)),
    additional: WEB_AI_SERVICES.filter((s) => !s.isPrimary && !favSet.has(s.id)),
  };
}

export type AiProviderId =
  | 'all'
  | 'claude'
  | 'chatgpt'
  | 'gemini'
  | 'deepseek'
  | 'kimi'
  | 'openrouter'
  | 'aihubmix'
  | 'vercel'
  | 'custom';

export interface AiProviderInfo {
  id: AiProviderId;
  displayName: string;
  shortName: string;
  defaultModel: string;
  availableModels: string[];
  description: string;
  participatesInDefaultCompare: boolean;
  usesLiveGatewayCatalog: boolean;
}

export const AI_PROVIDERS: AiProviderInfo[] = [
  {
    id: 'all',
    displayName: 'All Models (Compare)',
    shortName: 'All Models',
    defaultModel: 'all',
    availableModels: ['all'],
    description: 'Send one prompt concurrently to every configured direct provider',
    participatesInDefaultCompare: true,
    usesLiveGatewayCatalog: false,
  },
  {
    id: 'claude',
    displayName: 'Anthropic Claude',
    shortName: 'Claude',
    defaultModel: 'claude-sonnet-4-5',
    availableModels: [
      'claude-sonnet-4-5',
      'claude-opus-4-5',
      'claude-haiku-4-5-20251001',
    ],
    description: 'Anthropic models for coding, reasoning, writing, and agentic workflows',
    participatesInDefaultCompare: true,
    usesLiveGatewayCatalog: false,
  },
  {
    id: 'chatgpt',
    displayName: 'OpenAI',
    shortName: 'OpenAI',
    defaultModel: 'gpt-4o',
    availableModels: ['gpt-4o', 'gpt-4.1', 'o3-mini', 'gpt-4o-mini'],
    description: 'OpenAI GPT models for general text generation, coding, and reasoning',
    participatesInDefaultCompare: true,
    usesLiveGatewayCatalog: false,
  },
  {
    id: 'gemini',
    displayName: 'Google Gemini',
    shortName: 'Gemini',
    defaultModel: 'gemini-2.5-flash',
    availableModels: [
      'gemini-2.5-flash',
      'gemini-2.5-pro',
      'gemini-2.0-flash',
    ],
    description: 'Google multimodal models for coding, agents, reasoning, and long context',
    participatesInDefaultCompare: true,
    usesLiveGatewayCatalog: false,
  },
  {
    id: 'deepseek',
    displayName: 'DeepSeek AI',
    shortName: 'DeepSeek',
    defaultModel: 'deepseek-chat',
    availableModels: ['deepseek-chat', 'deepseek-reasoner'],
    description: 'DeepSeek models with long context, reasoning, coding, and agent capabilities',
    participatesInDefaultCompare: true,
    usesLiveGatewayCatalog: false,
  },
  {
    id: 'kimi',
    displayName: 'Moonshot Kimi AI',
    shortName: 'Kimi',
    defaultModel: 'kimi-k2-0711-preview',
    availableModels: ['kimi-k2-0711-preview', 'moonshot-v1-8k', 'moonshot-v1-32k'],
    description: 'Moonshot AI models for general chat, frontier reasoning, coding, and long context',
    participatesInDefaultCompare: true,
    usesLiveGatewayCatalog: false,
  },
  {
    id: 'openrouter',
    displayName: 'OpenRouter Free',
    shortName: 'OpenRouter',
    defaultModel: 'openrouter/free',
    availableModels: ['openrouter/free'],
    description: 'OpenRouter gateway that routes requests across currently available free models',
    participatesInDefaultCompare: false,
    usesLiveGatewayCatalog: true,
  },
  {
    id: 'aihubmix',
    displayName: 'AIHubMix Free',
    shortName: 'AIHubMix',
    defaultModel: 'hy3-free',
    availableModels: ['hy3-free', 'coding-glm-5.3-free', 'coding-glm-5.2-free'],
    description: 'AIHubMix gateway for subsidized zero-cost model variants',
    participatesInDefaultCompare: false,
    usesLiveGatewayCatalog: true,
  },
  {
    id: 'vercel',
    displayName: 'Vercel AI Gateway',
    shortName: 'Vercel',
    defaultModel: 'alibaba/qwen3-coder-30b-a3b',
    availableModels: ['alibaba/qwen3-coder-30b-a3b'],
    description: 'Vercel AI Gateway with a live text-model catalog and account budget controls',
    participatesInDefaultCompare: false,
    usesLiveGatewayCatalog: true,
  },
  {
    id: 'custom',
    displayName: 'Custom endpoint',
    shortName: 'Custom',
    defaultModel: '',
    availableModels: [],
    description: 'Your own OpenAI-compatible HTTPS endpoint; models come from its live catalog',
    participatesInDefaultCompare: false,
    usesLiveGatewayCatalog: true,
  },
];

export const CONCRETE_COMPARE_PROVIDERS = AI_PROVIDERS.filter(
  (p) => p.id !== 'all' && p.participatesInDefaultCompare,
);

export function getProviderInfo(id: AiProviderId): AiProviderInfo {
  return AI_PROVIDERS.find((p) => p.id === id) ?? AI_PROVIDERS[1];
}

export type ApiProcessingMode = 'auto' | 'standard' | 'flex' | 'fast';

export interface ApiKeyConfig {
  geminiKey: string;
  openAiKey: string;
  claudeKey: string;
  deepseekKey: string;
  kimiKey: string;
  openRouterKey: string;
  aiHubMixKey: string;
  vercelAiGatewayKey: string;
  customEndpointUrl: string;
  customEndpointKey: string;
  customEndpointModel: string;
}

export const EMPTY_API_KEYS: ApiKeyConfig = {
  geminiKey: '',
  openAiKey: '',
  claudeKey: '',
  deepseekKey: '',
  kimiKey: '',
  openRouterKey: '',
  aiHubMixKey: '',
  vercelAiGatewayKey: '',
  customEndpointUrl: '',
  customEndpointKey: '',
  customEndpointModel: '',
};

export function normalizeCustomEndpointBaseUrl(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed || trimmed.length > 512) return null;
  if (!trimmed.toLowerCase().startsWith('https://')) return null;
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.search || parsed.hash) {
      return null;
    }
    let cleanPath = parsed.pathname.replace(/\/+$/, '');
    for (const suffix of ['/chat/completions', '/models']) {
      if (cleanPath.toLowerCase().endsWith(suffix)) {
        cleanPath = cleanPath.slice(0, -suffix.length);
      }
    }
    return `https://${parsed.host.toLowerCase()}${cleanPath}`;
  } catch {
    return null;
  }
}

export function customEndpointTag(baseUrl: string): string {
  let hash = 0xcbf29ce484222325n;
  const prime = 0x100000001b3n;
  const mask = 0xffffffffffffffffn;
  const bytes = new TextEncoder().encode(baseUrl);
  for (const b of bytes) {
    hash = ((hash ^ BigInt(b)) * prime) & mask;
  }
  return hash.toString(16).padStart(16, '0');
}

export function getApiKeyForProvider(keys: ApiKeyConfig, provider: AiProviderId): string {
  switch (provider) {
    case 'gemini':
      return keys.geminiKey.trim();
    case 'chatgpt':
      return keys.openAiKey.trim();
    case 'claude':
      return keys.claudeKey.trim();
    case 'deepseek':
      return keys.deepseekKey.trim();
    case 'kimi':
      return keys.kimiKey.trim();
    case 'openrouter':
      return keys.openRouterKey.trim();
    case 'aihubmix':
      return keys.aiHubMixKey.trim();
    case 'vercel':
      return keys.vercelAiGatewayKey.trim();
    case 'custom':
      return keys.customEndpointKey.trim();
    case 'all':
      return '';
  }
}

export function hasKeyForProvider(keys: ApiKeyConfig, provider: AiProviderId): boolean {
  if (provider === 'custom') {
    return normalizeCustomEndpointBaseUrl(keys.customEndpointUrl) !== null;
  }
  if (provider === 'all') return false;
  return getApiKeyForProvider(keys, provider).length > 0;
}

export function configuredDirectProviders(keys: ApiKeyConfig): AiProviderId[] {
  return CONCRETE_COMPARE_PROVIDERS.filter((p) => hasKeyForProvider(keys, p.id)).map((p) => p.id);
}

export interface ProviderUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
  cachedTokens?: number;
  reasoningTokens?: number;
}

export type CapabilityDecision = 'ALLOW' | 'DENY' | 'ASK' | 'REQUIRES_USER_INTERACTION';

export interface NativeToolReceipt {
  toolName: string;
  decision: CapabilityDecision;
  summary: string;
}

export interface ActiveSkillCard {
  skillName: string;
  title: string;
  summary: string;
  outputText: string;
}

export interface ModelChatMessage {
  id: string;
  sender: 'user' | 'assistant';
  provider?: AiProviderId;
  modelName?: string;
  text: string;
  timestamp: number;
  isError?: boolean;
  isPartial?: boolean;
  isQueued?: boolean;
  latencyMs?: number;
  usage?: ProviderUsage;
  activeProfileNotes?: string[];
  sourceAssetIds?: string[];
  toolReceipts?: NativeToolReceipt[];
  skillCards?: ActiveSkillCard[];
  endpointTag?: string;
  starred?: boolean;
  starNote?: string;
}

export const DEFAULT_PROJECT_ID = 'inbox';

export interface NativeChatConversation {
  id: string;
  title: string;
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
  messages: ModelChatMessage[];
  draft: string;
  selectedProvider: AiProviderId;
  selectedModel: string;
  apiProcessingMode: ApiProcessingMode;
  includeSystemProfile: boolean;
  projectId: string;
  isIncognito?: boolean;
  parentConversationId?: string;
}

export interface LocalProject {
  id: string;
  name: string;
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
}

export type ProjectLibraryAssetKind = 'DOCUMENT' | 'PROMPT' | 'INSTRUCTION' | 'SKILL' | 'ARTIFACT';
export type ProjectLibraryAssetOrigin = 'LOCAL' | 'SHARED' | 'IMPORTED' | 'CHAT_EXPORT' | 'JOB_OUTPUT' | 'TOOL_OUTPUT';

export interface ProjectLibraryAsset {
  id: string;
  projectId: string;
  title: string;
  mediaType: string;
  fileName: string;
  content: string;
  sizeBytes: number;
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
  revision: number;
  contentSha256: string;
  kind: ProjectLibraryAssetKind;
  origin: ProjectLibraryAssetOrigin;
}

export interface ProjectLibraryArchive {
  version: number;
  projects: LocalProject[];
  assets: ProjectLibraryAsset[];
}

export interface WebChatBookmark {
  id: string;
  serviceId: WebAiServiceId;
  title: string;
  url: string;
  folderPath: string; // up to 3 levels e.g. "Work/Code/Reviews"
  createdAtEpochMs: number;
}

export interface WebDraftEntry {
  serviceId: WebAiServiceId;
  text: string;
  updatedAtEpochMs: number;
  restorePoints: Array<{ text: string; savedAtEpochMs: number }>;
}

export type AsyncProviderJobKind = 'BACKGROUND_RESPONSE' | 'BATCH';
export type AsyncProviderJobState =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'INCOMPLETE';

export interface AsyncProviderJob {
  id: string;
  provider: 'chatgpt' | 'claude' | 'gemini';
  kind: AsyncProviderJobKind;
  remoteId: string;
  model: string;
  projectId: string;
  state: AsyncProviderJobState;
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
  resultAssetId?: string;
  outputPreview?: string;
  errorMessage?: string;
}

export type BuiltInBenchToolId =
  | 'docbench-document'
  | 'docbench-text-inspector'
  | 'codebench-qr-barcode'
  | 'streambench-player';

export interface BuiltInBenchCapabilityInfo {
  id: BuiltInBenchToolId;
  displayName: string;
  description: string;
  networkBehavior: 'Local only' | 'Network optional' | 'Network required';
  surfaces: string[];
}

export const BUILT_IN_BENCH_TOOLS: BuiltInBenchCapabilityInfo[] = [
  {
    id: 'docbench-document',
    displayName: 'Docbench document tools',
    description: 'Document preflight diagnostics, Markdown structure repair, JSON/JSON5 formatting, line-ending normalization, text merge, and export.',
    networkBehavior: 'Local only',
    surfaces: ['Native chat', 'Account Web bridge', 'Companion UI'],
  },
  {
    id: 'docbench-text-inspector',
    displayName: 'Docbench Text Inspector',
    description: 'Scans text for invisible Unicode, bidi overrides, variation selectors, mixed-script confusables, and prompt-injection carriers.',
    networkBehavior: 'Local only',
    surfaces: ['Native chat', 'Account Web bridge', 'Companion UI'],
  },
  {
    id: 'codebench-qr-barcode',
    displayName: 'Codebench QR & barcode',
    description: 'Generates local QR SVG/Canvas payloads and decodes QR codes from local images without network uploads.',
    networkBehavior: 'Local only',
    surfaces: ['Native chat', 'Account Web bridge', 'Companion UI'],
  },
  {
    id: 'streambench-player',
    displayName: 'Streambench player',
    description: 'Imports M3U/M3U8 playlists locally in memory, manages favorite/recent station hashes, and plays HTTPS streams.',
    networkBehavior: 'Network optional',
    surfaces: ['Companion UI'],
  },
];

export interface ProviderRuntimeCapabilities {
  transportLabel: string;
  systemPlacementLabel: string;
  historyStrategyLabel: string;
  reasoningLabel: string;
  streamsText: boolean;
  reportsResolvedModel: boolean;
}

export function getProviderRuntimeCapabilities(provider: AiProviderId): ProviderRuntimeCapabilities {
  switch (provider) {
    case 'all':
      return {
        transportLabel: 'Compare fan-out',
        systemPlacementLabel: 'Attached using each provider’s native route',
        historyStrategyLabel: 'Isolated history per provider in compare fan-out',
        reasoningLabel: 'Per-provider model capabilities',
        streamsText: true,
        reportsResolvedModel: false,
      };
    case 'gemini':
      return {
        transportLabel: 'Gemini generateContent',
        systemPlacementLabel: 'Native provider instruction field',
        historyStrategyLabel: 'Bounded provider content replay with opaque model state',
        reasoningLabel: 'Provider default',
        streamsText: true,
        reportsResolvedModel: false,
      };
    case 'chatgpt':
      return {
        transportLabel: 'OpenAI Responses',
        systemPlacementLabel: 'Native provider instruction field',
        historyStrategyLabel: 'Bounded provider content replay with opaque model state',
        reasoningLabel: 'Provider default',
        streamsText: true,
        reportsResolvedModel: false,
      };
    case 'claude':
      return {
        transportLabel: 'Anthropic Messages',
        systemPlacementLabel: 'Native provider instruction field',
        historyStrategyLabel: 'Bounded provider content replay with opaque model state',
        reasoningLabel: 'Anthropic Models API capabilities',
        streamsText: true,
        reportsResolvedModel: false,
      };
    default:
      return {
        transportLabel: 'OpenAI-compatible chat completions',
        systemPlacementLabel: 'OpenAI-compatible system message',
        historyStrategyLabel: 'Bounded provider-scoped text replay',
        reasoningLabel: 'Provider default',
        streamsText: true,
        reportsResolvedModel: true,
      };
  }
}

export type NavigationTab =
  | 'WEB_CHATS'
  | 'COMPARE_HUB'
  | 'STUDIO'
  | 'INSTRUCTIONS'
  | 'YAML'
  | 'PLAYGROUND'
  | 'SKILLS';

export interface McpServerConfig {
  id: string;
  label: string;
  endpoint: string;
  createdAtEpochMs: number;
}

export interface PrivacySettings {
  screenPrivacy: boolean;
  quickPrivacy: boolean;
  appLockEnabled: boolean;
  appLockPasscodeHash: string | null;
}
