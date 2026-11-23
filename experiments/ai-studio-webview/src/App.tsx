import React, { useState } from 'react';
import {
  BookOpen,
  Code2,
  FlaskConical,
  Globe,
  KeyRound,
  Lock,
  MessageSquareMore,
  Moon,
  Sliders,
  Sparkles,
  Sun,
  Wrench,
} from 'lucide-react';
import { AisteeProvider, useAistee } from './context/AisteeContext';
import { NavigationTab } from './lib/models';
import { WebChatScreen } from './components/WebChatScreen';
import { ChatScreen } from './components/ChatScreen';
import { StudioScreen } from './components/StudioScreen';
import { InstructionsScreen } from './components/InstructionsScreen';
import { YamlAndMarkdownScreen } from './components/YamlAndMarkdownScreen';
import { PlaygroundScreen } from './components/PlaygroundScreen';
import { ToolsHubScreen } from './components/ToolsHubScreen';
import { ApiKeysDialog } from './components/ApiKeysDialog';

const STUDIO_TABS: NavigationTab[] = ['STUDIO', 'INSTRUCTIONS', 'YAML', 'PLAYGROUND', 'SKILLS'];

const STUDIO_SUB_NAV: Array<{ id: NavigationTab; label: string; icon: React.ReactNode }> = [
  { id: 'STUDIO', label: 'Profile', icon: <Sliders className="h-3.5 w-3.5" /> },
  { id: 'INSTRUCTIONS', label: 'Instructions', icon: <BookOpen className="h-3.5 w-3.5" /> },
  { id: 'YAML', label: 'YAML & Docbench', icon: <Code2 className="h-3.5 w-3.5" /> },
  { id: 'PLAYGROUND', label: 'Playground', icon: <FlaskConical className="h-3.5 w-3.5" /> },
  { id: 'SKILLS', label: 'Tools Hub', icon: <Wrench className="h-3.5 w-3.5" /> },
];

