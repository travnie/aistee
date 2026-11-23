import {
  ApiKeyConfig,
  AsyncProviderJob,
  BuiltInBenchToolId,
  DEFAULT_PROJECT_ID,
  EMPTY_API_KEYS,
  McpServerConfig,
  NativeChatConversation,
  PrivacySettings,
  ProjectLibraryArchive,
  WebAiServiceId,
  WebChatBookmark,
  WebDraftEntry,
} from './models';
import { DEFAULT_BASE_PROFILE, Profile, ProfileOverlay } from './profile';
import { LocalSkillBundle } from './skills';

const KEYS = {
  THEME: 'aistee.theme',
  API_KEYS: 'aistee.api_keys_v1',
  STUDIO_PROFILE: 'aistee.studio_profile_v1',
  NATIVE_CHATS: 'aistee.native_chats_v1',
  PROJECT_LIBRARY: 'aistee.project_library_v2',
  WEB_PREFS: 'aistee.web_prefs_v1',
  WEB_DRAFTS: 'aistee.web_drafts_v1',
  WEB_BOOKMARKS: 'aistee.web_bookmarks_v1',
  LOCAL_SKILLS: 'aistee.local_skills_v1',
  ACTIVE_SKILLS_ENABLED: 'aistee.active_skills_enabled',
  BUILT_IN_BENCHES: 'aistee.builtin_benches_v1',
  STREAMBENCH_STATIONS: 'aistee.streambench_stations_v1',
  MCP_SERVERS: 'aistee.mcp_servers_v1',
  ASYNC_JOBS: 'aistee.async_jobs_v1',
  PRIVACY: 'aistee.privacy_v1',
} as const;

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function writeJson(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // storage quota ignored
  }
}

export function loadThemeMode(): 'dark' | 'light' {
  const saved = localStorage.getItem(KEYS.THEME);
  return saved === 'light' ? 'light' : 'dark';
}

export function saveThemeMode(mode: 'dark' | 'light'): void {
  localStorage.setItem(KEYS.THEME, mode);
}

export function loadApiKeys(): ApiKeyConfig {
  return { ...EMPTY_API_KEYS, ...readJson<Partial<ApiKeyConfig>>(KEYS.API_KEYS, {}) };
}

export function saveApiKeys(keys: ApiKeyConfig): void {
  writeJson(KEYS.API_KEYS, keys);
}

export interface StudioSnapshot {
  baseProfile: Profile;
  selectedOverlayId: string | null;
  customOverlays: ProfileOverlay[];
  language: 'auto' | 'en' | 'pl';
}

export function loadStudioSnapshot(): StudioSnapshot {
  return readJson<StudioSnapshot>(KEYS.STUDIO_PROFILE, {
    baseProfile: DEFAULT_BASE_PROFILE,
    selectedOverlayId: null,
    customOverlays: [],
    language: 'auto',
  });
}

export function saveStudioSnapshot(snap: StudioSnapshot): void {
  writeJson(KEYS.STUDIO_PROFILE, snap);
}

export interface NativeChatStateStorage {
  activeConversationId: string;
  conversations: NativeChatConversation[];
}

export function loadNativeChats(): NativeChatStateStorage | null {
  return readJson<NativeChatStateStorage | null>(KEYS.NATIVE_CHATS, null);
}

export function saveNativeChats(data: NativeChatStateStorage): void {
  // Never persist incognito conversations
  const filtered = data.conversations.filter((c) => !c.isIncognito);
  const activeValid = filtered.some((c) => c.id === data.activeConversationId)
    ? data.activeConversationId
    : filtered[0]?.id ?? '';
  writeJson(KEYS.NATIVE_CHATS, {
    activeConversationId: activeValid,
    conversations: filtered,
  });
}

export function loadProjectLibrary(): ProjectLibraryArchive {
  const now = Date.now();
  const raw = readJson<ProjectLibraryArchive>(KEYS.PROJECT_LIBRARY, {
    version: 2,
    projects: [{ id: DEFAULT_PROJECT_ID, name: 'Inbox', createdAtEpochMs: now, updatedAtEpochMs: now }],
    assets: [],
  });
  if (!raw.projects.some((p) => p.id === DEFAULT_PROJECT_ID)) {
    raw.projects.unshift({
      id: DEFAULT_PROJECT_ID,
      name: 'Inbox',
      createdAtEpochMs: now,
      updatedAtEpochMs: now,
    });
  }
  return raw;
}

