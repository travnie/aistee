import React, { useMemo, useRef, useState } from 'react';
import yaml from 'js-yaml';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Copy,
  Download,
  FileCode2,
  FileText,
  Layers,
  ShieldAlert,
  Sparkles,
  Upload,
  Wrench,
} from 'lucide-react';
import { useAistee } from '../context/AisteeContext';
import {
  dumpOverlayYaml,
  dumpProfileYaml,
  STYLE_PROFILE_SCHEMA_JSON,
  validateProfile,
} from '../lib/profile';
import {
  countO200kTokens,
  detectLineEndings,
  diagnoseMarkdownDocument,
  formatJsonText,
  normalizeLineEndings,
  repairMarkdownStructure,
} from '../lib/docbench';
import { inspectTextSafety } from '../lib/textInspector';
import { DEFAULT_PROJECT_ID } from '../lib/models';
import { MarkdownContent } from './MarkdownContent';

export const YamlAndMarkdownScreen: React.FC = () => {
  const {
    composedProfile,
    activeOverlay,
    workspaceText,
    setWorkspaceText,
    workspaceTitle,
    setWorkspaceTitle,
    workspaceLinkedAssetId,
    upsertProjectAsset,
    activeConversation,
    showToast,
  } = useAistee();

  const [mode, setMode] = useState<'yaml' | 'docbench'>('docbench');
  const [yamlSubTab, setYamlSubTab] = useState<'profile' | 'overlay' | 'schema'>('profile');
  const docFileInputRef = useRef<HTMLInputElement>(null);

  const profileYaml = useMemo(() => dumpProfileYaml(composedProfile), [composedProfile]);
  const overlayYaml = useMemo(
    () => (activeOverlay ? dumpOverlayYaml(activeOverlay) : '# No overlay active — currently using Base Default Profile.\n'),
    [activeOverlay],
  );
  const validation = useMemo(() => validateProfile(composedProfile), [composedProfile]);
  const yamlParseCheck = useMemo(() => {
    try {
      yaml.load(profileYaml);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: err instanceof Error ? err.message : 'Invalid YAML syntax' };
    }
  }, [profileYaml]);

  // Docbench diagnostics
  const docDiagnostics = useMemo(() => diagnoseMarkdownDocument(workspaceText), [workspaceText]);
  const safetyFindings = useMemo(() => inspectTextSafety(workspaceText), [workspaceText]);
  const lineEndings = useMemo(() => detectLineEndings(workspaceText), [workspaceText]);
  const docTokens = useMemo(() => countO200kTokens(workspaceText), [workspaceText]);

  const activeYamlContent =
    yamlSubTab === 'profile' ? profileYaml : yamlSubTab === 'overlay' ? overlayYaml : STYLE_PROFILE_SCHEMA_JSON;

  const handleDownload = (filename: string, content: string, mime = 'text/plain;charset=utf-8') => {
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="h-full flex flex-col min-h-0 bg-slate-50 dark:bg-[#0F172A]">
      {/* Mode Header */}
      <header className="px-4 py-2.5 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/80 flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex rounded-lg border border-slate-200 dark:border-slate-700 p-0.5 bg-slate-50 dark:bg-slate-900">
          <button
            type="button"
            onClick={() => setMode('docbench')}
            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold whitespace-nowrap ${
              mode === 'docbench' ? 'bg-indigo-600 text-white' : 'text-slate-600 dark:text-slate-300'
            }`}
          >
            <FileText className="h-3.5 w-3.5" />
            Markdown & Docbench Workspace
          </button>
          <button
            type="button"
            onClick={() => setMode('yaml')}
            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold whitespace-nowrap ${
              mode === 'yaml' ? 'bg-indigo-600 text-white' : 'text-slate-600 dark:text-slate-300'
            }`}
          >
            <FileCode2 className="h-3.5 w-3.5" />
            YAML Profile & Schema
          </button>
        </div>

        {mode === 'docbench' ? (
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="text-slate-500 dark:text-slate-400 tabular-nums">
              {docTokens} tokens (o200k) · {lineEndings.style} ({lineEndings.lf} LF / {lineEndings.crlf} CRLF)
            </span>
          </div>
        ) : (
          <div className="flex items-center gap-2 text-xs">
            {validation.isValid && yamlParseCheck.ok ? (
              <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400 font-medium">
                <CheckCircle2 className="h-3.5 w-3.5" />
                Valid Profile & YAML
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 text-rose-600 dark:text-rose-400 font-medium">
                <AlertCircle className="h-3.5 w-3.5" />
                {validation.errors[0] || yamlParseCheck.error}
              </span>
            )}
          </div>
        )}
      </header>

      {mode === 'yaml' ? (
        <div className="flex-1 overflow-y-auto p-4 md:p-6">
          <div className="max-w-4xl mx-auto space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                {(
                  [
                    ['profile', 'profile.yaml'],
                    ['overlay', 'profile.overlay.yaml'],
                    ['schema', 'style-profile.schema.json'],
                  ] as const
                ).map(([id, label]) => (
                  <button
                    key={id}
                    type="button"
                    onClick={() => setYamlSubTab(id)}
                    className={`rounded-lg border px-3 py-1.5 font-mono text-xs whitespace-nowrap ${
                      yamlSubTab === id
                        ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-200 font-semibold'
                        : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>

              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={async () => {
                    await navigator.clipboard.writeText(activeYamlContent);
                    showToast('Copied to clipboard');
                  }}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white whitespace-nowrap"
                >
                  <Copy className="h-3.5 w-3.5" />
                  Copy
                </button>
                <button
                  type="button"
                  onClick={() =>
                    handleDownload(
                      yamlSubTab === 'profile'
                        ? 'profile.yaml'
                        : yamlSubTab === 'overlay'
                          ? 'profile.overlay.yaml'
                          : 'style-profile.schema.json',
                      activeYamlContent,
                    )
                  }
                  className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
                >
                  <Download className="h-3.5 w-3.5" />
                  Export
                </button>
              </div>
            </div>

            <pre className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] p-4 font-mono text-xs leading-relaxed text-slate-800 dark:text-slate-200 overflow-x-auto">
              {activeYamlContent}
            </pre>
          </div>
        </div>
      ) : (
        <div className="flex-1 flex flex-col min-h-0">
          {/* Docbench Toolbar */}
          <div className="px-4 py-2 border-b border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={workspaceTitle}
                onChange={(e) => setWorkspaceTitle(e.target.value)}
                className="w-48 rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-2.5 py-1 text-xs font-medium text-slate-900 dark:text-slate-100"
              />
              <input
                ref={docFileInputRef}
                type="file"
                accept=".md,.txt,.json,.yaml,.yml"
                className="hidden"
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  if (!file) return;
                  const txt = await file.text();
                  setWorkspaceTitle(file.name);
                  setWorkspaceText(txt);
                  showToast(`Opened ${file.name}`);
                  e.target.value = '';
                }}
              />
              <button
                type="button"
                onClick={() => docFileInputRef.current?.click()}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
              >
                <Upload className="h-3.5 w-3.5" />
                Open file
              </button>
              <button
                type="button"
                onClick={async () => {
                  await upsertProjectAsset({
                    id: workspaceLinkedAssetId ?? undefined,
                    projectId: activeConversation?.projectId ?? DEFAULT_PROJECT_ID,
                    title: workspaceTitle,
                    fileName: workspaceTitle,
                    content: workspaceText,
                    kind: 'DOCUMENT',
                    origin: 'LOCAL',
                  });
                  showToast(`Saved "${workspaceTitle}" to Project Library`);
                }}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
              >
                <Layers className="h-3.5 w-3.5 text-indigo-500" />
                Save to Library
              </button>
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              <button
                type="button"
                onClick={() => {
                  const res = repairMarkdownStructure(workspaceText);
                  if (res.changed) {
                    setWorkspaceText(res.text);
                    showToast(`Repaired ${res.repairedIssueCount} Markdown fence issue(s)`);
                  } else {
                    showToast('No unclosed Markdown fences found');
                  }
                }}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
              >
                <Wrench className="h-3.5 w-3.5 text-indigo-500" />
                Repair Fences
              </button>
              <button
                type="button"
                onClick={() => {
                  setWorkspaceText(normalizeLineEndings(workspaceText, 'LF'));
                  showToast('Normalized line endings to LF');
                }}
                className="rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
              >
                Normalize LF
              </button>
              <button
                type="button"
                onClick={() => {
                  const res = formatJsonText(workspaceText);
                  if (res.ok) {
                    setWorkspaceText(res.text);
                    showToast('Formatted JSON');
                  } else {
                    showToast(`JSON format error: ${res.error}`);
                  }
                }}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
              >
                <Sparkles className="h-3.5 w-3.5 text-sky-500" />
                Format JSON
              </button>
              <button
                type="button"
                onClick={() => handleDownload(workspaceTitle || 'document.md', workspaceText)}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap"
              >
                <Download className="h-3.5 w-3.5" />
                Download
              </button>
            </div>
          </div>

          {/* Diagnostics & Safety Strip */}
          {(docDiagnostics.length > 0 || safetyFindings.detectedCount > 0) && (
            <div className="px-4 py-2 border-b border-slate-200 dark:border-slate-800 bg-amber-50/70 dark:bg-amber-950/25 flex flex-wrap items-center gap-3 text-xs">
              {docDiagnostics.map((d, idx) => (
                <span key={idx} className="inline-flex items-center gap-1 text-amber-800 dark:text-amber-300">
                  <AlertTriangle className="h-3.5 w-3.5 shrink-0" />
                  L{d.line}: {d.message}
                </span>
              ))}
              {safetyFindings.detectedCount > 0 && (
                <span className="inline-flex items-center gap-1 text-rose-700 dark:text-rose-300 font-medium">
                  <ShieldAlert className="h-3.5 w-3.5 shrink-0" />
                  Text Inspector: {safetyFindings.highCount} high · {safetyFindings.mediumCount} med ·{' '}
                  {safetyFindings.lowCount} low ({safetyFindings.findings[0]?.label})
                </span>
              )}
            </div>
          )}

          {/* Split Editor + Preview */}
          <div className="flex-1 grid grid-cols-1 lg:grid-cols-2 min-h-0">
            <div className="flex flex-col min-h-0 border-b lg:border-b-0 lg:border-r border-slate-200 dark:border-slate-800">
              <textarea
                value={workspaceText}
                onChange={(e) => setWorkspaceText(e.target.value)}
                spellCheck={false}
                className="flex-1 w-full bg-white dark:bg-[#0B1120] p-4 font-mono text-xs leading-relaxed text-slate-900 dark:text-slate-100 focus:outline-none resize-none"
                placeholder="Write or paste Markdown, JSON, or text…"
              />
            </div>
            <div className="overflow-y-auto bg-white dark:bg-[#0F172A] p-5">
              <MarkdownContent content={workspaceText} onCopySuccess={(l) => showToast(l)} />
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
