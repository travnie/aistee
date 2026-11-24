import { downloadBlob } from '../lib/nativeWorkbench';
import React, { useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Bookmark,
  BookOpen,
  Check,
  Columns2,
  Copy,
  Download,
  ExternalLink,
  EyeOff,
  FileText,
  FolderOpen,
  History,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  RotateCcw,
  ShieldAlert,
  Sparkles,
  Star,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { useAistee } from '../context/AisteeContext';
import {
  DEFAULT_PROJECT_ID,
  WEB_AI_SERVICES,
  WebAiService,
  webChatSections,
} from '../lib/models';
import { countO200kTokens } from '../lib/docbench';
import { inspectTextSafety } from '../lib/textInspector';

export const WebChatScreen: React.FC = () => {
  const {
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
    projectLibrary,
    upsertProjectAsset,
    importProjectLibraryJson,
    privacy,
    renderedInstructions,
    composedProfile,
    showToast,
  } = useAistee();

  const [railOpen, setRailOpen] = useState(true);
  const [showBookmarksModal, setShowBookmarksModal] = useState(false);
  const [showPromptVaultModal, setShowPromptVaultModal] = useState(false);
  const [showRestoreMenu, setShowRestoreMenu] = useState(false);
  const [privacyPeek, setPrivacyPeek] = useState(false);

  // Bookmark form state
  const [bmTitle, setBmTitle] = useState('');
  const [bmUrl, setBmUrl] = useState('');
  const [bmFolder, setBmFolder] = useState('');
  const [bmFilterFolder, setBmFilterFolder] = useState<string>('ALL');
  const bmFileInputRef = useRef<HTMLInputElement>(null);

  // Prompt Vault form state
  const [newPromptTitle, setNewPromptTitle] = useState('');
  const [newPromptContent, setNewPromptContent] = useState('');
  const [promptFilterProject, setPromptFilterProject] = useState<string>('ALL');
  const promptFileInputRef = useRef<HTMLInputElement>(null);

  const sections = useMemo(() => webChatSections(favoriteWebServices), [favoriteWebServices]);
  const activeService = useMemo(
    () => WEB_AI_SERVICES.find((s) => s.id === selectedWebService) ?? WEB_AI_SERVICES[0],
    [selectedWebService],
  );

  const currentDraft = webDrafts[activeService.id];
  const draftText = currentDraft?.text ?? '';
  const draftTokens = useMemo(() => countO200kTokens(draftText), [draftText]);
  const isComfortableWidth = comfortableWidthIds.includes(activeService.id);

  const promptAssets = useMemo(
    () =>
      projectLibrary.assets.filter(
        (a) =>
          (a.kind === 'PROMPT' || a.kind === 'INSTRUCTION') &&
          (promptFilterProject === 'ALL' || a.projectId === promptFilterProject),
      ),
    [projectLibrary.assets, promptFilterProject],
  );

  const bookmarkFolders = useMemo(() => {
    const set = new Set<string>();
    for (const b of webBookmarks) {
      if (b.folderPath) set.add(b.folderPath);
    }
    return Array.from(set).sort();
  }, [webBookmarks]);

  const filteredBookmarks = useMemo(() => {
    if (bmFilterFolder === 'ALL') return webBookmarks;
    if (bmFilterFolder === 'ROOT') return webBookmarks.filter((b) => !b.folderPath);
    return webBookmarks.filter((b) => b.folderPath === bmFilterFolder);
  }, [webBookmarks, bmFilterFolder]);

  const handleCopy = async (text: string, label: string) => {
    if (!text.trim()) return;
    try {
      await navigator.clipboard.writeText(text);
      showToast(label);
    } catch {
      showToast('Clipboard permission denied');
    }
  };

  const handleStagePrompt = (title: string, content: string) => {
    const check = inspectTextSafety(content);
    if (check.highCount > 0) {
      showToast(`Preflight flagged ${check.highCount} high-severity text item(s) — review carefully.`);
    }
    const merged = draftText.trim() ? `${draftText.trim()}\n\n${content}` : content;
    updateWebDraft(activeService.id, merged);
    setShowPromptVaultModal(false);
    showToast(`Staged "${title}" into ${activeService.shortName} draft`);
  };

  const handleSaveNewPrompt = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newPromptContent.trim()) return;
    if (!(await upsertProjectAsset({
      projectId: promptFilterProject === 'ALL' ? DEFAULT_PROJECT_ID : promptFilterProject,
      title: newPromptTitle.trim() || 'Saved Web Prompt',
      content: newPromptContent.trim(),
      kind: 'PROMPT',
      origin: 'LOCAL',
    }))) return;
    setNewPromptTitle('');
    setNewPromptContent('');
    showToast('Prompt saved to Project Library');
  };

  const handleAddBookmark = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedUrl = bmUrl.trim();
    if (!trimmedUrl.toLowerCase().startsWith('https://')) {
      showToast('Bookmark URL must begin with https://');
      return;
    }
    addWebBookmark({
      serviceId: activeService.id,
      title: bmTitle.trim() || `${activeService.shortName} Thread`,
      url: trimmedUrl,
      folderPath: bmFolder.trim(),
    });
    setBmTitle('');
    setBmUrl('');
    setBmFolder('');
  };

  const exportBookmarksJson = () => {
    const blob = new Blob([JSON.stringify({ version: 1, bookmarks: webBookmarks }, null, 2)], {
      type: 'application/json',
    });
    downloadBlob(blob, `aistee-web-bookmarks-${new Date().toISOString().slice(0, 10)}.json`, showToast);
  };

  const exportPromptsJson = (singleAssetId?: string) => {
    const assets = singleAssetId
      ? projectLibrary.assets.filter((a) => a.id === singleAssetId)
      : promptAssets;
    const blob = new Blob([JSON.stringify({ version: 2, projects: projectLibrary.projects, assets }, null, 2)], {
      type: 'application/json',
    });
    downloadBlob(blob, singleAssetId ? `aistee-prompt-${singleAssetId}.json` : 'aistee-prompt-vault.json', showToast);
  };

  const renderProviderButton = (service: WebAiService) => {
    const isSelected = service.id === activeService.id;
    const isFav = favoriteWebServices.includes(service.id);
    const hasDraft = Boolean(webDrafts[service.id]?.text?.trim());
    return (
      <div
        key={service.id}
        className={`group flex items-center justify-between gap-2 rounded-lg px-2.5 py-2 text-xs transition-colors cursor-pointer ${
          isSelected
            ? 'bg-indigo-50 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-200 font-semibold border border-indigo-200 dark:border-indigo-500/30'
            : 'text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800/70 border border-transparent'
        }`}
        onClick={() => setSelectedWebService(service.id)}
      >
        <div className="flex items-center gap-2 min-w-0">
          <span
            className="h-2.5 w-2.5 rounded-full shrink-0"
            style={{ backgroundColor: service.brandColor }}
          />
          <span className="truncate">{service.displayName}</span>
          {hasDraft && (
            <span className="text-[10px] font-mono text-sky-600 dark:text-sky-400 shrink-0" title="Unsent draft saved">
              draft
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            toggleFavoriteWebService(service.id);
          }}
          className={`p-1 rounded hover:bg-slate-200/70 dark:hover:bg-slate-700/70 shrink-0 ${
            isFav ? 'text-amber-500' : 'text-slate-400 opacity-0 group-hover:opacity-100'
          }`}
          title={isFav ? 'Remove from favorites' : 'Pin to favorites'}
        >
          <Star className="h-3.5 w-3.5" fill={isFav ? 'currentColor' : 'none'} />
        </button>
      </div>
    );
  };

  return (
    <div className="flex h-full min-h-0 bg-slate-50 dark:bg-[#0F172A]">
      {/* Provider Rail */}
      {railOpen && (
        <aside className="w-60 shrink-0 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/60 flex flex-col min-h-0">
          <div className="flex items-center justify-between px-3.5 py-3 border-b border-slate-200 dark:border-slate-800">
            <div>
              <h2 className="font-display text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                Web AI Providers
              </h2>
            </div>
            <button
              type="button"
              onClick={() => setRailOpen(false)}
              className="rounded-md p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              title="Collapse provider rail"
            >
              <PanelLeftClose className="h-4 w-4" />
            </button>
          </div>

          <div className="flex-1 overflow-y-auto p-2.5 space-y-4">
            {sections.favorites.length > 0 && (
              <div className="space-y-1">
                <div className="px-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                  Favorites
                </div>
                {sections.favorites.map(renderProviderButton)}
              </div>
            )}

            <div className="space-y-1">
              <div className="px-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                Primary Web Chats
              </div>
              {sections.primary.map(renderProviderButton)}
            </div>

            <div className="space-y-1">
              <div className="px-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                More Chats
              </div>
              {sections.additional.map(renderProviderButton)}
            </div>
          </div>
        </aside>
      )}

      {/* Main Web Workspace */}
      <div className="flex-1 flex flex-col min-w-0 min-h-0">
        {/* Workspace Header */}
        <header className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/80">
          <div className="flex items-center gap-2.5 min-w-0">
            {!railOpen && (
              <button
                type="button"
                onClick={() => setRailOpen(true)}
                className="rounded-md p-1.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 shrink-0"
                title="Show provider rail"
              >
                <PanelLeftOpen className="h-4 w-4" />
              </button>
            )}
            <span
              className="h-3 w-3 rounded-full shrink-0"
              style={{ backgroundColor: activeService.brandColor }}
            />
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h1 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100 truncate">
                  {activeService.displayName}
                </h1>
                <span className="text-xs text-slate-500 dark:text-slate-400 hidden sm:inline">
                  · {activeService.status} · Account-backed Web
                </span>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2 overflow-x-auto">
            <button
              type="button"
              onClick={() => toggleComfortableWidth(activeService.id)}
              className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium whitespace-nowrap shrink-0 transition-colors ${
                isComfortableWidth
                  ? 'border-indigo-500/50 bg-indigo-50 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-200'
                  : 'border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
              }`}
              title="Toggle comfortable 720px workspace width"
            >
              <Columns2 className="h-3.5 w-3.5" />
              720px Width
            </button>

            <button
              type="button"
              onClick={() => setShowPromptVaultModal(true)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
            >
              <BookOpen className="h-3.5 w-3.5" />
              Prompt Vault ({promptAssets.length})
            </button>

            <button
              type="button"
              onClick={() => setShowBookmarksModal(true)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
            >
              <Bookmark className="h-3.5 w-3.5" />
              Bookmarks ({webBookmarks.length})
            </button>

            <a
              href={activeService.url}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white whitespace-nowrap shrink-0"
            >
              Open {activeService.shortName}
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </div>
        </header>

        {/* Scrollable Workspace Content */}
        <div className="flex-1 overflow-y-auto p-4 md:p-6">
          <div className={`mx-auto space-y-5 ${isComfortableWidth ? 'max-w-[720px]' : 'max-w-5xl'}`}>
            {/* Privacy Screen Mask Banner */}
            {privacy.screenPrivacy && !privacyPeek && (
              <div className="rounded-xl border border-amber-300 dark:border-amber-700/60 bg-amber-50/70 dark:bg-amber-950/30 p-5 flex items-center justify-between gap-4">
                <div className="flex items-start gap-3">
                  <EyeOff className="h-5 w-5 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
                  <div>
                    <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                      Screen Privacy Shield Active
                    </h3>
                    <p className="text-xs text-slate-600 dark:text-slate-300 mt-0.5">
                      Draft content and staged prompts are veiled while Screen Privacy is enabled in Tools → Privacy.
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setPrivacyPeek(true)}
                  className="rounded-lg border border-amber-400/60 px-3 py-1.5 text-xs font-semibold text-amber-800 dark:text-amber-200 hover:bg-amber-100 dark:hover:bg-amber-900/40 whitespace-nowrap shrink-0"
                >
                  Reveal session
                </button>
              </div>
            )}

            {/* Honest Account Session Card */}
            <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-4">
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div className="space-y-1 max-w-2xl">
                  <div className="text-xs font-medium text-indigo-600 dark:text-indigo-400">
                    Account-Backed Session Handoff · Zero Cookie Interception
                  </div>
                  <h2 className="font-display text-lg font-semibold text-slate-900 dark:text-slate-100">
                    {activeService.displayName}
                  </h2>
                  <p className="text-sm text-slate-600 dark:text-slate-300">
                    {activeService.description}. {activeService.notes}.
                  </p>
                </div>

                <a
                  href={activeService.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-4 py-2 text-xs font-semibold text-white whitespace-nowrap shrink-0"
                >
                  Launch {activeService.shortName} in Browser Tab
                  <ExternalLink className="h-3.5 w-3.5" />
                </a>
              </div>

              <div className="rounded-lg border border-slate-200 dark:border-slate-700/70 bg-slate-50 dark:bg-slate-900/60 px-3.5 py-2.5 text-xs text-slate-600 dark:text-slate-400 flex flex-wrap items-center justify-between gap-2">
                <span>
                  Browsers block third-party providers from rendering inside embedded frames (<code className="font-mono">X-Frame-Options</code> / CSP). Aistee launches your native browser session directly without intercepting passwords or session cookies.
                </span>
                <span className="font-mono text-[11px] text-slate-500 dark:text-slate-400">
                  {activeService.url}
                </span>
              </div>
            </section>

            {/* Draft Vault + .ai Profile Quick Copy */}
            <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] overflow-hidden">
              <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 border-b border-slate-200 dark:border-slate-700/80 bg-slate-50/70 dark:bg-slate-900/40">
                <div className="flex items-center gap-2">
                  <FileText className="h-4 w-4 text-sky-500 dark:text-sky-400" />
                  <span className="font-display text-xs font-semibold text-slate-800 dark:text-slate-100">
                    Local Draft Vault · {activeService.shortName}
                  </span>
                  <span className="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                    · {draftTokens} tokens (o200k)
                  </span>
                </div>

                <div className="flex items-center gap-2">
                  <div className="relative">
                    <button
                      type="button"
                      onClick={() => setShowRestoreMenu((v) => !v)}
                      className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
                    >
                      <History className="h-3.5 w-3.5" />
                      Restore ({currentDraft?.restorePoints?.length ?? 0})
                    </button>
                    {showRestoreMenu && (
                      <div className="absolute right-0 mt-1.5 w-72 z-30 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-xl p-2 space-y-1.5">
                        <div className="flex items-center justify-between px-2 py-1 border-b border-slate-200 dark:border-slate-700">
                          <span className="text-[11px] font-semibold text-slate-500 dark:text-slate-400">
                            Saved Restore Points
                          </span>
                          <button
                            type="button"
                            onClick={() => {
                              saveWebDraftRestorePoint(activeService.id);
                              setShowRestoreMenu(false);
                            }}
                            className="text-[11px] font-semibold text-indigo-600 dark:text-indigo-400 hover:underline"
                          >
                            + Snapshot now
                          </button>
                        </div>
                        {(!currentDraft?.restorePoints || currentDraft.restorePoints.length === 0) ? (
                          <div className="px-2 py-3 text-xs text-slate-500 dark:text-slate-400 text-center">
                            No restore points yet.
                          </div>
                        ) : (
                          currentDraft.restorePoints.map((rp, idx) => (
                            <button
                              key={idx}
                              type="button"
                              onClick={() => {
                                updateWebDraft(activeService.id, rp.text);
                                setShowRestoreMenu(false);
                                showToast('Restored draft snapshot');
                              }}
                              className="w-full text-left rounded-md px-2 py-1.5 text-xs hover:bg-slate-100 dark:hover:bg-slate-800"
                            >
                              <div className="flex items-center justify-between text-[11px] text-slate-400">
                                <span>{new Date(rp.savedAtEpochMs).toLocaleTimeString()}</span>
                                <RotateCcw className="h-3 w-3" />
                              </div>
                              <div className="truncate text-slate-700 dark:text-slate-200 mt-0.5">{privacy.screenPrivacy && !privacyPeek ? 'Hidden snapshot' : rp.text}</div>
                            </button>
                          ))
                        )}
                      </div>
                    )}
                  </div>

                  <button
                    type="button"
                    onClick={() => saveWebDraftRestorePoint(activeService.id)}
                    disabled={!draftText.trim()}
                    className="rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-40 whitespace-nowrap shrink-0"
                  >
                    Save snapshot
                  </button>

                  <button
                    type="button"
                    onClick={() => clearWebDraft(activeService.id)}
                    disabled={!draftText.trim()}
                    className="rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-rose-600 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-950/30 disabled:opacity-40 whitespace-nowrap shrink-0"
                  >
                    Clear
                  </button>
                </div>
              </div>

              <div className="p-4 space-y-3">
                <textarea
                  rows={7}
                  value={privacy.screenPrivacy && !privacyPeek ? '' : draftText}
                  onChange={(e) => updateWebDraft(activeService.id, e.target.value)}
                  disabled={privacy.screenPrivacy && !privacyPeek}
                  placeholder={`Stage your long prompt for ${activeService.displayName} here. Drafts autosave per provider in your local browser vault...`}
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/70 p-3 text-sm text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none resize-y"
                />

                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      onClick={() => handleCopy(draftText, `Copied ${activeService.shortName} draft`)}
                      disabled={!draftText.trim()}
                      className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 px-3 py-1.5 text-xs font-semibold text-white whitespace-nowrap shrink-0"
                    >
                      <Copy className="h-3.5 w-3.5" />
                      Copy Draft
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        handleCopy(
                          `${renderedInstructions}\n\n${draftText}`,
                          `Copied ${composedProfile.id} instructions + draft`,
                        )
                      }
                      disabled={!draftText.trim()}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-40 whitespace-nowrap shrink-0"
                    >
                      <Sparkles className="h-3.5 w-3.5 text-indigo-500" />
                      Copy with .ai Profile ({composedProfile.id})
                    </button>
                  </div>
                  <span className="text-[11px] text-slate-400">
                    {currentDraft?.updatedAtEpochMs
                      ? `Saved ${new Date(currentDraft.updatedAtEpochMs).toLocaleTimeString()}`
                      : 'Autosaves per provider'}
                  </span>
                </div>
              </div>
            </section>

            {/* Quick Bookmarks Preview */}
            {webBookmarks.filter((b) => b.serviceId === activeService.id).length > 0 && (
              <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-2.5">
                <div className="flex items-center justify-between">
                  <h3 className="font-display text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                    Saved {activeService.shortName} Bookmarks
                  </h3>
                  <button
                    type="button"
                    onClick={() => setShowBookmarksModal(true)}
                    className="text-xs text-indigo-600 dark:text-indigo-400 hover:underline"
                  >
                    Manage all
                  </button>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {webBookmarks
                    .filter((b) => b.serviceId === activeService.id)
                    .slice(0, 4)
                    .map((bm) => (
                      <a
                        key={bm.id}
                        href={bm.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="flex items-center justify-between gap-2 rounded-lg border border-slate-200 dark:border-slate-700/80 px-3 py-2 text-xs hover:border-indigo-400/60"
                      >
                        <div className="min-w-0">
                          <div className="font-medium text-slate-800 dark:text-slate-100 truncate">{bm.title}</div>
                          {bm.folderPath && (
                            <div className="text-[11px] text-slate-400 truncate">{bm.folderPath}</div>
                          )}
                        </div>
                        <ExternalLink className="h-3.5 w-3.5 text-slate-400 shrink-0" />
                      </a>
                    ))}
                </div>
              </section>
            )}
          </div>
        </div>
      </div>

      {/* Bookmarks Modal */}
      {showBookmarksModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setShowBookmarksModal(false)}
        >
          <div
            className="w-full max-w-3xl max-h-[88vh] flex flex-col rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-slate-200 dark:border-slate-700">
              <div className="flex items-center gap-2">
                <Bookmark className="h-4 w-4 text-indigo-500" />
                <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Local Web Chat Bookmarks
                </h2>
              </div>
              <div className="flex items-center gap-2">
                <input
                  ref={bmFileInputRef}
                  type="file"
                  accept=".json"
                  className="hidden"
                  onChange={async (e) => {
                    const file = e.target.files?.[0];
                    if (!file) return;
                    const text = await file.text();
                    const res = importWebBookmarksJson(text);
                    showToast(res.ok ? `Imported ${res.count} bookmarks` : res.error || 'Import failed');
                    e.target.value = '';
                  }}
                />
                <button
                  type="button"
                  onClick={() => bmFileInputRef.current?.click()}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
                >
                  <Upload className="h-3.5 w-3.5" />
                  Import JSON
                </button>
                <button
                  type="button"
                  onClick={exportBookmarksJson}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
                >
                  <Download className="h-3.5 w-3.5" />
                  Export JSON
                </button>
                <button
                  type="button"
                  onClick={() => setShowBookmarksModal(false)}
                  className="rounded-md p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="overflow-y-auto p-5 space-y-5">
              <form onSubmit={handleAddBookmark} className="grid grid-cols-1 sm:grid-cols-4 gap-2.5 items-end">
                <label className="block space-y-1">
                  <span className="text-[11px] font-medium text-slate-500">Title</span>
                  <input
                    type="text"
                    value={bmTitle}
                    onChange={(e) => setBmTitle(e.target.value)}
                    placeholder="Architecture thread"
                    className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  />
                </label>
                <label className="block space-y-1 sm:col-span-2">
                  <span className="text-[11px] font-medium text-slate-500">HTTPS Thread URL</span>
                  <input
                    type="url"
                    required
                    value={bmUrl}
                    onChange={(e) => setBmUrl(e.target.value)}
                    placeholder={activeService.url}
                    className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100"
                  />
                </label>
                <div className="flex gap-2">
                  <label className="block space-y-1 flex-1">
                    <span className="text-[11px] font-medium text-slate-500">Folder (≤3 levels)</span>
                    <input
                      type="text"
                      value={bmFolder}
                      onChange={(e) => setBmFolder(e.target.value)}
                      placeholder="Work/Reviews"
                      className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                    />
                  </label>
                  <button
                    type="submit"
                    className="self-end inline-flex items-center gap-1 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white whitespace-nowrap shrink-0"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    Add
                  </button>
                </div>
              </form>

              <div className="flex items-center gap-2 flex-wrap">
                <FolderOpen className="h-3.5 w-3.5 text-slate-400" />
                <button
                  type="button"
                  onClick={() => setBmFilterFolder('ALL')}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium ${
                    bmFilterFolder === 'ALL'
                      ? 'bg-indigo-600 text-white'
                      : 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
                  }`}
                >
                  All ({webBookmarks.length})
                </button>
                <button
                  type="button"
                  onClick={() => setBmFilterFolder('ROOT')}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium ${
                    bmFilterFolder === 'ROOT'
                      ? 'bg-indigo-600 text-white'
                      : 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
                  }`}
                >
                  Unfiled
                </button>
                {bookmarkFolders.map((folder) => (
                  <button
                    key={folder}
                    type="button"
                    onClick={() => setBmFilterFolder(folder)}
                    className={`rounded-md px-2.5 py-1 text-xs font-medium ${
                      bmFilterFolder === folder
                        ? 'bg-indigo-600 text-white'
                        : 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
                    }`}
                  >
                    {folder}
                  </button>
                ))}
              </div>

              <div className="space-y-2">
                {filteredBookmarks.length === 0 ? (
                  <p className="text-xs text-slate-500 py-6 text-center">No bookmarks in this folder yet.</p>
                ) : (
                  filteredBookmarks.map((bm) => (
                    <div
                      key={bm.id}
                      className="flex items-center justify-between gap-3 rounded-lg border border-slate-200 dark:border-slate-700 px-3.5 py-2.5"
                    >
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-semibold text-slate-900 dark:text-slate-100 truncate">
                            {bm.title}
                          </span>
                          <span className="text-[11px] text-slate-400">· {bm.serviceId}</span>
                          {bm.folderPath && (
                            <span className="text-[11px] text-indigo-600 dark:text-indigo-400">· {bm.folderPath}</span>
                          )}
                        </div>
                        <div className="font-mono text-[11px] text-slate-500 truncate">{bm.url}</div>
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        <a
                          href={bm.url}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-950/30"
                        >
                          Open
                        </a>
                        <button
                          type="button"
                          onClick={() => deleteWebBookmark(bm.id)}
                          className="rounded-md p-1 text-slate-400 hover:text-rose-500"
                          title="Delete bookmark"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Web Prompt Vault Modal */}
      {showPromptVaultModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setShowPromptVaultModal(false)}
        >
          <div
            className="w-full max-w-3xl max-h-[88vh] flex flex-col rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-slate-200 dark:border-slate-700">
              <div>
                <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Web Prompt Vault (Project Library Prompts)
                </h2>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Runs local Text Inspector preflight before staging prompts into {activeService.shortName}.
                </p>
              </div>
              <div className="flex items-center gap-2">
                <input
                  ref={promptFileInputRef}
                  type="file"
                  accept=".json"
                  className="hidden"
                  onChange={async (e) => {
                    const file = e.target.files?.[0];
                    if (!file) return;
                    const text = await file.text();
                    const res = importProjectLibraryJson(text);
                    showToast(res.message);
                    e.target.value = '';
                  }}
                />
                <button
                  type="button"
                  onClick={() => promptFileInputRef.current?.click()}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap shrink-0"
                >
                  <Upload className="h-3.5 w-3.5" />
                  Import JSON
                </button>
                <button
                  type="button"
                  onClick={() => exportPromptsJson()}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap shrink-0"
                >
                  <Download className="h-3.5 w-3.5" />
                  Export All
                </button>
                <button
                  type="button"
                  onClick={() => setShowPromptVaultModal(false)}
                  className="rounded-md p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="overflow-y-auto p-5 space-y-5">
              <form onSubmit={handleSaveNewPrompt} className="rounded-lg border border-slate-200 dark:border-slate-700 p-3.5 space-y-2.5 bg-slate-50 dark:bg-slate-900/50">
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
                  <input
                    type="text"
                    value={newPromptTitle}
                    onChange={(e) => setNewPromptTitle(e.target.value)}
                    placeholder="Prompt title (e.g. Security Diff Audit)"
                    className="sm:col-span-2 rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  />
                  <select
                    value={promptFilterProject}
                    onChange={(e) => setPromptFilterProject(e.target.value)}
                    className="rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  >
                    <option value="ALL">All Projects</option>
                    {projectLibrary.projects.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </div>
                <textarea
                  rows={3}
                  value={newPromptContent}
                  onChange={(e) => setNewPromptContent(e.target.value)}
                  placeholder="Write reusable prompt text to save into your local Project Library..."
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 p-2.5 text-xs text-slate-900 dark:text-slate-100"
                />
                <div className="flex justify-end">
                  <button
                    type="submit"
                    disabled={!newPromptContent.trim()}
                    className="inline-flex items-center gap-1 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 px-3 py-1.5 text-xs font-semibold text-white"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    Save Prompt to Library
                  </button>
                </div>
              </form>

              <div className="space-y-2.5">
                {promptAssets.length === 0 ? (
                  <p className="text-xs text-slate-500 py-6 text-center">
                    No saved prompts or instructions yet. Add one above or import JSON.
                  </p>
                ) : (
                  promptAssets.map((asset) => {
                    const safety = inspectTextSafety(asset.content);
                    return (
                      <div
                        key={asset.id}
                        className="rounded-lg border border-slate-200 dark:border-slate-700 p-3.5 space-y-2"
                      >
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <span className="font-semibold text-xs text-slate-900 dark:text-slate-100">
                                {asset.title}
                              </span>
                              <span className="text-[11px] text-slate-400">
                                · {asset.kind} · {countO200kTokens(asset.content)} tokens
                              </span>
                            </div>
                            {safety.detectedCount > 0 ? (
                              <div className="inline-flex items-center gap-1 text-[11px] text-amber-600 dark:text-amber-400 mt-0.5">
                                {safety.highCount > 0 ? (
                                  <ShieldAlert className="h-3 w-3 text-rose-500" />
                                ) : (
                                  <AlertTriangle className="h-3 w-3" />
                                )}
                                Inspector: {safety.highCount} high, {safety.mediumCount} medium finding(s)
                              </div>
                            ) : (
                              <div className="inline-flex items-center gap-1 text-[11px] text-emerald-600 dark:text-emerald-400 mt-0.5">
                                <Check className="h-3 w-3" />
                                Text Inspector clean
                              </div>
                            )}
                          </div>
                          <div className="flex items-center gap-1.5 shrink-0">
                            <button
                              type="button"
                              onClick={() => handleStagePrompt(asset.title, asset.content)}
                              className="rounded-md bg-indigo-600 hover:bg-indigo-500 px-2.5 py-1 text-xs font-semibold text-white whitespace-nowrap"
                            >
                              Stage into Draft
                            </button>
                            <button
                              type="button"
                              onClick={() => handleCopy(asset.content, `Copied "${asset.title}"`)}
                              className="rounded-md border border-slate-200 dark:border-slate-700 px-2 py-1 text-xs text-slate-600 dark:text-slate-300"
                            >
                              Copy
                            </button>
                            <button
                              type="button"
                              onClick={() => exportPromptsJson(asset.id)}
                              className="rounded-md border border-slate-200 dark:border-slate-700 px-2 py-1 text-xs text-slate-600 dark:text-slate-300"
                              title="Export single prompt JSON"
                            >
                              <Download className="h-3.5 w-3.5" />
                            </button>
                          </div>
                        </div>
                        <pre className="rounded bg-slate-50 dark:bg-slate-900/70 p-2.5 font-mono text-[11.5px] text-slate-700 dark:text-slate-300 whitespace-pre-wrap max-h-28 overflow-y-auto">
                          {asset.content}
                        </pre>
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