export function saveProjectLibrary(archive: ProjectLibraryArchive): void {
  writeJson(KEYS.PROJECT_LIBRARY, archive);
}

export interface WebPrefs {
  selectedService: WebAiServiceId;
  favorites: WebAiServiceId[];
  comfortableWidthIds: WebAiServiceId[];
}

export function loadWebPrefs(): WebPrefs {
  return readJson<WebPrefs>(KEYS.WEB_PREFS, {
    selectedService: 'claude',
    favorites: ['claude', 'chatgpt', 'gemini'],
    comfortableWidthIds: [],
  });
}

export function saveWebPrefs(prefs: WebPrefs): void {
  writeJson(KEYS.WEB_PREFS, prefs);
}

export function loadWebDrafts(): Record<string, WebDraftEntry> {
  return readJson<Record<string, WebDraftEntry>>(KEYS.WEB_DRAFTS, {});
}

export function saveWebDrafts(drafts: Record<string, WebDraftEntry>): void {
  writeJson(KEYS.WEB_DRAFTS, drafts);
}

export function loadWebBookmarks(): WebChatBookmark[] {
  return readJson<WebChatBookmark[]>(KEYS.WEB_BOOKMARKS, []);
}

export function saveWebBookmarks(bookmarks: WebChatBookmark[]): void {
  writeJson(KEYS.WEB_BOOKMARKS, bookmarks);
}

export function loadLocalSkills(): LocalSkillBundle[] {
  return readJson<LocalSkillBundle[]>(KEYS.LOCAL_SKILLS, []);
}

export function saveLocalSkills(skills: LocalSkillBundle[]): void {
  writeJson(KEYS.LOCAL_SKILLS, skills);
}

export function loadActiveSkillsEnabled(): boolean {
  return localStorage.getItem(KEYS.ACTIVE_SKILLS_ENABLED) === 'true';
}

export function saveActiveSkillsEnabled(enabled: boolean): void {
  localStorage.setItem(KEYS.ACTIVE_SKILLS_ENABLED, enabled ? 'true' : 'false');
}

export function loadEnabledBenches(): BuiltInBenchToolId[] {
  return readJson<BuiltInBenchToolId[]>(KEYS.BUILT_IN_BENCHES, [
    'docbench-document',
    'docbench-text-inspector',
    'codebench-qr-barcode',
  ]);
}

export function saveEnabledBenches(ids: BuiltInBenchToolId[]): void {
  writeJson(KEYS.BUILT_IN_BENCHES, ids);
}

export interface StreambenchPrefs {
  favorites: string[];
  recents: string[];
}

export function loadStreambenchPrefs(): StreambenchPrefs {
  return readJson<StreambenchPrefs>(KEYS.STREAMBENCH_STATIONS, { favorites: [], recents: [] });
}

export function saveStreambenchPrefs(prefs: StreambenchPrefs): void {
  writeJson(KEYS.STREAMBENCH_STATIONS, prefs);
}

export function loadMcpServers(): McpServerConfig[] {
  return readJson<McpServerConfig[]>(KEYS.MCP_SERVERS, []);
}

export function saveMcpServers(servers: McpServerConfig[]): void {
  writeJson(KEYS.MCP_SERVERS, servers);
}

export function loadAsyncJobs(): AsyncProviderJob[] {
  return readJson<AsyncProviderJob[]>(KEYS.ASYNC_JOBS, []);
}

export function saveAsyncJobs(jobs: AsyncProviderJob[]): void {
  writeJson(KEYS.ASYNC_JOBS, jobs);
}

export function loadPrivacySettings(): PrivacySettings {
  return readJson<PrivacySettings>(KEYS.PRIVACY, {
    screenPrivacy: false,
    quickPrivacy: false,
    appLockEnabled: false,
    appLockPasscodeHash: null,
  });
}

export function savePrivacySettings(settings: PrivacySettings): void {
  writeJson(KEYS.PRIVACY, settings);
}

export function deleteAllAisteeLocalData(): void {
  for (const key of Object.values(KEYS)) {
    if (key !== KEYS.THEME) {
      localStorage.removeItem(key);
    }
  }
}
