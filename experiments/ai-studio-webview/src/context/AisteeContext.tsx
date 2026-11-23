import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import {
  AI_PROVIDERS,
  AiProviderId,
  ApiKeyConfig,
  ApiProcessingMode,
  AsyncProviderJob,
  BuiltInBenchToolId,
  configuredDirectProviders,
  customEndpointTag,
  DEFAULT_PROJECT_ID,
  getApiKeyForProvider,
  getProviderInfo,
  hasKeyForProvider,
  LocalProject,
  McpServerConfig,
  ModelChatMessage,
  NativeChatConversation,
  NavigationTab,
  normalizeCustomEndpointBaseUrl,
  PrivacySettings,
  ProjectLibraryArchive,
  ProjectLibraryAsset,
  ProjectLibraryAssetKind,
  ProjectLibraryAssetOrigin,
  ProviderUsage,
  WebAiServiceId,
  WebChatBookmark,
  WebDraftEntry,
} from '../lib/models';
import {
  BUILT_IN_OVERLAYS,
  DEFAULT_BASE_PROFILE,
  mergeProfileWithOverlay,
  Profile,
  ProfileOverlay,
  renderInstructions,
} from '../lib/profile';
import { LocalSkillBundle, parseSkillManifest, sha256Hex } from '../lib/skills';
import { fetchGatewayCatalogDirect, streamProviderChatDirect } from '../lib/directFetch';
import {
  deleteAllAisteeLocalData,
  loadActiveSkillsEnabled,
  loadApiKeys,
  loadAsyncJobs,
  loadEnabledBenches,
  loadLocalSkills,
  loadMcpServers,
  loadNativeChats,
  loadPrivacySettings,
  loadProjectLibrary,
  loadStreambenchPrefs,
  loadStudioSnapshot,
  loadThemeMode,
  loadWebBookmarks,
  loadWebDrafts,
  loadWebPrefs,
  saveActiveSkillsEnabled,
  saveApiKeys,
  saveAsyncJobs,
  saveEnabledBenches,
  saveLocalSkills,
  saveMcpServers,
  saveNativeChats,
  savePrivacySettings,
  saveProjectLibrary,
  saveStreambenchPrefs,
  saveStudioSnapshot,
  saveThemeMode,
  saveWebBookmarks,
  saveWebDrafts,
  saveWebPrefs,
} from '../lib/storage';

export type StudioToolsSubTab = 'SKILLS' | 'BENCHES' | 'AGENTS' | 'MCP' | 'JOBS' | 'PRIVACY';

export interface GatewayModelEntry {
  id: string;
  inputPriceUsd: number | null;
  outputPriceUsd: number | null;
  supportsTextOutput: boolean;
}

function createId(prefix: string): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return `${prefix}_${crypto.randomUUID().slice(0, 12)}`;
  }
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

function createDefaultConversation(
  overrides: Partial<NativeChatConversation> = {},
): NativeChatConversation {
  const now = Date.now();
  return {
    id: createId('chat'),
    title: overrides.isIncognito ? 'Incognito chat' : 'New chat',
    createdAtEpochMs: now,
    updatedAtEpochMs: now,
    messages: [],
    draft: '',
    selectedProvider: 'claude',
    selectedModel: getProviderInfo('claude').defaultModel,
    apiProcessingMode: 'auto',
    includeSystemProfile: true,
    projectId: DEFAULT_PROJECT_ID,
    ...overrides,
  };
}

function autoTitleFromPrompt(prompt: string): string {
  const cleaned = prompt.trim().replace(/\s+/g, ' ');
  if (!cleaned) return 'New chat';
  return cleaned.length > 48 ? `${cleaned.slice(0, 48)}…` : cleaned;
}

interface AisteeContextValue {
  // Navigation & Theme
  theme: 'dark' | 'light';
  toggleTheme: () => void;
  activeTab: NavigationTab;
  setActiveTab: (tab: NavigationTab) => void;
  toolsSubTab: StudioToolsSubTab;
  setToolsSubTab: (sub: StudioToolsSubTab) => void;

  // Toast feedback
  toastMessage: string | null;
  showToast: (msg: string) => void;

  // API Keys modal
  isApiKeysOpen: boolean;
  setIsApiKeysOpen: (open: boolean) => void;
  apiKeys: ApiKeyConfig;
  updateApiKeys: (keys: ApiKeyConfig) => void;

  // Studio Profile & Overlays
  baseProfile: Profile;
  setBaseProfile: React.Dispatch<React.SetStateAction<Profile>>;
  selectedOverlayId: string | null;
  setSelectedOverlayId: (id: string | null) => void;
  customOverlays: ProfileOverlay[];
  saveCustomOverlay: (overlay: ProfileOverlay) => void;
  deleteCustomOverlay: (id: string) => void;
  allOverlays: ProfileOverlay[];
  activeOverlay: ProfileOverlay | null;
  composedProfile: Profile;
  language: 'auto' | 'en' | 'pl';
  setLanguage: (lang: 'auto' | 'en' | 'pl') => void;
  renderedInstructions: string;
  resetStudioProfile: () => void;

  // Markdown & Docbench handoff state
  workspaceText: string;
  setWorkspaceText: (text: string) => void;
  workspaceTitle: string;
  setWorkspaceTitle: (title: string) => void;
  workspaceLinkedAssetId: string | null;
  openInMarkdownWorkspace: (title: string, text: string, linkedAssetId?: string | null) => void;

  // Native & Compare Chat
  conversations: NativeChatConversation[];
  activeConversation: NativeChatConversation;
  setActiveConversationId: (id: string) => void;
  createConversation: (opts?: { incognito?: boolean; projectId?: string }) => void;
  deleteConversation: (id: string) => void;
  renameConversation: (id: string, title: string) => void;
  assignConversationProject: (id: string, projectId: string) => void;
  updateConversationSettings: (
    id: string,
    patch: Partial<
      Pick<
        NativeChatConversation,
        'selectedProvider' | 'selectedModel' | 'apiProcessingMode' | 'includeSystemProfile' | 'draft'
      >
    >,
  ) => void;
  toggleMessageStar: (conversationId: string, messageId: string) => void;
  updateMessageStarNote: (conversationId: string, messageId: string, note: string) => void;
  branchConversationFromMessage: (conversationId: string, messageId: string) => void;
  stagedAssetIds: string[];
  toggleStagedAsset: (assetId: string) => void;
  clearStagedAssets: () => void;
  streamingConversationIds: Record<string, boolean>;
  sendChatPrompt: (conversationId?: string) => Promise<void>;
  stopStreaming: (conversationId: string) => void;

