import React, { useEffect, useState } from 'react';
import { Eye, EyeOff, KeyRound, ShieldCheck, Trash2, X } from 'lucide-react';
import {
  ApiKeyConfig,
  EMPTY_API_KEYS,
  normalizeCustomEndpointBaseUrl,
} from '../lib/models';
import { useAistee } from '../context/AisteeContext';

export const ApiKeysDialog: React.FC = () => {
  const { isApiKeysOpen, setIsApiKeysOpen, apiKeys, updateApiKeys, showToast } = useAistee();
  const [draft, setDraft] = useState<ApiKeyConfig>(apiKeys);
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (isApiKeysOpen) {
      setDraft(apiKeys);
      setRevealed({});
    }
  }, [isApiKeysOpen, apiKeys]);

  if (!isApiKeysOpen) return null;

  const customUrlTrimmed = draft.customEndpointUrl.trim();
  const customUrlInvalid =
    customUrlTrimmed.length > 0 && normalizeCustomEndpointBaseUrl(customUrlTrimmed) === null;

  const toggleReveal = (field: string) =>
    setRevealed((prev) => ({ ...prev, [field]: !prev[field] }));

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    if (customUrlInvalid) {
      showToast('Custom endpoint must be a valid public https:// base URL.');
      return;
    }
    const normalizedCustom = customUrlTrimmed
      ? normalizeCustomEndpointBaseUrl(customUrlTrimmed) ?? ''
      : '';
    updateApiKeys({
      ...draft,
      customEndpointUrl: normalizedCustom,
    });
    setIsApiKeysOpen(false);
    showToast('API keys updated locally');
  };

  const fields: Array<{ key: keyof ApiKeyConfig; label: string; hint: string }> = [
    { key: 'claudeKey', label: 'Anthropic Claude API Key', hint: 'sk-ant-...' },
    { key: 'openAiKey', label: 'OpenAI API Key', hint: 'sk-...' },
    { key: 'geminiKey', label: 'Google Gemini API Key', hint: 'AIza...' },
    { key: 'deepseekKey', label: 'DeepSeek AI API Key', hint: 'sk-...' },
    { key: 'kimiKey', label: 'Moonshot Kimi AI API Key', hint: 'sk-...' },
    { key: 'openRouterKey', label: 'OpenRouter API Key', hint: 'sk-or-...' },
    { key: 'aiHubMixKey', label: 'AIHubMix API Key', hint: 'sk-...' },
    { key: 'vercelAiGatewayKey', label: 'Vercel AI Gateway Token', hint: 'vck_...' },
  ];

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
      onClick={() => setIsApiKeysOpen(false)}
    >
      <form
        onSubmit={handleSave}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-2xl max-h-[88vh] flex flex-col rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
      >
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-slate-200 dark:border-slate-700">
          <div className="flex items-center gap-2.5">
            <KeyRound className="h-4 w-4 text-indigo-500 dark:text-indigo-400" />
            <div>
              <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                Provider API Keys & Custom Endpoint
              </h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Stored only in your browser; sent ephemerally to the stateless proxy per turn.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => setIsApiKeysOpen(false)}
            className="rounded-md p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
            aria-label="Close API keys dialog"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="overflow-y-auto p-5 space-y-4">
          <div className="flex items-start gap-2.5 rounded-lg border border-indigo-200/70 dark:border-indigo-900/60 bg-indigo-50/50 dark:bg-indigo-950/25 px-3.5 py-2.5 text-xs text-slate-700 dark:text-slate-300">
            <ShieldCheck className="h-4 w-4 shrink-0 text-indigo-500 mt-0.5" />
            <span>
              Aistee never silently switches between your account chats and metered API keys, or across providers if a key is missing.
            </span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
            {fields.map((f) => {
              const isShown = Boolean(revealed[f.key]);
              return (
                <label key={f.key} className="block space-y-1">
                  <span className="text-xs font-medium text-slate-700 dark:text-slate-300">{f.label}</span>
                  <div className="relative">
                    <input
                      type={isShown ? 'text' : 'password'}
                      autoComplete="off"
                      value={draft[f.key]}
                      onChange={(e) => setDraft((prev) => ({ ...prev, [f.key]: e.target.value }))}
                      placeholder={f.hint}
                      className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/80 px-3 py-1.5 pr-8 font-mono text-xs text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none"
                    />
                    <button
                      type="button"
                      onClick={() => toggleReveal(f.key)}
                      className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                      aria-label={isShown ? 'Hide key' : 'Show key'}
                    >
                      {isShown ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                    </button>
                  </div>
                </label>
              );
            })}
          </div>

          <div className="pt-2 border-t border-slate-200 dark:border-slate-700 space-y-3">
            <div className="text-xs font-semibold text-slate-800 dark:text-slate-200">
              Custom OpenAI-Compatible HTTPS Endpoint
            </div>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              <label className="md:col-span-2 block space-y-1">
                <span className="text-xs text-slate-600 dark:text-slate-400">HTTPS Base URL</span>
                <input
                  type="url"
                  value={draft.customEndpointUrl}
                  onChange={(e) => setDraft((prev) => ({ ...prev, customEndpointUrl: e.target.value }))}
                  placeholder="https://api.example.com/v1"
                  className={`w-full rounded-lg border px-3 py-1.5 font-mono text-xs bg-slate-50 dark:bg-slate-900/80 text-slate-900 dark:text-slate-100 focus:outline-none ${
                    customUrlInvalid
                      ? 'border-rose-500'
                      : 'border-slate-300 dark:border-slate-600 focus:border-indigo-500'
                  }`}
                />
                {customUrlInvalid && (
                  <p className="text-[11px] text-rose-500">Must be a clean https:// URL without credentials or query params.</p>
                )}
              </label>

              <label className="block space-y-1">
                <span className="text-xs text-slate-600 dark:text-slate-400">Default Model ID</span>
                <input
                  type="text"
                  value={draft.customEndpointModel}
                  onChange={(e) => setDraft((prev) => ({ ...prev, customEndpointModel: e.target.value }))}
                  placeholder="llama-3.3-70b"
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/80 px-3 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none"
                />
              </label>
            </div>
            <label className="block space-y-1">
              <span className="text-xs text-slate-600 dark:text-slate-400">Optional Bearer Key</span>
              <input
                type={revealed.customEndpointKey ? 'text' : 'password'}
                value={draft.customEndpointKey}
                onChange={(e) => setDraft((prev) => ({ ...prev, customEndpointKey: e.target.value }))}
                placeholder="Optional bearer token"
                className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/80 px-3 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none"
              />
            </label>
          </div>
        </div>

        <div className="flex items-center justify-between px-5 py-3 border-t border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60">
          <button
            type="button"
            onClick={() => setDraft(EMPTY_API_KEYS)}
            className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium text-rose-600 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-950/40 whitespace-nowrap shrink-0"
          >
            <Trash2 className="h-3.5 w-3.5" />
            Clear all fields
          </button>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setIsApiKeysOpen(false)}
              className="rounded-lg border border-slate-300 dark:border-slate-600 px-3.5 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="rounded-lg bg-indigo-600 hover:bg-indigo-500 px-4 py-1.5 text-xs font-semibold text-white whitespace-nowrap shrink-0"
            >
              Save locally
            </button>
          </div>
        </div>
      </form>
    </div>
  );
};
