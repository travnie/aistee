import React from 'react';
import { Copy, Download, Languages } from 'lucide-react';
import { useAistee } from '../context/AisteeContext';
import { countO200kTokens } from '../lib/docbench';

export const InstructionsScreen: React.FC = () => {
  const { composedProfile, language, setLanguage, renderedInstructions, showToast } = useAistee();
  const lines = renderedInstructions
    .trim()
    .split('\n')
    .filter(Boolean)
    .map((l) => l.replace(/^- /, ''));

  const tokenCount = countO200kTokens(renderedInstructions);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(renderedInstructions);
    showToast('Copied rendered instructions.md');
  };

  const handleDownload = () => {
    const blob = new Blob([renderedInstructions], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `instructions-${composedProfile.id}.md`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="h-full overflow-y-auto bg-slate-50 dark:bg-[#0F172A] p-4 md:p-6">
      <div className="max-w-4xl mx-auto space-y-5">
        <header className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 flex flex-wrap items-center justify-between gap-4">
          <div className="space-y-1">
            <div className="text-xs font-medium text-indigo-600 dark:text-indigo-400">
              Deterministic Instruction Renderer · <span className="tabular-nums">{lines.length} rules · {tokenCount} tokens (o200k)</span>
            </div>
            <h1 className="font-display text-lg font-semibold text-slate-900 dark:text-slate-100">
              Rendered System Instructions ({composedProfile.id})
            </h1>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex items-center rounded-lg border border-slate-200 dark:border-slate-700 p-0.5 bg-slate-50 dark:bg-slate-900">
              <Languages className="h-3.5 w-3.5 text-slate-400 mx-2" />
              {(['auto', 'en', 'pl'] as const).map((langOpt) => (
                <button
                  key={langOpt}
                  type="button"
                  onClick={() => setLanguage(langOpt)}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium uppercase whitespace-nowrap ${
                    language === langOpt
                      ? 'bg-indigo-600 text-white'
                      : 'text-slate-600 dark:text-slate-300 hover:bg-slate-200/60 dark:hover:bg-slate-800'
                  }`}
                >
                  {langOpt}
                </button>
              ))}
            </div>

            <button
              type="button"
              onClick={handleCopy}
              className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white whitespace-nowrap shrink-0"
            >
              <Copy className="h-3.5 w-3.5" />
              Copy instructions
            </button>
            <button
              type="button"
              onClick={handleDownload}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
            >
              <Download className="h-3.5 w-3.5" />
              Download .md
            </button>
          </div>
        </header>

        {/* Numbered Rule Cards */}
        <section className="space-y-2">
          {lines.map((rule, idx) => {
            const roleTag =
              idx === 0
                ? 'Base Voice'
                : idx === 1
                  ? 'Core Integrity'
                  : idx === lines.length - 1
                    ? 'Safety Boundary'
                    : null;
            return (
              <div
                key={idx}
                className="flex items-start gap-3 rounded-lg border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] px-4 py-3"
              >
                <span className="font-mono text-xs text-slate-400 dark:text-slate-500 tabular-nums w-6 shrink-0 pt-0.5">
                  {String(idx + 1).padStart(2, '0')}
                </span>
                <div className="flex-1 text-sm text-slate-800 dark:text-slate-200">
                  {roleTag && (
                    <div className="text-[11px] font-semibold uppercase tracking-wider text-indigo-600 dark:text-indigo-400 mb-0.5">
                      {roleTag}
                    </div>
                  )}
                  {rule}
                </div>
              </div>
            );
          })}
        </section>

        {/* Raw Markdown Block */}
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] overflow-hidden">
          <div className="px-4 py-2.5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60 text-xs font-semibold text-slate-600 dark:text-slate-300">
            Raw Rendered Markdown
          </div>
          <pre className="overflow-x-auto p-4 font-mono text-xs leading-relaxed text-slate-800 dark:text-slate-200">
            {renderedInstructions}
          </pre>
        </section>
      </div>
    </div>
  );
};