  // Gateway live model catalogs
  gatewayCatalogs: Record<string, GatewayModelEntry[]>;
  gatewayCatalogLoading: Record<string, boolean>;
  refreshGatewayCatalog: (provider: AiProviderId) => Promise<void>;

  // Project Library v2
  projectLibrary: ProjectLibraryArchive;
  createProject: (name: string) => LocalProject | null;
  renameProject: (id: string, name: string) => void;
  deleteProject: (id: string) => void;
  upsertProjectAsset: (input: {
    id?: string;
    projectId: string;
    title: string;
    content: string;
    fileName?: string;
    mediaType?: string;
    kind: ProjectLibraryAssetKind;
    origin: ProjectLibraryAssetOrigin;
  }) => Promise<ProjectLibraryAsset>;
  deleteProjectAsset: (assetId: string) => void;
  importProjectLibraryJson: (raw: string) => { ok: boolean; message: string };

  // Web AI state
  selectedWebService: WebAiServiceId;
  setSelectedWebService: (id: WebAiServiceId) => void;
  favoriteWebServices: WebAiServiceId[];
  toggleFavoriteWebService: (id: WebAiServiceId) => void;
  comfortableWidthIds: WebAiServiceId[];
  toggleComfortableWidth: (id: WebAiServiceId) => void;
  webDrafts: Record<string, WebDraftEntry>;
  updateWebDraft: (serviceId: WebAiServiceId, text: string) => void;
  saveWebDraftRestorePoint: (serviceId: WebAiServiceId) => void;
  clearWebDraft: (serviceId: WebAiServiceId) => void;
  webBookmarks: WebChatBookmark[];
  addWebBookmark: (bm: Omit<WebChatBookmark, 'id' | 'createdAtEpochMs'>) => void;
  deleteWebBookmark: (id: string) => void;
  importWebBookmarksJson: (raw: string) => { ok: boolean; count: number; error?: string };

  // Local Skills & Active Skills
  localSkills: LocalSkillBundle[];
  activeSkillsEnabled: boolean;
  setActiveSkillsEnabled: (enabled: boolean) => void;
  upsertLocalSkill: (source: string, scriptSource?: string) => Promise<{ ok: boolean; error?: string }>;
  deleteLocalSkill: (name: string) => void;
  trustLocalSkill: (name: string, origins?: string[]) => void;
  revokeLocalSkillTrust: (name: string) => void;

  // Benches & Streambench
  enabledBenches: BuiltInBenchToolId[];
  toggleBenchEnabled: (id: BuiltInBenchToolId) => void;
  streambenchFavorites: string[];
  streambenchRecents: string[];
  toggleStreambenchFavorite: (hash: string) => void;
  recordStreambenchPlay: (hash: string) => void;

  // MCP Servers
  mcpServers: McpServerConfig[];
  addMcpServer: (label: string, endpoint: string) => { ok: boolean; error?: string };
  removeMcpServer: (id: string) => void;

  // Async Jobs
  asyncJobs: AsyncProviderJob[];
  addOrUpdateAsyncJob: (job: AsyncProviderJob) => void;
  removeAsyncJob: (id: string) => void;

  // Privacy & App Lock
  privacy: PrivacySettings;
  updatePrivacy: (patch: Partial<PrivacySettings>) => void;
  setAppLockPasscode: (passcode: string | null) => Promise<void>;
  isAppLocked: boolean;
  unlockApp: (passcode: string) => Promise<boolean>;
  lockAppNow: () => void;
  wipeAllLocalData: () => void;
}

const AisteeContext = createContext<AisteeContextValue | null>(null);

