import React, { useState } from 'react';
import { FlaskConical, Send, Sparkles, Trash2 } from 'lucide-react';
import { useAistee } from '../context/AisteeContext';
import { simulatePlaygroundReply } from '../lib/profile';
import { MarkdownContent } from './MarkdownContent';

interface SimTurn {
  id: string;
  prompt: string;
  reply: string;
  notes: string[];
}

const SAMPLE_PROMPTS = [
  'Explain what a Git submodule is for sharing .ai profiles.',
  'Review our PR for silent fallbacks and URL validation gaps.',
  'Should we build a thin ChatGPT wrapper startup?',
  'How do YAML overlays merge over Base Default Profile?',
];

export const PlaygroundScreen: React.FC = () => {
  const { composedProfile, showToast } = useAistee();
  const [input, setInput] = useState('');
  const [turns, setTurns] = useState<SimTurn[]>([]);

  const runSimulation = (promptText: string) => {
    const clean = promptText.trim();
    if (!clean) return;
    const result = simulatePlaygroundReply(clean, composedProfile);
    setTurns((prev) => [
      ...prev,
      { id: `${Date.now()}_${prev.length}`, prompt: clean, reply: result.text, notes: result.notes },
    ]);
    setInput('');
  };

  return (
    <div className="h-full flex flex-col min-h-0 bg-slate-50 dark:bg-[#0F172A]">
      <header className="px-4 py-3 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/80 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <FlaskConical className="h-4 w-4 text-violet-500" />
          <div>
            <h1 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
              Offline Profile Playground ({composedProfile.id})
            </h1>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Deterministic local simulator — preview tone, verbosity, verification, and traits without network calls.
            </p>
          </div>
        </div>
        {turns.length > 0 && (
          <button
            type="button"
            onClick={() => setTurns([])}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <Trash2 className="h-3.5 w-3.5" />
            Clear
          </button>
        )}
      </header>

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="max-w-3xl mx-auto space-y-4">
          <div className="flex flex-wrap gap-2">
            {SAMPLE_PROMPTS.map((sp, i) => (
              <button
                key={i}
                type="button"
                onClick={() => runSimulation(sp)}
                className="rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] px-3 py-1.5 text-xs text-slate-700 dark:text-slate-200 hover:border-indigo-400/60"
              >
                {sp}
              </button>
            ))}
          </div>

          {turns.length === 0 ? (
            <div className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-6 text-center space-y-2">
              <Sparkles className="h-6 w-6 text-indigo-500 mx-auto" />
              <div className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                Test Your Composed Style
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Click a sample prompt above or enter your own message below to inspect how active modifiers shape responses.
              </p>
            </div>
          ) : (
            turns.map((t) => (
              <div key={t.id} className="space-y-2.5">
                <div className="rounded-xl border border-indigo-200/80 dark:border-indigo-800/60 bg-indigo-50/40 dark:bg-indigo-950/20 p-3.5 text-sm text-slate-900 dark:text-slate-100">
                  {t.prompt}
                </div>
                <div className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-3">
                  <MarkdownContent content={t.reply} onCopySuccess={(l) => showToast(l)} />
                  <div className="border-t border-slate-200 dark:border-slate-700/70 pt-2.5 text-[11px] text-slate-500 dark:text-slate-400">
                    Active traits applied: {t.notes.join(' · ')}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          runSimulation(input);
        }}
        className="border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B]/90 px-4 py-3"
      >
        <div className="max-w-3xl mx-auto flex items-center gap-2">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Enter a test prompt to simulate active .ai profile…"
            className="flex-1 rounded-xl border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3.5 py-2 text-sm text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none"
          />
          <button
            type="submit"
            disabled={!input.trim()}
            className="inline-flex items-center gap-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 px-4 py-2 text-xs font-semibold text-white"
          >
            <Send className="h-3.5 w-3.5" />
            Simulate
          </button>
        </div>
      </form>
    </div>
  );
};