const AisteeShell: React.FC = () => {
  const {
    theme,
    toggleTheme,
    activeTab,
    setActiveTab,
    setIsApiKeysOpen,
    privacy,
    isAppLocked,
    unlockApp,
    lockAppNow,
    toastMessage,
  } = useAistee();

  const [unlockInput, setUnlockInput] = useState('');
  const [unlockError, setUnlockError] = useState(false);
  const isStudioZone = STUDIO_TABS.includes(activeTab);

  if (isAppLocked) {
    return (
      <div className="h-screen w-screen flex items-center justify-center bg-slate-50 dark:bg-[#0F172A] p-4">
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await unlockApp(unlockInput);
            if (ok) {
              setUnlockInput('');
              setUnlockError(false);
            } else {
              setUnlockError(true);
            }
          }}
          className="w-full max-w-sm rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] p-6 shadow-xl space-y-4"
        >
          <div className="flex items-center gap-2.5">
            <Lock className="h-5 w-5 text-indigo-500" />
            <div>
              <h1 className="font-display text-base font-semibold text-slate-900 dark:text-slate-100">
                Aistee Locked
              </h1>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Enter your local passcode to resume workbench access.
              </p>
            </div>
          </div>
          <input
            type="password"
            autoFocus
            value={unlockInput}
            onChange={(e) => {
              setUnlockInput(e.target.value);
              setUnlockError(false);
            }}
            placeholder="Passcode…"
            className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-2 text-sm text-slate-900 dark:text-slate-100 focus:border-indigo-500 focus:outline-none"
          />
          {unlockError && <p className="text-xs text-rose-500">Incorrect passcode.</p>}
          <button
            type="submit"
            className="w-full rounded-lg bg-indigo-600 hover:bg-indigo-500 py-2 text-xs font-semibold text-white"
          >
            Unlock Aistee
          </button>
        </form>
      </div>
    );
  }

  const primaryZones = [
    { id: 'WEB_CHATS' as const, label: 'Web AI', icon: <Globe className="h-4 w-4" />, active: activeTab === 'WEB_CHATS' },
    {
      id: 'COMPARE_HUB' as const,
      label: 'Compare',
      icon: <MessageSquareMore className="h-4 w-4" />,
      active: activeTab === 'COMPARE_HUB',
    },
    { id: 'STUDIO' as const, label: 'Studio', icon: <Sparkles className="h-4 w-4" />, active: isStudioZone },
  ];

  return (
    <div className="h-screen w-screen flex flex-col md:flex-row overflow-hidden bg-slate-50 dark:bg-[#0F172A] text-slate-900 dark:text-[#F1F5F9]">
      {/* Desktop Navigation Rail */}
      <nav className="hidden md:flex w-16 shrink-0 flex-col items-center justify-between border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B] py-3">
        <div className="flex flex-col items-center gap-3 w-full px-1.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-600 text-white font-display font-bold text-sm tracking-tight select-none">
            Ai
          </div>
          <div className="w-8 border-t border-slate-200 dark:border-slate-700/80 my-0.5" />
          {primaryZones.map((zone) => (
            <button
              key={zone.id}
              type="button"
              onClick={() => setActiveTab(zone.id)}
              className={`w-full flex flex-col items-center gap-1 rounded-lg py-2 px-1 text-[11px] font-medium transition-colors ${
                zone.active
                  ? 'bg-indigo-50 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-200'
                  : 'text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800'
              }`}
            >
              {zone.icon}
              <span className="whitespace-nowrap">{zone.label}</span>
            </button>
          ))}
        </div>

        <div className="flex flex-col items-center gap-2 w-full px-1.5">
          <button
            type="button"
            onClick={() => setIsApiKeysOpen(true)}
            className="w-full flex flex-col items-center gap-1 rounded-lg py-2 text-[10px] font-medium text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
            title="Provider API Keys"
          >
            <KeyRound className="h-4 w-4" />
            <span>Keys</span>
          </button>

          {privacy.appLockEnabled && (
            <button
              type="button"
              onClick={lockAppNow}
              className="w-full flex flex-col items-center gap-1 rounded-lg py-2 text-[10px] font-medium text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              title="Lock Aistee"
            >
              <Lock className="h-4 w-4" />
              <span>Lock</span>
            </button>
          )}

          <button
            type="button"
            onClick={toggleTheme}
            className="w-full flex flex-col items-center gap-1 rounded-lg py-2 text-[10px] font-medium text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
            title="Toggle color theme"
          >
            {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            <span>{theme === 'dark' ? 'Light' : 'Dark'}</span>
          </button>
        </div>
      </nav>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 min-h-0">
        {/* Studio Sub-Navigation Strip */}
        {isStudioZone && (
          <div className="shrink-0 flex items-center justify-between gap-2 px-4 py-2 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B] overflow-x-auto">
            <div className="flex items-center gap-1.5">
              {STUDIO_SUB_NAV.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveTab(item.id)}
                  className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold whitespace-nowrap shrink-0 transition-colors ${
                    activeTab === item.id
                      ? 'bg-indigo-600 text-white'
                      : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
                  }`}
                >
                  {item.icon}
                  {item.label}
                </button>
              ))}
            </div>
            <div className="hidden sm:flex items-center gap-2 md:hidden">
              <button
                type="button"
                onClick={toggleTheme}
                className="rounded-md p-1.5 border border-slate-200 dark:border-slate-700"
              >
                {theme === 'dark' ? <Sun className="h-3.5 w-3.5" /> : <Moon className="h-3.5 w-3.5" />}
              </button>
            </div>
          </div>
        )}

        <main className="flex-1 min-h-0">
          {activeTab === 'WEB_CHATS' && <WebChatScreen />}
          {activeTab === 'COMPARE_HUB' && <ChatScreen />}
          {activeTab === 'STUDIO' && <StudioScreen />}
          {activeTab === 'INSTRUCTIONS' && <InstructionsScreen />}
          {activeTab === 'YAML' && <YamlAndMarkdownScreen />}
          {activeTab === 'PLAYGROUND' && <PlaygroundScreen />}
          {activeTab === 'SKILLS' && <ToolsHubScreen />}
        </main>

        {/* Mobile Bottom Navigation */}
        <nav className="flex md:hidden items-center justify-around border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-[#1E293B] py-1.5 px-2 shrink-0">
          {primaryZones.map((zone) => (
            <button
              key={zone.id}
              type="button"
              onClick={() => setActiveTab(zone.id)}
              className={`flex flex-col items-center gap-0.5 rounded-lg px-4 py-1 text-[11px] font-medium ${
                zone.active ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-500 dark:text-slate-400'
              }`}
            >
              {zone.icon}
              <span>{zone.label}</span>
            </button>
          ))}
          <button
            type="button"
            onClick={() => setIsApiKeysOpen(true)}
            className="flex flex-col items-center gap-0.5 rounded-lg px-3 py-1 text-[11px] font-medium text-slate-500 dark:text-slate-400"
          >
            <KeyRound className="h-4 w-4" />
            <span>Keys</span>
          </button>
        </nav>
      </div>

      <ApiKeysDialog />

      {/* Transient Toast */}
      {toastMessage && (
        <div className="fixed bottom-16 md:bottom-5 right-5 z-50 rounded-lg border border-slate-700 bg-slate-900 text-slate-100 px-3.5 py-2 text-xs font-medium shadow-xl">
          {toastMessage}
        </div>
      )}
    </div>
  );
};

export function App() {
  return (
    <AisteeProvider>
      <AisteeShell />
    </AisteeProvider>
  );
}

export default App;
