import { downloadBlob } from '../lib/nativeWorkbench';
import React, { useMemo, useRef, useState } from 'react';
import {
  BookMarked,
  ChevronDown,
  ChevronUp,
  Copy,
  Download,
  FilePlus2,
  FolderKanban,
  GitBranch,
  KeyRound,
  Layers,
  ListTree,
  Lock,
  PanelLeftClose,
  PanelLeftOpen,
  Paperclip,
  Plus,
  Quote,
  RefreshCw,
  Search,
  Send,
  Sparkles,
  Square,
  Star,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { useAistee } from '../context/AisteeContext';
import {
  AI_PROVIDERS,
  AiProviderId,
  ApiProcessingMode,
  configuredDirectProviders,
  DEFAULT_PROJECT_ID,
  getProviderInfo,
  getProviderRuntimeCapabilities,
  hasKeyForProvider,
  ModelChatMessage,
} from '../lib/models';
import { countO200kTokens } from '../lib/docbench';
import { MarkdownContent } from './MarkdownContent';

export const ChatScreen: React.FC = () => {
  const {
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
    deleteProject,
    upsertProjectAsset,
    deleteProjectAsset,
    importProjectLibraryJson,
    apiKeys,
    setIsApiKeysOpen,
    composedProfile,
    renderedInstructions,
    openInMarkdownWorkspace,
    showToast,
  } = useAistee();

  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterProjectId, setFilterProjectId] = useState<string>('ALL');
  const [showLibraryModal, setShowLibraryModal] = useState(false);
  const [showTimelineModal, setShowTimelineModal] = useState(false);
  const [expandedUserMsgs, setExpandedUserMsgs] = useState<Record<string, boolean>>({});
  const [editingNoteMsgId, setEditingNoteMsgId] = useState<string | null>(null);
  const [noteInput, setNoteInput] = useState('');
  const [newProjectName, setNewProjectName] = useState('');

  // Project library asset creation
  const [newAssetTitle, setNewAssetTitle] = useState('');
  const [newAssetContent, setNewAssetContent] = useState('');
  const [newAssetKind, setNewAssetKind] = useState<'DOCUMENT' | 'PROMPT' | 'INSTRUCTION'>('DOCUMENT');
  const libraryFileInputRef = useRef<HTMLInputElement>(null);

  const isStreaming = Boolean(streamingConversationIds[activeConversation.id]);
  const providerInfo = getProviderInfo(activeConversation.selectedProvider);
  const runtimeCaps = getProviderRuntimeCapabilities(activeConversation.selectedProvider);

  const availableModels = useMemo(() => {
    const p = activeConversation.selectedProvider;
    const live = gatewayCatalogs[p];
    if (live && live.length > 0) return live.map((m) => m.id);
    if (p === 'custom' && apiKeys.customEndpointModel.trim()) {
      return [apiKeys.customEndpointModel.trim()];
    }
    return providerInfo.availableModels;
  }, [activeConversation.selectedProvider, gatewayCatalogs, apiKeys.customEndpointModel, providerInfo.availableModels]);

  const filteredConversations = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return conversations.filter((c) => {
      if (filterProjectId !== 'ALL' && c.projectId !== filterProjectId) return false;
      if (!q) return true;
      return (
        c.title.toLowerCase().includes(q) ||
        c.messages.some((m) => m.text.toLowerCase().includes(q))
      );
    });
  }, [conversations, filterProjectId, searchQuery]);

  const timelineItems = useMemo(
    () =>
      activeConversation.messages
        .map((m, idx) => ({ msg: m, index: idx + 1 }))
        .filter((item) => item.msg.sender === 'user' || item.msg.starred),
    [activeConversation.messages],
  );

  const activeProject = useMemo(
    () => projectLibrary.projects.find((p) => p.id === activeConversation.projectId) ?? projectLibrary.projects[0],
    [projectLibrary.projects, activeConversation.projectId],
  );

  const projectAssets = useMemo(
    () =>
      projectLibrary.assets.filter(
        (a) => a.projectId === activeConversation.projectId || a.projectId === DEFAULT_PROJECT_ID,
      ),
    [projectLibrary.assets, activeConversation.projectId],
  );

  const stagedAssets = useMemo(
    () => projectLibrary.assets.filter((a) => stagedAssetIds.includes(a.id)),
    [projectLibrary.assets, stagedAssetIds],
  );

  const composerTokenEstimate = useMemo(() => {
    const draftTokens = countO200kTokens(activeConversation.draft);
    const sysTokens = activeConversation.includeSystemProfile ? countO200kTokens(renderedInstructions) : 0;
    const assetTokens = stagedAssets.reduce((sum, a) => sum + countO200kTokens(a.content), 0);
    return draftTokens + sysTokens + assetTokens;
  }, [activeConversation.draft, activeConversation.includeSystemProfile, renderedInstructions, stagedAssets]);

  const configuredCount = useMemo(() => configuredDirectProviders(apiKeys).length, [apiKeys]);

  const handleProviderChange = (nextProvider: AiProviderId) => {
    const info = getProviderInfo(nextProvider);
    const live = gatewayCatalogs[nextProvider];
    const nextModel =
      live && live.length > 0
        ? live[0].id
        : nextProvider === 'custom'
          ? apiKeys.customEndpointModel.trim() || ''
          : info.defaultModel;
    updateConversationSettings(activeConversation.id, {
      selectedProvider: nextProvider,
      selectedModel: nextModel,
    });
  };

  const handleExportChatMarkdown = () => {
    const lines = [
      `# ${activeConversation.title}`,
      `Project: ${activeProject?.name ?? 'Inbox'} · Provider: ${providerInfo.displayName} · Model: ${activeConversation.selectedModel}`,
      '',
    ];
    for (const m of activeConversation.messages) {
      const role = m.sender === 'user' ? 'User' : `${m.provider ?? 'Assistant'} (${m.modelName ?? ''})`;
      lines.push(`## ${role}`);
      if (m.starNote) lines.push(`> Star note: ${m.starNote}`);
      lines.push(m.text, '');
    }
    const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
    downloadBlob(blob, `${activeConversation.title.toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'chat'}.md`, showToast);
  };

  const handleQuoteMessage = (msg: ModelChatMessage) => {
    const quoted = msg.text
      .split('\n')
      .slice(0, 12)
      .map((l) => `> ${l}`)
      .join('\n');
    const prefix = activeConversation.draft.trim() ? `${activeConversation.draft.trim()}\n\n` : '';
    updateConversationSettings(activeConversation.id, { draft: `${prefix}${quoted}\n\n` });
    showToast('Quoted reply into draft');
  };

  const handleSaveAssetToLibrary = async (title: string, content: string) => {
    if (!(await upsertProjectAsset({
      projectId: activeConversation.projectId,
      title: title.slice(0, 60) || 'Chat snippet',
      content,
      kind: 'DOCUMENT',
      origin: 'CHAT_EXPORT',
    }))) return;
    showToast(`Saved to ${activeProject?.name ?? 'Inbox'} Library`);
  };

  const handleCreateProject = (e: React.FormEvent) => {
    e.preventDefault();
    const created = createProject(newProjectName);
    if (created) {
      assignConversationProject(activeConversation.id, created.id);
      setNewProjectName('');
      showToast(`Created project "${created.name}"`);
    }
  };

  return (
    <div className="flex h-full min-h-0 bg-slate-50 dark:bg-[#0F172A]">
      {/* Conversations & Projects Rail */}
      {sidebarOpen && (
        <aside className="w-72 shrink-0 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/60 flex flex-col min-h-0">
          <div className="p-3 border-b border-slate-200 dark:border-slate-800 space-y-2.5">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => createConversation({ incognito: false })}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white whitespace-nowrap shrink-0"
                >
                  <Plus className="h-3.5 w-3.5" />
                  New Chat
                </button>
                <button
                  type="button"
                  onClick={() => createConversation({ incognito: true })}
                  className="inline-flex items-center gap-1 rounded-lg border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
                  title="Start ephemeral incognito chat (never persisted to disk)"
                >
                  <Lock className="h-3.5 w-3.5" />
                  Incognito
                </button>
              </div>
              <button
                type="button"
                onClick={() => setSidebarOpen(false)}
                className="rounded-md p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
                title="Collapse conversation list"
              >
                <PanelLeftClose className="h-4 w-4" />
              </button>
            </div>

            <div className="relative">
              <Search className="h-3.5 w-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search chats…"
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/70 pl-8 pr-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none"
              />
            </div>

            <div className="flex items-center gap-1.5">
              <FolderKanban className="h-3.5 w-3.5 text-slate-400 shrink-0" />
              <select
                value={filterProjectId}
                onChange={(e) => setFilterProjectId(e.target.value)}
                className="flex-1 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/70 px-2 py-1 text-xs text-slate-700 dark:text-slate-200"
              >
                <option value="ALL">All Projects ({conversations.length})</option>
                {projectLibrary.projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
              <button
                type="button"
                onClick={() => setShowLibraryModal(true)}
                className="rounded-md border border-slate-200 dark:border-slate-700 px-2 py-1 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
              >
                Library
              </button>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto p-2 space-y-1">
            {filteredConversations.map((c) => {
              const active = c.id === activeConversation.id;
              const proj = projectLibrary.projects.find((p) => p.id === c.projectId);
              return (
                <div
                  key={c.id}
                  onClick={() => setActiveConversationId(c.id)}
                  className={`group flex items-start justify-between gap-2 rounded-lg px-2.5 py-2 cursor-pointer border transition-colors ${
                    active
                      ? 'bg-indigo-50 dark:bg-indigo-500/15 border-indigo-200 dark:border-indigo-500/30 text-indigo-900 dark:text-indigo-100'
                      : 'border-transparent hover:bg-slate-100 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-300'
                  }`}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      {c.isIncognito && <Lock className="h-3 w-3 text-amber-500 shrink-0" />}
                      <span className="text-xs font-medium truncate">{c.title}</span>
                    </div>
                    <div className="text-[11px] text-slate-400 truncate mt-0.5 tabular-nums">
                      {getProviderInfo(c.selectedProvider).shortName} · {c.messages.length} msgs
                      {proj && proj.id !== DEFAULT_PROJECT_ID ? ` · ${proj.name}` : ''}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      deleteConversation(c.id);
                    }}
                    className="opacity-0 group-hover:opacity-100 p-1 rounded text-slate-400 hover:text-rose-500 shrink-0"
                    title="Delete conversation"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              );
            })}
          </div>
        </aside>
      )}

      {/* Active Chat Column */}
      <div className="flex-1 flex flex-col min-w-0 min-h-0">
        {/* Top Controls Bar */}
        <header className="border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/80 px-4 py-2.5 space-y-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 min-w-0">
              {!sidebarOpen && (
                <button
                  type="button"
                  onClick={() => setSidebarOpen(true)}
                  className="rounded-md p-1.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 shrink-0"
                  title="Show conversations"
                >
                  <PanelLeftOpen className="h-4 w-4" />
                </button>
              )}

              <select
                value={activeConversation.selectedProvider}
                onChange={(e) => handleProviderChange(e.target.value as AiProviderId)}
                className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs font-semibold text-slate-900 dark:text-slate-100"
              >
                {AI_PROVIDERS.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.displayName}
                  </option>
                ))}
              </select>

              {activeConversation.selectedProvider !== 'all' && (
                <div className="flex items-center gap-1">
                  {availableModels.length > 0 ? (
                    <select
                      value={activeConversation.selectedModel}
                      onChange={(e) =>
                        updateConversationSettings(activeConversation.id, { selectedModel: e.target.value })
                      }
                      className="max-w-[200px] rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 font-mono text-xs text-slate-800 dark:text-slate-200"
                    >
                      {!availableModels.includes(activeConversation.selectedModel) &&
                        activeConversation.selectedModel && (
                          <option value={activeConversation.selectedModel}>
                            {activeConversation.selectedModel}
                          </option>
                        )}
                      {availableModels.map((m) => (
                        <option key={m} value={m}>
                          {m}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      type="text"
                      value={activeConversation.selectedModel}
                      onChange={(e) =>
                        updateConversationSettings(activeConversation.id, { selectedModel: e.target.value })
                      }
                      placeholder="Model ID…"
                      className="w-40 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 font-mono text-xs text-slate-800 dark:text-slate-200"
                    />
                  )}
                  {providerInfo.usesLiveGatewayCatalog && (
                    <button
                      type="button"
                      onClick={() => refreshGatewayCatalog(activeConversation.selectedProvider)}
                      disabled={Boolean(gatewayCatalogLoading[activeConversation.selectedProvider])}
                      className="rounded-lg border border-slate-200 dark:border-slate-700 p-1.5 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                      title="Fetch live /models catalog"
                    >
                      <RefreshCw
                        className={`h-3.5 w-3.5 ${
                          gatewayCatalogLoading[activeConversation.selectedProvider] ? 'animate-spin' : ''
                        }`}
                      />
                    </button>
                  )}
                </div>
              )}

              {activeConversation.selectedProvider === 'chatgpt' && (
                <select
                  value={activeConversation.apiProcessingMode}
                  onChange={(e) =>
                    updateConversationSettings(activeConversation.id, {
                      apiProcessingMode: e.target.value as ApiProcessingMode,
                    })
                  }
                  className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2 py-1.5 text-xs text-slate-700 dark:text-slate-300"
                  title="OpenAI processing tier"
                >
                  <option value="auto">Tier: Auto</option>
                  <option value="standard">Standard</option>
                  <option value="flex">Flex</option>
                  <option value="fast">Priority</option>
                </select>
              )}
            </div>

            <div className="flex items-center gap-2 overflow-x-auto">
              <button
                type="button"
                onClick={() =>
                  updateConversationSettings(activeConversation.id, {
                    includeSystemProfile: !activeConversation.includeSystemProfile,
                  })
                }
                className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium whitespace-nowrap shrink-0 transition-colors ${
                  activeConversation.includeSystemProfile
                    ? 'border-indigo-500/50 bg-indigo-50 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-200'
                    : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400'
                }`}
                title="Attach composed .ai system instructions to upstream requests"
              >
                <Sparkles className="h-3.5 w-3.5" />
                Profile: {activeConversation.includeSystemProfile ? composedProfile.id : 'Off'}
              </button>

              <button
                type="button"
                onClick={() => setShowTimelineModal(true)}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
              >
                <ListTree className="h-3.5 w-3.5" />
                Timeline ({timelineItems.length})
              </button>

              <button
                type="button"
                onClick={handleExportChatMarkdown}
                disabled={activeConversation.messages.length === 0}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-40 whitespace-nowrap shrink-0"
              >
                <Download className="h-3.5 w-3.5" />
                Export .md
              </button>

              <button
                type="button"
                onClick={() => setIsApiKeysOpen(true)}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
              >
                <KeyRound className="h-3.5 w-3.5 text-indigo-500" />
                Keys ({configuredCount})
              </button>
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-slate-500 dark:text-slate-400">
            <div className="flex items-center gap-2 min-w-0">
              <span>{runtimeCaps.transportLabel}</span>
              <span>·</span>
              <span className="truncate">{runtimeCaps.historyStrategyLabel}</span>
            </div>
            <div className="flex items-center gap-2">
              {activeConversation.selectedProvider === 'all' ? (
                <span>Fan-out targets: {configuredCount} configured direct provider(s)</span>
              ) : !hasKeyForProvider(apiKeys, activeConversation.selectedProvider) ? (
                <button
                  type="button"
                  onClick={() => setIsApiKeysOpen(true)}
                  className="text-amber-600 dark:text-amber-400 font-medium hover:underline"
                >
                  API key not configured for {providerInfo.shortName} — click to add
                </button>
              ) : null}
            </div>
          </div>
        </header>

        {/* Incognito Banner */}
        {activeConversation.isIncognito && (
          <div className="px-4 py-2 border-b border-amber-300/60 dark:border-amber-700/50 bg-amber-50/70 dark:bg-amber-950/30 flex items-center gap-2 text-xs text-amber-800 dark:text-amber-200">
            <Lock className="h-3.5 w-3.5 shrink-0" />
            <span>
              <strong>Incognito Chat:</strong> Kept strictly in volatile memory for this session and excluded from local storage, exports, and backups.
            </span>
          </div>
        )}

        {/* Messages Feed */}
        <div className="flex-1 overflow-y-auto px-4 py-5">
          <div className="max-w-4xl mx-auto space-y-4">
            {activeConversation.messages.length === 0 ? (
              <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/60 p-6 space-y-4">
                <div className="space-y-1">
                  <h2 className="font-display text-base font-semibold text-slate-900 dark:text-slate-100">
                    Multi-Provider Native & Compare Chat
                  </h2>
                  <p className="text-xs text-slate-600 dark:text-slate-300">
                    Send prompts directly to Anthropic, OpenAI, Gemini, DeepSeek, Kimi, gateways, or fan out concurrently with <strong>All Models (Compare)</strong>.
                  </p>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  {[
                    'Compare tradeoff: normalized client state vs server-derived state in TypeScript.',
                    'Audit this deployment design for SSRF, XSS, and silent billing fallbacks.',
                    'Write a runnable table comparing SSE vs WebSockets for LLM token streaming.',
                    'Explain how a partial YAML profile overlay merges with a canonical base profile.',
                  ].map((sample, i) => (
                    <button
                      key={i}
                      type="button"
                      onClick={() => updateConversationSettings(activeConversation.id, { draft: sample })}
                      className="text-left rounded-lg border border-slate-200 dark:border-slate-700/80 p-3 text-xs text-slate-700 dark:text-slate-300 hover:border-indigo-400/60 hover:bg-indigo-50/30 dark:hover:bg-indigo-950/20"
                    >
                      {sample}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              activeConversation.messages.map((msg) => {
                const isUser = msg.sender === 'user';
                const lineCount = msg.text.split('\n').length;
                const isLongUser = isUser && (lineCount > 8 || msg.text.length > 550);
                const userExpanded = Boolean(expandedUserMsgs[msg.id]);

                return (
                  <article
                    key={msg.id}
                    id={`turn-${msg.id}`}
                    className={`rounded-xl border p-4 space-y-2.5 ${
                      isUser
                        ? 'border-indigo-200/80 dark:border-indigo-800/60 bg-indigo-50/40 dark:bg-indigo-950/20'
                        : msg.isError
                          ? 'border-rose-300 dark:border-rose-800/70 bg-rose-50/60 dark:bg-rose-950/25'
                          : 'border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B]'
                    }`}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                      <div className="flex flex-wrap items-center gap-1.5 font-medium text-slate-700 dark:text-slate-300">
                        <span className="font-display font-semibold text-slate-900 dark:text-slate-100">
                          {isUser ? 'You' : getProviderInfo(msg.provider ?? 'claude').displayName}
                        </span>
                        {!isUser && msg.modelName && (
                          <span className="font-mono text-[11px] text-slate-500 dark:text-slate-400">
                            · {msg.modelName}
                          </span>
                        )}
                        {!isUser && msg.isPartial && (
                          <span className="text-[11px] text-sky-600 dark:text-sky-400">· Streaming…</span>
                        )}
                        {!isUser && msg.latencyMs !== undefined && (
                          <span className="text-[11px] text-slate-500 dark:text-slate-400 tabular-nums">
                            · {(msg.latencyMs / 1000).toFixed(2)}s
                          </span>
                        )}
                        {!isUser && msg.usage?.totalTokens !== undefined && (
                          <span className="text-[11px] text-slate-500 dark:text-slate-400 tabular-nums">
                            · {msg.usage.totalTokens} tok ({msg.usage.inputTokens ?? 0} in / {msg.usage.outputTokens ?? 0} out)
                          </span>
                        )}
                      </div>

                      <div className="flex items-center gap-1">
                        <button
                          type="button"
                          onClick={() => toggleMessageStar(activeConversation.id, msg.id)}
                          className={`rounded p-1 hover:bg-slate-200/60 dark:hover:bg-slate-800 ${
                            msg.starred ? 'text-amber-500' : 'text-slate-400'
                          }`}
                          title="Star message"
                        >
                          <Star className="h-3.5 w-3.5" fill={msg.starred ? 'currentColor' : 'none'} />
                        </button>
                        {!isUser && (
                          <button
                            type="button"
                            onClick={() => {
                              setEditingNoteMsgId(editingNoteMsgId === msg.id ? null : msg.id);
                              setNoteInput(msg.starNote ?? '');
                            }}
                            className="rounded p-1 text-slate-400 hover:text-indigo-500 hover:bg-slate-200/60 dark:hover:bg-slate-800"
                            title="Add/edit bookmark note (max 140 chars)"
                          >
                            <BookMarked className="h-3.5 w-3.5" />
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => handleQuoteMessage(msg)}
                          className="rounded p-1 text-slate-400 hover:text-indigo-500 hover:bg-slate-200/60 dark:hover:bg-slate-800"
                          title="Quote into draft"
                        >
                          <Quote className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={() => branchConversationFromMessage(activeConversation.id, msg.id)}
                          className="rounded p-1 text-slate-400 hover:text-indigo-500 hover:bg-slate-200/60 dark:hover:bg-slate-800"
                          title="Branch/fork new conversation from here"
                        >
                          <GitBranch className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={() =>
                            openInMarkdownWorkspace(`${activeConversation.title} reply.md`, msg.text)
                          }
                          className="rounded p-1 text-slate-400 hover:text-indigo-500 hover:bg-slate-200/60 dark:hover:bg-slate-800"
                          title="Open in Docbench & Markdown Workspace"
                        >
                          <FilePlus2 className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={() => handleSaveAssetToLibrary(`${activeConversation.title} snippet`, msg.text)}
                          className="rounded p-1 text-slate-400 hover:text-indigo-500 hover:bg-slate-200/60 dark:hover:bg-slate-800"
                          title="Save to Project Library"
                        >
                          <Layers className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={async () => {
                            await navigator.clipboard.writeText(msg.text);
                            showToast('Copied message text');
                          }}
                          className="rounded p-1 text-slate-400 hover:text-indigo-500 hover:bg-slate-200/60 dark:hover:bg-slate-800"
                          title="Copy message"
                        >
                          <Copy className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </div>

                    {msg.starNote && (
                      <div className="text-xs text-amber-700 dark:text-amber-300 bg-amber-50 dark:bg-amber-950/30 border border-amber-200/70 dark:border-amber-800/50 rounded-md px-2.5 py-1">
                        Note: {msg.starNote}
                      </div>
                    )}

                    {editingNoteMsgId === msg.id && (
                      <form
                        onSubmit={(e) => {
                          e.preventDefault();
                          updateMessageStarNote(activeConversation.id, msg.id, noteInput);
                          setEditingNoteMsgId(null);
                        }}
                        className="flex items-center gap-2"
                      >
                        <input
                          type="text"
                          maxLength={140}
                          value={noteInput}
                          onChange={(e) => setNoteInput(e.target.value)}
                          placeholder="Short note (max 140 chars)…"
                          className="flex-1 rounded-md border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-2.5 py-1 text-xs text-slate-900 dark:text-slate-100"
                        />
                        <button
                          type="submit"
                          className="rounded-md bg-indigo-600 px-2.5 py-1 text-xs font-semibold text-white"
                        >
                          Save
                        </button>
                      </form>
                    )}

                    {isUser ? (
                      <div className="space-y-1.5">
                        <div
                          className={`text-sm text-slate-800 dark:text-slate-200 whitespace-pre-wrap ${
                            isLongUser && !userExpanded ? 'line-clamp-6' : ''
                          }`}
                        >
                          {msg.text}
                        </div>
                        {isLongUser && (
                          <button
                            type="button"
                            onClick={() =>
                              setExpandedUserMsgs((prev) => ({ ...prev, [msg.id]: !prev[msg.id] }))
                            }
                            className="inline-flex items-center gap-1 text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:underline"
                          >
                            {userExpanded ? (
                              <>
                                <ChevronUp className="h-3.5 w-3.5" /> Show less
                              </>
                            ) : (
                              <>
                                <ChevronDown className="h-3.5 w-3.5" /> Show all ({lineCount} lines)
                              </>
                            )}
                          </button>
                        )}
                      </div>
                    ) : (
                      <MarkdownContent
                        content={msg.text || (msg.isPartial ? 'Waiting for tokens…' : '')}
                        onCopySuccess={(label) => showToast(label)}
                      />
                    )}
                  </article>
                );
              })
            )}
          </div>
        </div>

        {/* Stable Bottom Composer */}
        <div className="border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/90 px-4 py-3">
          <div className="max-w-4xl mx-auto space-y-2.5">
            {stagedAssets.length > 0 && (
              <div className="flex flex-wrap items-center gap-2 text-xs">
                <span className="text-slate-500 dark:text-slate-400">Attached:</span>
                {stagedAssets.map((a) => (
                  <span
                    key={a.id}
                    className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-800 px-2 py-0.5 text-slate-700 dark:text-slate-200"
                  >
                    <Paperclip className="h-3 w-3" />
                    {a.fileName}
                    <button
                      type="button"
                      onClick={() => toggleStagedAsset(a.id)}
                      className="text-slate-400 hover:text-rose-500"
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </span>
                ))}
                <button
                  type="button"
                  onClick={clearStagedAssets}
                  className="text-xs text-slate-400 hover:text-rose-500"
                >
                  Clear all
                </button>
              </div>
            )}

            <div className="flex items-end gap-2">
              <div className="flex-1">
                <textarea
                  rows={3}
                  value={activeConversation.draft}
                  onChange={(e) =>
                    updateConversationSettings(activeConversation.id, { draft: e.target.value })
                  }
                  onKeyDown={(e) => {
                    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter' && !isStreaming) {
                      e.preventDefault();
                      sendChatPrompt(activeConversation.id);
                    }
                  }}
                  placeholder={
                    activeConversation.selectedProvider === 'all'
                      ? 'Prompt all configured direct providers concurrently… (Cmd/Ctrl+Enter to send)'
                      : `Message ${providerInfo.shortName} (${activeConversation.selectedModel || 'default'})…`
                  }
                  className="w-full rounded-xl border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/80 px-3.5 py-2.5 text-sm text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none resize-none"
                />
              </div>
              <div className="flex flex-col gap-1.5 shrink-0">
                <button
                  type="button"
                  onClick={() => setShowLibraryModal(true)}
                  className="inline-flex items-center justify-center rounded-lg border border-slate-200 dark:border-slate-700 p-2 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                  title="Attach Project Library file"
                >
                  <Paperclip className="h-4 w-4" />
                </button>
                {isStreaming ? (
                  <button
                    type="button"
                    onClick={() => stopStreaming(activeConversation.id)}
                    className="inline-flex items-center justify-center rounded-lg bg-rose-600 hover:bg-rose-500 p-2.5 text-white"
                    title="Stop generation"
                  >
                    <Square className="h-4 w-4" />
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => sendChatPrompt(activeConversation.id)}
                    disabled={!activeConversation.draft.trim() && stagedAssetIds.length === 0}
                    className="inline-flex items-center justify-center rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 p-2.5 text-white"
                    title="Send prompt (Cmd/Ctrl+Enter)"
                  >
                    <Send className="h-4 w-4" />
                  </button>
                )}
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-slate-500 dark:text-slate-400">
              <div className="flex items-center gap-2">
                <span>Project: {activeProject?.name ?? 'Inbox'}</span>
                <span>·</span>
                <span className="tabular-nums">Estimated turn budget: {composerTokenEstimate} tokens (o200k)</span>
              </div>
              <span>Explicit route · Zero silent fallback</span>
            </div>
          </div>
        </div>
      </div>

      {/* Turn Timeline Modal */}
      {showTimelineModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setShowTimelineModal(false)}
        >
          <div
            className="w-full max-w-xl max-h-[80vh] flex flex-col rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700">
              <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                Turn Timeline & Starred Replies
              </h3>
              <button
                type="button"
                onClick={() => setShowTimelineModal(false)}
                className="rounded p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="overflow-y-auto p-4 space-y-2">
              {timelineItems.length === 0 ? (
                <p className="text-xs text-slate-500 text-center py-6">No turns yet.</p>
              ) : (
                timelineItems.map(({ msg, index }) => (
                  <button
                    key={msg.id}
                    type="button"
                    onClick={() => {
                      setShowTimelineModal(false);
                      document.getElementById(`turn-${msg.id}`)?.scrollIntoView({ behavior: 'smooth' });
                    }}
                    className="w-full text-left rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2 hover:border-indigo-400/60"
                  >
                    <div className="flex items-center justify-between text-[11px] text-slate-400">
                      <span>
                        Turn #{index} · {msg.sender === 'user' ? 'User' : msg.provider ?? 'Assistant'}
                      </span>
                      {msg.starred && <Star className="h-3 w-3 text-amber-500" fill="currentColor" />}
                    </div>
                    <div className="text-xs text-slate-800 dark:text-slate-200 truncate mt-0.5">{msg.text}</div>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      {/* Project Library Modal */}
      {showLibraryModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setShowLibraryModal(false)}
        >
          <div
            className="w-full max-w-3xl max-h-[88vh] flex flex-col rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-slate-200 dark:border-slate-700">
              <div>
                <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Local Project Library v2
                </h2>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Organize local files, prompts, and conversation artifacts per project.
                </p>
              </div>
              <div className="flex items-center gap-2">
                <input
                  ref={libraryFileInputRef}
                  type="file"
                  accept=".json,.md,.txt,.yaml,.yml"
                  className="hidden"
                  onChange={async (e) => {
                    const file = e.target.files?.[0];
                    if (!file) return;
                    const text = await file.text();
                    if (file.name.endsWith('.json')) {
                      const res = importProjectLibraryJson(text);
                      showToast(res.message);
                    } else {
                      if (!(await upsertProjectAsset({
                        projectId: activeConversation.projectId,
                        title: file.name,
                        fileName: file.name,
                        content: text,
                        kind: 'DOCUMENT',
                        origin: 'IMPORTED',
                      }))) return;
                      showToast(`Imported ${file.name} into Project Library`);
                    }
                    e.target.value = '';
                  }}
                />
                <button
                  type="button"
                  onClick={() => libraryFileInputRef.current?.click()}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
                >
                  <Upload className="h-3.5 w-3.5" />
                  Import File / JSON
                </button>
                <button
                  type="button"
                  onClick={() => {
                    const blob = new Blob([JSON.stringify(projectLibrary, null, 2)], { type: 'application/json' });
                    downloadBlob(blob, `aistee-library-${new Date().toISOString().slice(0, 10)}.json`, showToast);
                  }}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
                >
                  <Download className="h-3.5 w-3.5" />
                  Export v2 JSON
                </button>
                <button
                  type="button"
                  onClick={() => setShowLibraryModal(false)}
                  className="rounded p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="overflow-y-auto p-5 space-y-5">
              {/* Project selector & creator */}
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs font-medium text-slate-500">Chat Project:</span>
                  {projectLibrary.projects.map((p) => (
                    <div key={p.id} className="inline-flex items-center gap-1">
                      <button
                        type="button"
                        onClick={() => assignConversationProject(activeConversation.id, p.id)}
                        className={`rounded-md px-2.5 py-1 text-xs font-medium ${
                          activeConversation.projectId === p.id
                            ? 'bg-indigo-600 text-white'
                            : 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
                        }`}
                      >
                        {p.name}
                      </button>
                      {p.id !== DEFAULT_PROJECT_ID && (
                        <button
                          type="button"
                          onClick={() => deleteProject(p.id)}
                          className="text-slate-400 hover:text-rose-500 p-0.5"
                          title="Delete project"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
                <form onSubmit={handleCreateProject} className="flex items-center gap-1.5">
                  <input
                    type="text"
                    value={newProjectName}
                    onChange={(e) => setNewProjectName(e.target.value)}
                    placeholder="New project name…"
                    className="rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-2.5 py-1 text-xs text-slate-900 dark:text-slate-100"
                  />
                  <button
                    type="submit"
                    disabled={!newProjectName.trim()}
                    className="rounded-lg bg-slate-800 dark:bg-slate-700 px-2.5 py-1 text-xs font-semibold text-white disabled:opacity-40"
                  >
                    Create
                  </button>
                </form>
              </div>

              {/* Add local asset */}
              <form
                onSubmit={async (e) => {
                  e.preventDefault();
                  if (!newAssetContent.trim()) return;
                  if (!(await upsertProjectAsset({
                    projectId: activeConversation.projectId,
                    title: newAssetTitle.trim() || 'Reference snippet',
                    content: newAssetContent,
                    kind: newAssetKind,
                    origin: 'LOCAL',
                  }))) return;
                  setNewAssetTitle('');
                  setNewAssetContent('');
                  showToast('Saved asset to Project Library');
                }}
                className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50 p-3.5 space-y-2.5"
              >
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                  <input
                    type="text"
                    value={newAssetTitle}
                    onChange={(e) => setNewAssetTitle(e.target.value)}
                    placeholder="Asset title (e.g. API Schema.md)"
                    className="sm:col-span-2 rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  />
                  <select
                    value={newAssetKind}
                    onChange={(e) => setNewAssetKind(e.target.value as 'DOCUMENT' | 'PROMPT' | 'INSTRUCTION')}
                    className="rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  >
                    <option value="DOCUMENT">Document</option>
                    <option value="PROMPT">Prompt</option>
                    <option value="INSTRUCTION">Instruction</option>
                  </select>
                </div>
                <textarea
                  rows={3}
                  value={newAssetContent}
                  onChange={(e) => setNewAssetContent(e.target.value)}
                  placeholder="Paste reference code, spec, or reusable prompt…"
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 p-2.5 font-mono text-xs text-slate-900 dark:text-slate-100"
                />
                <div className="flex justify-end">
                  <button
                    type="submit"
                    disabled={!newAssetContent.trim()}
                    className="rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 px-3 py-1.5 text-xs font-semibold text-white"
                  >
                    Save to {activeProject?.name ?? 'Inbox'}
                  </button>
                </div>
              </form>

              {/* Assets List */}
              <div className="space-y-2">
                {projectAssets.length === 0 ? (
                  <p className="text-xs text-slate-500 py-4 text-center">No assets in this project yet.</p>
                ) : (
                  projectAssets.map((a) => {
                    const isStaged = stagedAssetIds.includes(a.id);
                    return (
                      <div
                        key={a.id}
                        className="flex items-center justify-between gap-3 rounded-lg border border-slate-200 dark:border-slate-700 px-3.5 py-2.5"
                      >
                        <div className="min-w-0">
                          <div className="text-xs font-semibold text-slate-900 dark:text-slate-100 truncate">
                            {a.title}
                          </div>
                          <div className="text-[11px] text-slate-400 tabular-nums">
                            {a.kind} · {a.fileName} · {countO200kTokens(a.content)} tok · rev {a.revision}
                          </div>
                        </div>
                        <div className="flex items-center gap-1.5 shrink-0">
                          <button
                            type="button"
                            onClick={() => {
                              toggleStagedAsset(a.id);
                              showToast(isStaged ? `Detached ${a.fileName}` : `Attached ${a.fileName}`);
                            }}
                            className={`rounded-md px-2.5 py-1 text-xs font-medium whitespace-nowrap ${
                              isStaged
                                ? 'bg-indigo-600 text-white'
                                : 'border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'
                            }`}
                          >
                            {isStaged ? 'Attached ✓' : 'Attach to Chat'}
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              setShowLibraryModal(false);
                              openInMarkdownWorkspace(a.title, a.content, a.id);
                            }}
                            className="rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs text-slate-700 dark:text-slate-200"
                          >
                            Edit
                          </button>
                          <button
                            type="button"
                            onClick={() => deleteProjectAsset(a.id)}
                            className="rounded p-1 text-slate-400 hover:text-rose-500"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
