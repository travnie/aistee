import React, { useState } from 'react';
import {
  Clock,
  Cpu,
  Lock,
  Server,
  Shield,
  Terminal,
  Trash2,
  Wrench,
} from 'lucide-react';
import { StudioToolsSubTab, useAistee } from '../context/AisteeContext';
import { SkillsToolsPanel } from './tools/SkillsToolsPanel';
import { BenchesToolsPanel } from './tools/BenchesToolsPanel';
import { AsyncJobsPanel, ClaudeCodeHandoffPanel, McpDiscoveryPanel } from './tools/McpAndJobsPanel';

const SUB_TABS: Array<{ id: StudioToolsSubTab; label: string; icon: React.ReactNode }> = [
  { id: 'SKILLS', label: 'Skills', icon: <Cpu className="h-3.5 w-3.5" /> },
  { id: 'BENCHES', label: 'Benches', icon: <Wrench className="h-3.5 w-3.5" /> },
  { id: 'AGENTS', label: 'Agents', icon: <Terminal className="h-3.5 w-3.5" /> },
  { id: 'MCP', label: 'MCP', icon: <Server className="h-3.5 w-3.5" /> },
  { id: 'JOBS', label: 'Jobs', icon: <Clock className="h-3.5 w-3.5" /> },
  { id: 'PRIVACY', label: 'Privacy', icon: <Shield className="h-3.5 w-3.5" /> },
];

export const ToolsHubScreen: React.FC = () => {
  const {
    toolsSubTab,
    setToolsSubTab,
    privacy,
    updatePrivacy,
    setAppLockPasscode,
    lockAppNow,
    wipeAllLocalData,
    showToast,
  } = useAistee();

  const [passcodeInput, setPasscodeInput] = useState('');
  const [confirmWipe, setConfirmWipe] = useState(false);

  return (
    <div className="h-full flex flex-col min-h-0 bg-slate-50 dark:bg-[#0F172A]">
      <header className="px-4 py-2.5 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/80 flex items-center justify-between gap-3 overflow-x-auto">
        <div className="flex items-center gap-1.5">
          {SUB_TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setToolsSubTab(t.id)}
              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold whitespace-nowrap shrink-0 transition-colors ${
                toolsSubTab === t.id
                  ? 'bg-indigo-600 text-white'
                  : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
              }`}
            >
              {t.icon}
              {t.label}
            </button>
          ))}
        </div>
      </header>

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="max-w-5xl mx-auto">
          {toolsSubTab === 'SKILLS' && <SkillsToolsPanel />}
          {toolsSubTab === 'BENCHES' && <BenchesToolsPanel />}
          {toolsSubTab === 'AGENTS' && <ClaudeCodeHandoffPanel />}
          {toolsSubTab === 'MCP' && <McpDiscoveryPanel />}
          {toolsSubTab === 'JOBS' && <AsyncJobsPanel />}

          {toolsSubTab === 'PRIVACY' && (
            <div className="space-y-4">
              <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-4">
                <div className="flex items-center gap-2.5">
                  <Shield className="h-5 w-5 text-indigo-500" />
                  <div>
                    <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                      Local Privacy & Session Controls
                    </h2>
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                      Zero telemetry. Conversations, keys, drafts, and skills never leave your browser except on explicit provider calls.
                    </p>
                  </div>
                </div>

                <div className="space-y-2.5">
                  <label className="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700 px-4 py-3 cursor-pointer">
                    <div>
                      <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                        Screen Privacy Shield
                      </div>
                      <div className="text-[11px] text-slate-500 dark:text-slate-400">
                        Masks staged Web AI drafts by default until explicitly revealed.
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={privacy.screenPrivacy}
                      onChange={(e) => updatePrivacy({ screenPrivacy: e.target.checked })}
                      className="h-4 w-4 accent-indigo-600"
                    />
                  </label>

                  <label className="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700 px-4 py-3 cursor-pointer">
                    <div>
                      <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                        Quick Privacy Mode
                      </div>
                      <div className="text-[11px] text-slate-500 dark:text-slate-400">
                        Hides preview snippets in task switchers and enables fast lock shortcut.
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={privacy.quickPrivacy}
                      onChange={(e) => updatePrivacy({ quickPrivacy: e.target.checked })}
                      className="h-4 w-4 accent-indigo-600"
                    />
                  </label>
                </div>

                <div className="rounded-lg border border-slate-200 dark:border-slate-700 p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Lock className="h-4 w-4 text-indigo-500" />
                      <span className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                        Passcode App Lock ({privacy.appLockEnabled ? 'Enabled' : 'Off'})
                      </span>
                    </div>
                    {privacy.appLockEnabled && (
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={lockAppNow}
                          className="rounded-md bg-indigo-600 px-2.5 py-1 text-xs font-semibold text-white"
                        >
                          Lock Now
                        </button>
                        <button
                          type="button"
                          onClick={async () => {
                            await setAppLockPasscode(null);
                            showToast('App Lock disabled');
                          }}
                          className="rounded-md border border-slate-300 dark:border-slate-600 px-2.5 py-1 text-xs"
                        >
                          Disable
                        </button>
                      </div>
                    )}
                  </div>

                  <form
                    onSubmit={async (e) => {
                      e.preventDefault();
                      if (passcodeInput.trim().length < 4) {
                        showToast('Passcode must be at least 4 characters');
                        return;
                      }
                      await setAppLockPasscode(passcodeInput.trim());
                      setPasscodeInput('');
                      showToast('App Lock passcode saved');
                    }}
                    className="flex items-center gap-2"
                  >
                    <input
                      type="password"
                      value={passcodeInput}
                      onChange={(e) => setPasscodeInput(e.target.value)}
                      placeholder="Set or update unlock passcode (min 4 chars)…"
                      className="flex-1 rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                    />
                    <button
                      type="submit"
                      className="rounded-lg bg-slate-800 dark:bg-slate-700 px-3 py-1.5 text-xs font-semibold text-white"
                    >
                      Save Passcode
                    </button>
                  </form>
                </div>
              </section>

              {/* Delete Local Data */}
              <section className="rounded-xl border border-rose-300/70 dark:border-rose-800/60 bg-rose-50/40 dark:bg-rose-950/20 p-5 space-y-3">
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <h3 className="font-display text-sm font-semibold text-rose-700 dark:text-rose-300">
                      Delete All Local Data
                    </h3>
                    <p className="text-xs text-slate-600 dark:text-slate-300 mt-0.5">
                      Permanently erases all stored API keys, chats, Project Library files, Web drafts/bookmarks, local skills, MCP servers, and jobs.
                    </p>
                  </div>
                  {!confirmWipe ? (
                    <button
                      type="button"
                      onClick={() => setConfirmWipe(true)}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-rose-500/60 px-3 py-1.5 text-xs font-semibold text-rose-600 dark:text-rose-300 hover:bg-rose-100 dark:hover:bg-rose-950/50 whitespace-nowrap shrink-0"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                      Delete Local Data…
                    </button>
                  ) : (
                    <div className="flex items-center gap-2 shrink-0">
                      <button
                        type="button"
                        onClick={() => setConfirmWipe(false)}
                        className="rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-1.5 text-xs"
                      >
                        Cancel
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          wipeAllLocalData();
                          setConfirmWipe(false);
                        }}
                        className="rounded-lg bg-rose-600 hover:bg-rose-500 px-3 py-1.5 text-xs font-semibold text-white"
                      >
                        Confirm Erase Everything
                      </button>
                    </div>
                  )}
                </div>
              </section>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