export const AisteeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [theme, setTheme] = useState<'dark' | 'light'>(() => loadThemeMode());
  const [activeTab, setActiveTab] = useState<NavigationTab>('WEB_CHATS');
  const [toolsSubTab, setToolsSubTab] = useState<StudioToolsSubTab>('SKILLS');

  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const toastTimerRef = useRef<number | null>(null);

  const showToast = useCallback((msg: string) => {
    setToastMessage(msg);
    if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current);
    toastTimerRef.current = window.setTimeout(() => setToastMessage(null), 3200);
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'dark') {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
    saveThemeMode(theme);
  }, [theme]);

  const toggleTheme = useCallback(() => {
    setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'));
  }, []);

  // API keys
  const [isApiKeysOpen, setIsApiKeysOpen] = useState(false);
  const [apiKeys, setApiKeys] = useState<ApiKeyConfig>(() => loadApiKeys());
  const updateApiKeys = useCallback((next: ApiKeyConfig) => {
    setApiKeys(next);
    saveApiKeys(next);
  }, []);

  // Studio profile
  const initialStudio = useMemo(() => loadStudioSnapshot(), []);
  const [baseProfile, setBaseProfile] = useState<Profile>(initialStudio.baseProfile);
  const [selectedOverlayId, setSelectedOverlayId] = useState<string | null>(initialStudio.selectedOverlayId);
  const [customOverlays, setCustomOverlays] = useState<ProfileOverlay[]>(initialStudio.customOverlays);
  const [language, setLanguage] = useState<'auto' | 'en' | 'pl'>(initialStudio.language);

  useEffect(() => {
    saveStudioSnapshot({ baseProfile, selectedOverlayId, customOverlays, language });
  }, [baseProfile, selectedOverlayId, customOverlays, language]);

  const allOverlays = useMemo(() => {
    const map = new Map<string, ProfileOverlay>();
    for (const o of BUILT_IN_OVERLAYS) map.set(o.id, o);
    for (const o of customOverlays) map.set(o.id, o);
    return Array.from(map.values());
  }, [customOverlays]);

  const activeOverlay = useMemo(
    () => allOverlays.find((o) => o.id === selectedOverlayId) ?? null,
    [allOverlays, selectedOverlayId],
  );

  const composedProfile = useMemo(
    () => mergeProfileWithOverlay(baseProfile, activeOverlay),
    [baseProfile, activeOverlay],
  );

  const renderedInstructions = useMemo(
    () => renderInstructions(composedProfile, language),
    [composedProfile, language],
  );

  const saveCustomOverlay = useCallback(
    (overlay: ProfileOverlay) => {
      setCustomOverlays((prev) => {
        const filtered = prev.filter((o) => o.id !== overlay.id);
        return [...filtered, overlay];
      });
      setSelectedOverlayId(overlay.id);
      showToast(`Saved overlay "${overlay.name}"`);
    },
    [showToast],
  );

  const deleteCustomOverlay = useCallback(
    (id: string) => {
      setCustomOverlays((prev) => prev.filter((o) => o.id !== id));
      if (selectedOverlayId === id) setSelectedOverlayId(null);
      showToast('Custom overlay deleted');
    },
    [selectedOverlayId, showToast],
  );

  const resetStudioProfile = useCallback(() => {
    setBaseProfile(structuredClone(DEFAULT_BASE_PROFILE));
    setSelectedOverlayId(null);
    setLanguage('auto');
    showToast('Reset Studio base profile to canonical default');
  }, [showToast]);

  // Markdown & Docbench Workspace handoff
  const [workspaceText, setWorkspaceText] = useState<string>(
    `# Aistee Document Workspace\n\nPaste Markdown, JSON, YAML, or plain text here to run preflight diagnostics, repair fences, normalize line endings, format JSON, or estimate \`o200k_base\` tokens.\n\n\`\`\`ts\nexport function greet(name: string): string {\n  return \`Hello, \${name}\`;\n}\n\`\`\`\n`,
  );
  const [workspaceTitle, setWorkspaceTitle] = useState<string>('Untitled note.md');
  const [workspaceLinkedAssetId, setWorkspaceLinkedAssetId] = useState<string | null>(null);

  const openInMarkdownWorkspace = useCallback(
    (title: string, text: string, linkedAssetId: string | null = null) => {
      setWorkspaceTitle(title);
      setWorkspaceText(text);
      setWorkspaceLinkedAssetId(linkedAssetId);
      setActiveTab('YAML');
    },
    [],
  );

  // Project Library v2
  const [projectLibrary, setProjectLibrary] = useState<ProjectLibraryArchive>(() => loadProjectLibrary());
  useEffect(() => {
    saveProjectLibrary(projectLibrary);
  }, [projectLibrary]);

  const createProject = useCallback(
    (name: string): LocalProject | null => {
      const trimmed = name.trim();
      if (!trimmed) return null;
      const now = Date.now();
      const proj: LocalProject = {
        id: createId('proj'),
        name: trimmed.slice(0, 60),
        createdAtEpochMs: now,
        updatedAtEpochMs: now,
      };
      setProjectLibrary((prev) => ({
        ...prev,
        projects: [...prev.projects, proj],
      }));
      return proj;
    },
    [],
  );

  const renameProject = useCallback((id: string, name: string) => {
    const trimmed = name.trim();
    if (!trimmed || id === DEFAULT_PROJECT_ID) return;
    const now = Date.now();
    setProjectLibrary((prev) => ({
      ...prev,
      projects: prev.projects.map((p) => (p.id === id ? { ...p, name: trimmed, updatedAtEpochMs: now } : p)),
    }));
  }, []);

  const deleteProject = useCallback((id: string) => {
    if (id === DEFAULT_PROJECT_ID) return;
    setProjectLibrary((prev) => ({
      ...prev,
      projects: prev.projects.filter((p) => p.id !== id),
      assets: prev.assets.map((a) => (a.projectId === id ? { ...a, projectId: DEFAULT_PROJECT_ID } : a)),
    }));
  }, []);

  const upsertProjectAsset = useCallback(
    async (input: {
      id?: string;
      projectId: string;
      title: string;
      content: string;
      fileName?: string;
      mediaType?: string;
      kind: ProjectLibraryAssetKind;
      origin: ProjectLibraryAssetOrigin;
    }): Promise<ProjectLibraryAsset> => {
      const now = Date.now();
      const hash = await sha256Hex(input.content);
      const sizeBytes = new TextEncoder().encode(input.content).length;
      let createdAsset!: ProjectLibraryAsset;
      setProjectLibrary((prev) => {
        const existing = input.id ? prev.assets.find((a) => a.id === input.id) : undefined;
        if (existing) {
          createdAsset = {
            ...existing,
            projectId: input.projectId || existing.projectId,
            title: input.title.trim() || existing.title,
            content: input.content,
            fileName: input.fileName || existing.fileName,
            mediaType: input.mediaType || existing.mediaType,
            kind: input.kind,
            sizeBytes,
            updatedAtEpochMs: now,
            revision: existing.revision + 1,
            contentSha256: hash,
          };
          return {
            ...prev,
            assets: prev.assets.map((a) => (a.id === existing.id ? createdAsset : a)),
          };
        }
        createdAsset = {
          id: createId('asset'),
          projectId: input.projectId || DEFAULT_PROJECT_ID,
          title: input.title.trim() || 'Untitled asset',
          mediaType: input.mediaType || 'text/markdown',
          fileName: input.fileName || `${(input.title.trim() || 'asset').toLowerCase().replace(/[^a-z0-9]+/g, '-')}.md`,
          content: input.content,
          sizeBytes,
          createdAtEpochMs: now,
          updatedAtEpochMs: now,
          revision: 1,
          contentSha256: hash,
          kind: input.kind,
          origin: input.origin,
        };
        return {
          ...prev,
          assets: [createdAsset, ...prev.assets],
        };
      });
      return createdAsset;
    },
    [],
  );

  const deleteProjectAsset = useCallback((assetId: string) => {
    setProjectLibrary((prev) => ({
      ...prev,
      assets: prev.assets.filter((a) => a.id !== assetId),
    }));
  }, []);

  const importProjectLibraryJson = useCallback((raw: string): { ok: boolean; message: string } => {
    try {
      const parsed = JSON.parse(raw) as Partial<ProjectLibraryArchive>;
      if (!Array.isArray(parsed.assets)) {
        return { ok: false, message: 'JSON must contain an `assets` array.' };
      }
      const importedProjects = Array.isArray(parsed.projects) ? parsed.projects : [];
      setProjectLibrary((prev) => {
        const projMap = new Map(prev.projects.map((p) => [p.id, p]));
        for (const p of importedProjects) {
          if (p && p.id && p.name && !projMap.has(p.id)) projMap.set(p.id, p);
        }
        const assetMap = new Map(prev.assets.map((a) => [a.id, a]));
        for (const a of parsed.assets ?? []) {
          if (a && a.id && typeof a.content === 'string') {
            assetMap.set(a.id, {
              ...a,
              projectId: projMap.has(a.projectId) ? a.projectId : DEFAULT_PROJECT_ID,
            });
          }
        }
        return {
          version: 2,
          projects: Array.from(projMap.values()),
          assets: Array.from(assetMap.values()),
        };
      });
      return { ok: true, message: `Imported ${parsed.assets.length} project library assets.` };
    } catch (err) {
      return { ok: false, message: err instanceof Error ? err.message : 'Invalid JSON archive.' };
    }
  }, []);

  // Native Conversations
  const initialChats = useMemo(() => {
    const loaded = loadNativeChats();
    if (loaded && loaded.conversations.length > 0) return loaded;
    const starter = createDefaultConversation();
    return { activeConversationId: starter.id, conversations: [starter] };
  }, []);

  const [conversations, setConversations] = useState<NativeChatConversation[]>(initialChats.conversations);
  const [activeConversationId, setActiveConversationId] = useState<string>(initialChats.activeConversationId);
  const [stagedAssetIds, setStagedAssetIds] = useState<string[]>([]);
  const [streamingConversationIds, setStreamingConversationIds] = useState<Record<string, boolean>>({});
  const streamControllersRef = useRef<Record<string, AbortController[]>>({});

  useEffect(() => {
    saveNativeChats({ activeConversationId, conversations });
  }, [activeConversationId, conversations]);

  const activeConversation = useMemo(
    () => conversations.find((c) => c.id === activeConversationId) ?? conversations[0],
    [conversations, activeConversationId],
  );

  const conversationsRef = useRef(conversations);
  conversationsRef.current = conversations;

  const createConversation = useCallback(
    (opts?: { incognito?: boolean; projectId?: string }) => {
      const current = conversationsRef.current.find((c) => c.id === activeConversationId);
      const next = createDefaultConversation({
        isIncognito: opts?.incognito ?? false,
        projectId: opts?.projectId ?? current?.projectId ?? DEFAULT_PROJECT_ID,
        selectedProvider: current?.selectedProvider ?? 'claude',
        selectedModel: current?.selectedModel ?? getProviderInfo('claude').defaultModel,
        includeSystemProfile: current?.includeSystemProfile ?? true,
      });
      setConversations((prev) => [next, ...prev]);
      setActiveConversationId(next.id);
      setStagedAssetIds([]);
    },
    [activeConversationId],
  );

  const deleteConversation = useCallback(
    (id: string) => {
      streamControllersRef.current[id]?.forEach((c) => c.abort());
      delete streamControllersRef.current[id];
      setConversations((prev) => {
        const remaining = prev.filter((c) => c.id !== id);
        if (remaining.length === 0) {
          const fresh = createDefaultConversation();
          setActiveConversationId(fresh.id);
          return [fresh];
        }
        if (activeConversationId === id) {
          setActiveConversationId(remaining[0].id);
        }
        return remaining;
      });
    },
    [activeConversationId],
  );

  const renameConversation = useCallback((id: string, title: string) => {
    const cleaned = title.trim();
    if (!cleaned) return;
    setConversations((prev) =>
      prev.map((c) => (c.id === id ? { ...c, title: cleaned, updatedAtEpochMs: Date.now() } : c)),
    );
  }, []);

  const assignConversationProject = useCallback((id: string, projectId: string) => {
    setConversations((prev) =>
      prev.map((c) => (c.id === id ? { ...c, projectId, updatedAtEpochMs: Date.now() } : c)),
    );
  }, []);

  const updateConversationSettings = useCallback(
    (
      id: string,
      patch: Partial<
        Pick<
          NativeChatConversation,
          'selectedProvider' | 'selectedModel' | 'apiProcessingMode' | 'includeSystemProfile' | 'draft'
        >
      >,
    ) => {
      setConversations((prev) =>
        prev.map((c) => (c.id === id ? { ...c, ...patch } : c)),
      );
    },
    [],
  );

  const toggleMessageStar = useCallback((conversationId: string, messageId: string) => {
    setConversations((prev) =>
      prev.map((c) =>
        c.id !== conversationId
          ? c
          : {
              ...c,
              messages: c.messages.map((m) =>
                m.id === messageId ? { ...m, starred: !m.starred } : m,
              ),
            },
      ),
    );
  }, []);

  const updateMessageStarNote = useCallback((conversationId: string, messageId: string, note: string) => {
    const trimmed = note.slice(0, 140);
    setConversations((prev) =>
      prev.map((c) =>
        c.id !== conversationId
          ? c
          : {
              ...c,
              messages: c.messages.map((m) =>
                m.id === messageId ? { ...m, starred: true, starNote: trimmed } : m,
              ),
            },
      ),
    );
  }, []);

  const branchConversationFromMessage = useCallback(
    (conversationId: string, messageId: string) => {
      const source = conversationsRef.current.find((c) => c.id === conversationId);
      if (!source) return;
      const idx = source.messages.findIndex((m) => m.id === messageId);
      if (idx < 0) return;
      const sliced = source.messages.slice(0, idx + 1).map((m) => ({ ...m }));
      const fork = createDefaultConversation({
        title: `${source.title} (branch)`,
        projectId: source.projectId,
        isIncognito: source.isIncognito,
        selectedProvider: source.selectedProvider,
        selectedModel: source.selectedModel,
        includeSystemProfile: source.includeSystemProfile,
        apiProcessingMode: source.apiProcessingMode,
        parentConversationId: source.id,
        messages: sliced,
      });
      setConversations((prev) => [fork, ...prev]);
      setActiveConversationId(fork.id);
      showToast('Created branched conversation from reply');
    },
    [showToast],
  );

  const toggleStagedAsset = useCallback((assetId: string) => {
    setStagedAssetIds((prev) =>
      prev.includes(assetId) ? prev.filter((id) => id !== assetId) : [...prev, assetId],
    );
  }, []);

  const clearStagedAssets = useCallback(() => setStagedAssetIds([]), []);

  // Gateway live model catalogs
  const [gatewayCatalogs, setGatewayCatalogs] = useState<Record<string, GatewayModelEntry[]>>({});
  const [gatewayCatalogLoading, setGatewayCatalogLoading] = useState<Record<string, boolean>>({});

  const refreshGatewayCatalog = useCallback(
    async (provider: AiProviderId) => {
      if (!getProviderInfo(provider).usesLiveGatewayCatalog) return;
      const key = getApiKeyForProvider(apiKeys, provider);
      const customUrl = provider === 'custom' ? normalizeCustomEndpointBaseUrl(apiKeys.customEndpointUrl) : null;
      if (provider === 'custom' && !customUrl) {
        showToast('Configure a valid HTTPS custom endpoint URL in API Keys first.');
        return;
      }
      if (provider !== 'custom' && !key) {
        showToast(`Add your ${getProviderInfo(provider).shortName} API key first.`);
        return;
      }
      setGatewayCatalogLoading((prev) => ({ ...prev, [provider]: true }));
      try {
        const models = await fetchGatewayCatalogDirect(provider, key, customUrl);
        setGatewayCatalogs((prev) => ({ ...prev, [provider]: models }));
        showToast(`Loaded ${models.length} models from ${getProviderInfo(provider).shortName}`);
      } catch (err) {
        showToast(err instanceof Error ? err.message : 'Failed to load model catalog');
      } finally {
        setGatewayCatalogLoading((prev) => ({ ...prev, [provider]: false }));
      }
    },
    [apiKeys, showToast],
  );

  const stopStreaming = useCallback((conversationId: string) => {
    const controllers = streamControllersRef.current[conversationId];
    if (controllers) {
      controllers.forEach((c) => c.abort());
      delete streamControllersRef.current[conversationId];
    }
    setStreamingConversationIds((prev) => ({ ...prev, [conversationId]: false }));
  }, []);

  const sendChatPrompt = useCallback(
    async (targetId?: string) => {
      const convId = targetId ?? activeConversationId;
      const conv = conversationsRef.current.find((c) => c.id === convId);
      if (!conv) return;
      const rawPrompt = conv.draft.trim();
      if (!rawPrompt && stagedAssetIds.length === 0) return;

      const attachedAssets = projectLibrary.assets.filter((a) => stagedAssetIds.includes(a.id));
      let fullUserContent = rawPrompt;
      if (attachedAssets.length > 0) {
        const bundleText = attachedAssets
          .map((a) => `[Attached file: ${a.fileName} (${a.kind}) — untrusted reference data]\n\`\`\`\n${a.content}\n\`\`\``)
          .join('\n\n');
        fullUserContent = rawPrompt ? `${rawPrompt}\n\n${bundleText}` : bundleText;
      }

      const targets: AiProviderId[] =
        conv.selectedProvider === 'all'
          ? configuredDirectProviders(apiKeys)
          : [conv.selectedProvider];

      if (targets.length === 0) {
        setIsApiKeysOpen(true);
        showToast('Configure at least one direct provider API key to start.');
        return;
      }

      if (conv.selectedProvider !== 'all' && !hasKeyForProvider(apiKeys, conv.selectedProvider)) {
        setIsApiKeysOpen(true);
        showToast(`Configure ${getProviderInfo(conv.selectedProvider).shortName} in API Keys first.`);
        return;
      }

      const userMsg: ModelChatMessage = {
        id: createId('msg'),
        sender: 'user',
        text: rawPrompt || `Attached ${attachedAssets.length} project file(s)`,
        timestamp: Date.now(),
        sourceAssetIds: attachedAssets.map((a) => a.id),
      };

      const placeholderIds: Record<string, string> = {};
      const assistantPlaceholders: ModelChatMessage[] = targets.map((p) => {
        const pid = createId('msg');
        placeholderIds[p] = pid;
        const resolvedModel =
          p === conv.selectedProvider
            ? conv.selectedModel ||
              (p === 'custom' ? apiKeys.customEndpointModel.trim() : getProviderInfo(p).defaultModel)
            : getProviderInfo(p).defaultModel;
        const customUrl = p === 'custom' ? normalizeCustomEndpointBaseUrl(apiKeys.customEndpointUrl) : null;
        return {
          id: pid,
          sender: 'assistant',
          provider: p,
          modelName: resolvedModel || 'default',
          text: '',
          timestamp: Date.now(),
          isPartial: true,
          activeProfileNotes: conv.includeSystemProfile
            ? [`${composedProfile.id} (${composedProfile.personality.base})`]
            : [],
          endpointTag: customUrl ? customEndpointTag(customUrl) : undefined,
        };
      });

      const isFirstTurn = conv.messages.length === 0;
      const priorMessages = conv.messages;

      setConversations((prev) =>
        prev.map((c) =>
          c.id !== convId
            ? c
            : {
                ...c,
                title: isFirstTurn ? autoTitleFromPrompt(userMsg.text) : c.title,
                draft: '',
                updatedAtEpochMs: Date.now(),
                messages: [...c.messages, userMsg, ...assistantPlaceholders],
              },
        ),
      );
      setStagedAssetIds([]);
      setStreamingConversationIds((prev) => ({ ...prev, [convId]: true }));

      const systemPrompt = conv.includeSystemProfile ? renderedInstructions : undefined;
      const controllers: AbortController[] = [];
      streamControllersRef.current[convId] = controllers;

      const streamSingleTarget = async (provider: AiProviderId) => {
        const msgId = placeholderIds[provider];
        const startedAt = performance.now();
        const controller = new AbortController();
        controllers.push(controller);

        const modelForProvider =
          provider === conv.selectedProvider
            ? conv.selectedModel ||
              (provider === 'custom' ? apiKeys.customEndpointModel.trim() : getProviderInfo(provider).defaultModel)
            : getProviderInfo(provider).defaultModel;

        const apiKey = getApiKeyForProvider(apiKeys, provider);
        const customUrl =
          provider === 'custom' ? normalizeCustomEndpointBaseUrl(apiKeys.customEndpointUrl) ?? undefined : undefined;

        // Provider-scoped replay boundary: only replay user messages and assistant messages from THIS provider (no cross-provider/error pollution)
        const scopedHistory: Array<{ role: 'user' | 'assistant'; text: string }> = [];
        for (const m of priorMessages) {
          if (m.isError || !m.text.trim()) continue;
          if (m.sender === 'user') {
            scopedHistory.push({ role: 'user', text: m.text });
          } else if (m.sender === 'assistant' && (m.provider === provider || targets.length === 1)) {
            scopedHistory.push({ role: 'assistant', text: m.text });
          }
        }
        // Keep last 24 turns bounded
        const boundedHistory = [...scopedHistory.slice(-24), { role: 'user' as const, text: fullUserContent }];

        try {
          let accumulatedText = '';
          let latestUsage: ProviderUsage | undefined;
          let resolvedModelName: string | undefined;
          let errorText: string | null = null;

          const flushAssistant = (finalized: boolean) => {
            const latency = Math.round(performance.now() - startedAt);
            setConversations((prev) =>
              prev.map((c) =>
                c.id !== convId
                  ? c
                  : {
                      ...c,
                      updatedAtEpochMs: Date.now(),
                      messages: c.messages.map((m) =>
                        m.id !== msgId
                          ? m
                          : {
                              ...m,
                              modelName: resolvedModelName || m.modelName,
                              text: errorText ? errorText : accumulatedText,
                              isError: Boolean(errorText),
                              isPartial: !finalized,
                              latencyMs: finalized ? latency : undefined,
                              usage: latestUsage,
                            },
                      ),
                    },
              ),
            );
          };

          await streamProviderChatDirect({
            provider,
            model: modelForProvider,
            apiKey,
            customUrl,
            systemPrompt,
            messages: boundedHistory,
            apiProcessingMode: conv.apiProcessingMode,
            signal: controller.signal,
            onDelta: (delta) => {
              accumulatedText += delta;
              flushAssistant(false);
            },
            onUsage: (u, resolvedModel) => {
              if (resolvedModel) resolvedModelName = resolvedModel;
              if (u) {
                latestUsage = {
                  ...(latestUsage ?? {}),
                  ...Object.fromEntries(Object.entries(u).filter(([, v]) => v !== undefined)),
                };
              }
            },
            onError: (msg) => {
              errorText = msg;
            },
          });

          if (!accumulatedText && !errorText) {
            errorText = 'No text output returned from provider.';
          }
          flushAssistant(true);
        } catch (err) {
          const isAbort = (err as Error)?.name === 'AbortError';
          const latency = Math.round(performance.now() - startedAt);
          setConversations((prev) =>
            prev.map((c) =>
              c.id !== convId
                ? c
                : {
                    ...c,
                    messages: c.messages.map((m) =>
                      m.id !== msgId
                        ? m
                        : {
                            ...m,
                            text: isAbort
                              ? m.text || '(Generation stopped by user.)'
                              : err instanceof Error
                                ? err.message
                                : 'Connection error.',
                            isError: !isAbort,
                            isPartial: false,
                            latencyMs: latency,
                          },
                    ),
                  },
            ),
          );
        }
      };

      await Promise.allSettled(targets.map((t) => streamSingleTarget(t)));
      delete streamControllersRef.current[convId];
      setStreamingConversationIds((prev) => ({ ...prev, [convId]: false }));
    },
    [activeConversationId, apiKeys, composedProfile, projectLibrary.assets, renderedInstructions, showToast, stagedAssetIds],
  );

  // Web AI state
  const initialWebPrefs = useMemo(() => loadWebPrefs(), []);
  const [selectedWebService, setSelectedWebService] = useState<WebAiServiceId>(initialWebPrefs.selectedService);
  const [favoriteWebServices, setFavoriteWebServices] = useState<WebAiServiceId[]>(initialWebPrefs.favorites);
  const [comfortableWidthIds, setComfortableWidthIds] = useState<WebAiServiceId[]>(initialWebPrefs.comfortableWidthIds);

  useEffect(() => {
    saveWebPrefs({
      selectedService: selectedWebService,
      favorites: favoriteWebServices,
      comfortableWidthIds,
    });
  }, [selectedWebService, favoriteWebServices, comfortableWidthIds]);

  const toggleFavoriteWebService = useCallback((id: WebAiServiceId) => {
    setFavoriteWebServices((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }, []);

  const toggleComfortableWidth = useCallback((id: WebAiServiceId) => {
    setComfortableWidthIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }, []);

  const [webDrafts, setWebDrafts] = useState<Record<string, WebDraftEntry>>(() => loadWebDrafts());
  useEffect(() => {
    saveWebDrafts(webDrafts);
  }, [webDrafts]);

  const updateWebDraft = useCallback((serviceId: WebAiServiceId, text: string) => {
    setWebDrafts((prev) => {
      const existing = prev[serviceId];
      return {
        ...prev,
        [serviceId]: {
          serviceId,
          text,
          updatedAtEpochMs: Date.now(),
          restorePoints: existing?.restorePoints ?? [],
        },
      };
    });
  }, []);

  const saveWebDraftRestorePoint = useCallback(
    (serviceId: WebAiServiceId) => {
      setWebDrafts((prev) => {
        const existing = prev[serviceId];
        if (!existing || !existing.text.trim()) return prev;
        const points = [
          { text: existing.text, savedAtEpochMs: Date.now() },
          ...(existing.restorePoints ?? []),
        ].slice(0, 6);
        return {
          ...prev,
          [serviceId]: { ...existing, restorePoints: points },
        };
      });
      showToast('Saved draft restore point');
    },
    [showToast],
  );

  const clearWebDraft = useCallback((serviceId: WebAiServiceId) => {
    setWebDrafts((prev) => {
      const existing = prev[serviceId];
      return {
        ...prev,
        [serviceId]: {
          serviceId,
          text: '',
          updatedAtEpochMs: Date.now(),
          restorePoints: existing?.restorePoints ?? [],
        },
      };
    });
  }, []);

  const [webBookmarks, setWebBookmarks] = useState<WebChatBookmark[]>(() => loadWebBookmarks());
  useEffect(() => {
    saveWebBookmarks(webBookmarks);
  }, [webBookmarks]);

  const addWebBookmark = useCallback(
    (bm: Omit<WebChatBookmark, 'id' | 'createdAtEpochMs'>) => {
      const cleanFolder = bm.folderPath
        .split('/')
        .map((s) => s.trim())
        .filter(Boolean)
        .slice(0, 3)
        .join('/');
      setWebBookmarks((prev) => [
        {
          ...bm,
          folderPath: cleanFolder,
          id: createId('bm'),
          createdAtEpochMs: Date.now(),
        },
        ...prev,
      ]);
      showToast('Bookmark saved locally');
    },
    [showToast],
  );

  const deleteWebBookmark = useCallback((id: string) => {
    setWebBookmarks((prev) => prev.filter((b) => b.id !== id));
  }, []);

  const importWebBookmarksJson = useCallback((raw: string) => {
    try {
      const parsed = JSON.parse(raw);
      const list = Array.isArray(parsed) ? parsed : parsed?.bookmarks;
      if (!Array.isArray(list)) {
        return { ok: false, count: 0, error: 'Expected a JSON array or { bookmarks: [...] }.' };
      }
      const valid: WebChatBookmark[] = [];
      for (const item of list) {
        if (item && typeof item.url === 'string' && item.url.startsWith('https://')) {
          valid.push({
            id: typeof item.id === 'string' ? item.id : createId('bm'),
            serviceId: item.serviceId || 'claude',
            title: String(item.title || item.url).slice(0, 120),
            url: item.url,
            folderPath: String(item.folderPath || '').split('/').slice(0, 3).join('/'),
            createdAtEpochMs: Number(item.createdAtEpochMs) || Date.now(),
          });
        }
      }
      setWebBookmarks((prev) => [...valid, ...prev]);
      return { ok: true, count: valid.length };
    } catch (err) {
      return { ok: false, count: 0, error: err instanceof Error ? err.message : 'Invalid JSON' };
    }
  }, []);

  // Local Skills & Active Skills
  const [localSkills, setLocalSkills] = useState<LocalSkillBundle[]>(() => loadLocalSkills());
  const [activeSkillsEnabled, setActiveSkillsEnabledState] = useState<boolean>(() => loadActiveSkillsEnabled());

  useEffect(() => {
    saveLocalSkills(localSkills);
  }, [localSkills]);

  const setActiveSkillsEnabled = useCallback((enabled: boolean) => {
    setActiveSkillsEnabledState(enabled);
    saveActiveSkillsEnabled(enabled);
  }, []);

  const upsertLocalSkill = useCallback(async (source: string, scriptSource?: string) => {
    const manifest = parseSkillManifest(source);
    if (manifest.issues.length > 0) {
      return { ok: false, error: manifest.issues[0] };
    }
    const digest = await sha256Hex(`${source}\n---\n${scriptSource ?? ''}`);
    const bundle: LocalSkillBundle = {
      name: manifest.name,
      source,
      scriptSource,
      digest,
      trusted: false,
      trustedNetworkOrigins: [],
      updatedAtEpochMs: Date.now(),
    };
    setLocalSkills((prev) => [bundle, ...prev.filter((s) => s.name !== manifest.name)]);
    return { ok: true };
  }, []);

  const deleteLocalSkill = useCallback((name: string) => {
    setLocalSkills((prev) => prev.filter((s) => s.name !== name));
  }, []);

  const trustLocalSkill = useCallback((name: string, origins: string[] = []) => {
    setLocalSkills((prev) =>
      prev.map((s) => (s.name === name ? { ...s, trusted: true, trustedNetworkOrigins: origins } : s)),
    );
  }, []);

  const revokeLocalSkillTrust = useCallback((name: string) => {
    setLocalSkills((prev) =>
      prev.map((s) => (s.name === name ? { ...s, trusted: false, trustedNetworkOrigins: [] } : s)),
    );
  }, []);

  // Benches & Streambench
  const [enabledBenches, setEnabledBenches] = useState<BuiltInBenchToolId[]>(() => loadEnabledBenches());
  useEffect(() => {
    saveEnabledBenches(enabledBenches);
  }, [enabledBenches]);

  const toggleBenchEnabled = useCallback((id: BuiltInBenchToolId) => {
    setEnabledBenches((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }, []);

  const initialStreamPrefs = useMemo(() => loadStreambenchPrefs(), []);
  const [streambenchFavorites, setStreambenchFavorites] = useState<string[]>(initialStreamPrefs.favorites);
  const [streambenchRecents, setStreambenchRecents] = useState<string[]>(initialStreamPrefs.recents);

  useEffect(() => {
    saveStreambenchPrefs({ favorites: streambenchFavorites, recents: streambenchRecents });
  }, [streambenchFavorites, streambenchRecents]);

  const toggleStreambenchFavorite = useCallback((hash: string) => {
    setStreambenchFavorites((prev) => (prev.includes(hash) ? prev.filter((x) => x !== hash) : [hash, ...prev]));
  }, []);

  const recordStreambenchPlay = useCallback((hash: string) => {
    setStreambenchRecents((prev) => [hash, ...prev.filter((x) => x !== hash)].slice(0, 12));
  }, []);

  // MCP servers
  const [mcpServers, setMcpServers] = useState<McpServerConfig[]>(() => loadMcpServers());
  useEffect(() => {
    saveMcpServers(mcpServers);
  }, [mcpServers]);

  const addMcpServer = useCallback(
    (label: string, endpoint: string) => {
      if (mcpServers.length >= 8) {
        return { ok: false, error: 'Maximum of 8 saved MCP endpoints reached.' };
      }
      const cleanUrl = endpoint.trim();
      if (!cleanUrl.toLowerCase().startsWith('https://')) {
        return { ok: false, error: 'MCP endpoints must use https://.' };
      }
      const item: McpServerConfig = {
        id: createId('mcp'),
        label: label.trim() || new URL(cleanUrl).host,
        endpoint: cleanUrl,
        createdAtEpochMs: Date.now(),
      };
      setMcpServers((prev) => [...prev, item]);
      return { ok: true };
    },
    [mcpServers.length],
  );

  const removeMcpServer = useCallback((id: string) => {
    setMcpServers((prev) => prev.filter((s) => s.id !== id));
  }, []);

  // Async Jobs
  const [asyncJobs, setAsyncJobs] = useState<AsyncProviderJob[]>(() => loadAsyncJobs());
  useEffect(() => {
    saveAsyncJobs(asyncJobs);
  }, [asyncJobs]);

  const addOrUpdateAsyncJob = useCallback((job: AsyncProviderJob) => {
    setAsyncJobs((prev) => {
      const idx = prev.findIndex((j) => j.id === job.id);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = job;
        return next;
      }
      return [job, ...prev];
    });
  }, []);

  const removeAsyncJob = useCallback((id: string) => {
    setAsyncJobs((prev) => prev.filter((j) => j.id !== id));
  }, []);

  // Privacy & App Lock
  const [privacy, setPrivacy] = useState<PrivacySettings>(() => loadPrivacySettings());
  const [isAppLocked, setIsAppLocked] = useState<boolean>(() => {
    const p = loadPrivacySettings();
    return Boolean(p.appLockEnabled && p.appLockPasscodeHash);
  });

  useEffect(() => {
    savePrivacySettings(privacy);
  }, [privacy]);

  const updatePrivacy = useCallback((patch: Partial<PrivacySettings>) => {
    setPrivacy((prev) => ({ ...prev, ...patch }));
  }, []);

  const setAppLockPasscode = useCallback(async (passcode: string | null) => {
    if (!passcode || !passcode.trim()) {
      setPrivacy((prev) => ({ ...prev, appLockEnabled: false, appLockPasscodeHash: null }));
      setIsAppLocked(false);
      return;
    }
    const hash = await sha256Hex(`aistee-lock:${passcode}`);
    setPrivacy((prev) => ({ ...prev, appLockEnabled: true, appLockPasscodeHash: hash }));
  }, []);

  const unlockApp = useCallback(
    async (passcode: string) => {
      if (!privacy.appLockPasscodeHash) {
        setIsAppLocked(false);
        return true;
      }
      const candidate = await sha256Hex(`aistee-lock:${passcode}`);
      if (candidate === privacy.appLockPasscodeHash) {
        setIsAppLocked(false);
        return true;
      }
      return false;
    },
    [privacy.appLockPasscodeHash],
  );

  const lockAppNow = useCallback(() => {
    if (privacy.appLockEnabled && privacy.appLockPasscodeHash) {
      setIsAppLocked(true);
    }
  }, [privacy.appLockEnabled, privacy.appLockPasscodeHash]);

  const wipeAllLocalData = useCallback(() => {
    Object.values(streamControllersRef.current).forEach((arr) => arr.forEach((c) => c.abort()));
    streamControllersRef.current = {};
    deleteAllAisteeLocalData();
    setApiKeys(loadApiKeys());
    setBaseProfile(structuredClone(DEFAULT_BASE_PROFILE));
    setSelectedOverlayId(null);
    setCustomOverlays([]);
    const freshChat = createDefaultConversation();
    setConversations([freshChat]);
    setActiveConversationId(freshChat.id);
    setProjectLibrary(loadProjectLibrary());
    setWebDrafts({});
    setWebBookmarks([]);
    setLocalSkills([]);
    setActiveSkillsEnabledState(false);
    setMcpServers([]);
    setAsyncJobs([]);
    setPrivacy({ screenPrivacy: false, quickPrivacy: false, appLockEnabled: false, appLockPasscodeHash: null });
    setIsAppLocked(false);
    showToast('Deleted all local Aistee data, chats, skills, and API keys');
  }, [showToast]);

  const value: AisteeContextValue = {
    theme,
    toggleTheme,
    activeTab,
    setActiveTab,
    toolsSubTab,
    setToolsSubTab,
    toastMessage,
    showToast,
    isApiKeysOpen,
    setIsApiKeysOpen,
    apiKeys,
    updateApiKeys,
    baseProfile,
    setBaseProfile,
    selectedOverlayId,
    setSelectedOverlayId,
    customOverlays,
    saveCustomOverlay,
    deleteCustomOverlay,
    allOverlays,
    activeOverlay,
    composedProfile,
    language,
    setLanguage,
    renderedInstructions,
    resetStudioProfile,
    workspaceText,
    setWorkspaceText,
    workspaceTitle,
    setWorkspaceTitle,
    workspaceLinkedAssetId,
    openInMarkdownWorkspace,
    conversations,
    activeConversation,
    setActiveConversationId,
    createConversation,
    deleteConversation,
    renameConversation,
    assignConversationProject,
    updateConversationSettings,
    toggleMessageStar,
    updateMessageStarNote,
    branchConversationFromMessage,
    stagedAssetIds,
    toggleStagedAsset,
    clearStagedAssets,
    streamingConversationIds,
    sendChatPrompt,
    stopStreaming,
    gatewayCatalogs,
    gatewayCatalogLoading,
    refreshGatewayCatalog,
    projectLibrary,
    createProject,
    renameProject,
    deleteProject,
    upsertProjectAsset,
    deleteProjectAsset,
    importProjectLibraryJson,
    selectedWebService,
    setSelectedWebService,
    favoriteWebServices,
    toggleFavoriteWebService,
    comfortableWidthIds,
    toggleComfortableWidth,
    webDrafts,
    updateWebDraft,
    saveWebDraftRestorePoint,
    clearWebDraft,
    webBookmarks,
    addWebBookmark,
    deleteWebBookmark,
    importWebBookmarksJson,
    localSkills,
    activeSkillsEnabled,
    setActiveSkillsEnabled,
    upsertLocalSkill,
    deleteLocalSkill,
    trustLocalSkill,
    revokeLocalSkillTrust,
    enabledBenches,
    toggleBenchEnabled,
    streambenchFavorites,
    streambenchRecents,
    toggleStreambenchFavorite,
    recordStreambenchPlay,
    mcpServers,
    addMcpServer,
    removeMcpServer,
    asyncJobs,
    addOrUpdateAsyncJob,
    removeAsyncJob,
    privacy,
    updatePrivacy,
    setAppLockPasscode,
    isAppLocked,
    unlockApp,
    lockAppNow,
    wipeAllLocalData,
  };

  return <AisteeContext.Provider value={value}>{children}</AisteeContext.Provider>;
};

export function useAistee(): AisteeContextValue {
  const ctx = useContext(AisteeContext);
  if (!ctx) throw new Error('useAistee must be used inside AisteeProvider');
  return ctx;
}
