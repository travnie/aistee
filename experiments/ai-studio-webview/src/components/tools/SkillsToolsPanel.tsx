import { downloadBlob } from '../../lib/nativeWorkbench';
import React, { useRef, useState } from 'react';
import {
  Copy,
  Download,
  Plus,
  ShieldCheck,
  ShieldOff,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { useAistee } from '../../context/AisteeContext';
import {
  BUILT_IN_INSTRUCTIONS,
  BUILT_IN_SKILLS,
  BUILT_IN_TEMPLATES,
  createSkillDraft,
  exportStudioPackageZip,
  parseSkillManifest,
  readSkillArchiveZip,
} from '../../lib/skills';

const BUILT_IN_CATEGORIES = ['Skills', 'Instructions', 'Templates'] as const;

export const SkillsToolsPanel: React.FC = () => {
  const {
    localSkills,
    upsertLocalSkill,
    deleteLocalSkill,
    trustLocalSkill,
    revokeLocalSkillTrust,
    renderedInstructions,
    showToast,
  } = useAistee();

  const [subView, setSubView] = useState<'local' | 'builtin'>('local');
  const [builtinTab, setBuiltinTab] = useState<(typeof BUILT_IN_CATEGORIES)[number]>('Skills');
  const [exportHost, setExportHost] = useState<'PORTABLE' | 'CLAUDE' | 'CODEX'>('PORTABLE');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [draftName, setDraftName] = useState('');
  const [draftDesc, setDraftDesc] = useState('');
  const [draftActive, setDraftActive] = useState(true);
  const [draftScript, setDraftScript] = useState(
    `// Runs inside sandboxed iframe\nwindow.aisteeCall('current_datetime', {});`,
  );

  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleImportFile = async (file: File) => {
    try {
      if (file.name.toLowerCase().endsWith('.zip')) {
        const { source, scriptSource } = await readSkillArchiveZip(file);
        const res = await upsertLocalSkill(source, scriptSource);
        showToast(res.ok ? `Imported skill archive ${file.name}` : res.error || 'Import failed');
      } else {
        const text = await file.text();
        const res = await upsertLocalSkill(text);
        showToast(res.ok ? `Imported skill ${file.name}` : res.error || 'Invalid SKILL.md');
      }
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Failed to import skill');
    }
  };

  const handleExportZip = async () => {
    const blob = await exportStudioPackageZip(localSkills, exportHost, renderedInstructions);
    downloadBlob(blob, `aistee-studio-${exportHost.toLowerCase()}.zip`, showToast);
  };

  return (
    <div className="space-y-5">
      {/* Header & Active Skills Toggle */}
      <div className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setSubView('local')}
            className={`rounded-lg px-3 py-1.5 text-xs font-semibold ${
              subView === 'local'
                ? 'bg-indigo-600 text-white'
                : 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
            }`}
          >
            Local Skill Library ({localSkills.length})
          </button>
          <button
            type="button"
            onClick={() => setSubView('builtin')}
            className={`rounded-lg px-3 py-1.5 text-xs font-semibold ${
              subView === 'builtin'
                ? 'bg-indigo-600 text-white'
                : 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
            }`}
          >
            Built-In Reference
          </button>
        </div>

        <p className="text-xs text-amber-500">Active script execution disabled pending an isolated capability runtime. Import/export remains available.</p>
      </div>

      {subView === 'local' ? (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => setShowCreateModal(true)}
                className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white"
              >
                <Plus className="h-3.5 w-3.5" />
                Create Skill Draft
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept=".md,.zip"
                className="hidden"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) handleImportFile(file);
                  e.target.value = '';
                }}
              />
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200"
              >
                <Upload className="h-3.5 w-3.5" />
                Import SKILL.md / .zip
              </button>
            </div>

            <div className="flex items-center gap-2">
              <select
                value={exportHost}
                onChange={(e) => setExportHost(e.target.value as 'PORTABLE' | 'CLAUDE' | 'CODEX')}
                className="rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] px-2.5 py-1.5 text-xs text-slate-800 dark:text-slate-200"
              >
                <option value="PORTABLE">Host: Portable (/skills)</option>
                <option value="CLAUDE">Host: Claude (.claude/skills)</option>
                <option value="CODEX">Host: Codex (.agents/skills)</option>
              </select>
              <button
                type="button"
                onClick={handleExportZip}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200"
              >
                <Download className="h-3.5 w-3.5" />
                Export Plugin ZIP
              </button>
            </div>
          </div>

          {localSkills.length === 0 ? (
            <div className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-6 text-center text-xs text-slate-500">
              No local skills installed yet. Create a SKILL.md draft or import a `.md` / `.zip` skill package.
            </div>
          ) : (
            <div className="space-y-3">
              {localSkills.map((skill) => {
                const manifest = parseSkillManifest(skill.source);
                const isActiveSkill = Boolean(manifest.activeDeclaration);
                return (
                  <div
                    key={skill.name}
                    className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-2.5"
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-xs font-semibold text-slate-900 dark:text-slate-100">
                            {skill.name}
                          </span>
                          <span className="text-[11px] text-slate-500">
                            · {isActiveSkill ? `Active (${manifest.activeDeclaration?.runtime})` : 'Instruction skill'} · sha256:{skill.digest.slice(0, 10)}
                          </span>
                        </div>
                        <p className="text-xs text-slate-600 dark:text-slate-300 mt-0.5">{manifest.description}</p>
                        {manifest.activeDeclaration && (
                          <div className="text-[11px] text-slate-400 mt-1">
                            Declared tools: {manifest.activeDeclaration.tools.join(', ') || 'none'}
                          </div>
                        )}
                      </div>

                      <div className="flex flex-wrap items-center gap-1.5">
                        {isActiveSkill && (
                          <>
                            {skill.trusted ? (
                              <button
                                type="button"
                                onClick={() => revokeLocalSkillTrust(skill.name)}
                                className="inline-flex items-center gap-1 rounded-md border border-emerald-500/40 bg-emerald-50 dark:bg-emerald-950/30 px-2.5 py-1 text-xs font-medium text-emerald-700 dark:text-emerald-300"
                              >
                                <ShieldCheck className="h-3.5 w-3.5" />
                                Trusted (Revoke)
                              </button>
                            ) : (
                              <button
                                type="button"
                                onClick={() =>
                                  trustLocalSkill(skill.name, manifest.activeDeclaration?.networkOrigins ?? [])
                                }
                                className="inline-flex items-center gap-1 rounded-md border border-amber-400/50 px-2.5 py-1 text-xs font-medium text-amber-700 dark:text-amber-300"
                              >
                                <ShieldOff className="h-3.5 w-3.5" />
                                Trust SHA-256
                              </button>
                            )}

                          </>
                        )}
                        <button
                          type="button"
                          onClick={() => deleteLocalSkill(skill.name)}
                          className="rounded p-1.5 text-slate-400 hover:text-rose-500"
                          title="Delete local skill"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-4">
          <div className="flex items-center gap-2">
            {BUILT_IN_CATEGORIES.map((cat) => (
              <button
                key={cat}
                type="button"
                onClick={() => setBuiltinTab(cat)}
                className={`rounded-lg border px-3 py-1.5 text-xs font-medium ${
                  builtinTab === cat
                    ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-200'
                    : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          {builtinTab === 'Skills' && (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {BUILT_IN_SKILLS.map((item) => (
                <div
                  key={item.id}
                  className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-2"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">{item.title}</div>
                      <div className="text-[11px] text-indigo-600 dark:text-indigo-400">
                        {item.category} · {item.targetPlatform}
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={async () => {
                        await navigator.clipboard.writeText(item.content);
                        showToast(`Copied ${item.title}`);
                      }}
                      className="rounded border border-slate-200 dark:border-slate-700 p-1.5 text-slate-500 hover:text-indigo-500"
                    >
                      <Copy className="h-3.5 w-3.5" />
                    </button>
                  </div>
                  <p className="text-xs text-slate-600 dark:text-slate-300">{item.description}</p>
                </div>
              ))}
            </div>
          )}

          {builtinTab === 'Instructions' && (
            <div className="space-y-3">
              {BUILT_IN_INSTRUCTIONS.map((item) => (
                <div
                  key={item.id}
                  className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">{item.title}</div>
                      <div className="text-[11px] text-slate-400">{item.category} · {item.summary}</div>
                    </div>
                    <button
                      type="button"
                      onClick={async () => {
                        await navigator.clipboard.writeText(item.promptText);
                        showToast(`Copied "${item.title}"`);
                      }}
                      className="inline-flex items-center gap-1 rounded border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs text-slate-600 dark:text-slate-200"
                    >
                      <Copy className="h-3.5 w-3.5" />
                      Copy
                    </button>
                  </div>
                  <pre className="rounded bg-slate-50 dark:bg-slate-900/70 p-2.5 text-xs text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                    {item.promptText}
                  </pre>
                </div>
              ))}
            </div>
          )}

          {builtinTab === 'Templates' && (
            <div className="space-y-3">
              {BUILT_IN_TEMPLATES.map((tpl) => (
                <div
                  key={tpl.id}
                  className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <div className="font-mono text-xs font-semibold text-slate-900 dark:text-slate-100">
                        {tpl.filename}
                      </div>
                      <div className="text-[11px] text-slate-400">{tpl.type} · {tpl.description}</div>
                    </div>
                    <button
                      type="button"
                      onClick={async () => {
                        await navigator.clipboard.writeText(tpl.code);
                        showToast(`Copied ${tpl.filename}`);
                      }}
                      className="inline-flex items-center gap-1 rounded border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs text-slate-600 dark:text-slate-200"
                    >
                      <Copy className="h-3.5 w-3.5" />
                      Copy
                    </button>
                  </div>
                  <pre className="rounded bg-slate-50 dark:bg-slate-900/70 p-2.5 font-mono text-[11.5px] text-slate-700 dark:text-slate-300 overflow-x-auto">
                    {tpl.code}
                  </pre>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Create Local Skill Draft Modal */}
      {showCreateModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setShowCreateModal(false)}
        >
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const source = createSkillDraft(draftName, draftDesc, draftActive);
              const res = await upsertLocalSkill(source, draftActive ? draftScript : undefined);
              if (res.ok) {
                setShowCreateModal(false);
                setDraftName('');
                setDraftDesc('');
                showToast('Created local skill draft');
              } else {
                showToast(res.error || 'Failed to create skill');
              }
            }}
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-lg rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700">
              <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                Create Local Skill Draft
              </h3>
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="rounded p-1 text-slate-400"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="p-4 space-y-3">
              <input
                type="text"
                required
                value={draftName}
                onChange={(e) => setDraftName(e.target.value)}
                placeholder="kebab-case-skill-name"
                className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100"
              />
              <input
                type="text"
                required
                value={draftDesc}
                onChange={(e) => setDraftDesc(e.target.value)}
                placeholder="Short description of skill purpose…"
                className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
              />
              <label className="flex items-center gap-2 text-xs text-slate-700 dark:text-slate-200">
                <input
                  type="checkbox"
                  checked={draftActive}
                  onChange={(e) => setDraftActive(e.target.checked)}
                  className="h-4 w-4 accent-indigo-600"
                />
                Include Active Skill runtime declaration (`webview-v1`)
              </label>
              {draftActive && (
                <textarea
                  rows={4}
                  value={draftScript}
                  onChange={(e) => setDraftScript(e.target.value)}
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 p-2.5 font-mono text-xs text-slate-900 dark:text-slate-100"
                />
              )}
            </div>
            <div className="flex justify-end gap-2 px-4 py-3 border-t border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50">
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3.5 py-1.5 text-xs font-semibold text-white"
              >
                Create Skill
              </button>
            </div>
          </form>
        </div>
      )}

    </div>
  );
};
