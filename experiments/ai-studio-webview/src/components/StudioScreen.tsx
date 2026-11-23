import React, { useState } from 'react';
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  Code2,
  FlaskConical,
  Plus,
  RotateCcw,
  Sliders,
  Sparkles,
  Trash2,
  Wrench,
  X,
} from 'lucide-react';
import { useAistee } from '../context/AisteeContext';
import {
  AdaptationConfig,
  CollaborationConfig,
  KnowledgeConfig,
  OutputConfig,
  PersonalityBase,
  ProfileOverlay,
  validateProfile,
} from '../lib/profile';
import { countO200kTokens } from '../lib/docbench';

const PERSONALITY_BASES: Array<{ id: PersonalityBase; label: string }> = [
  { id: 'default', label: 'Default' },
  { id: 'friendly', label: 'Friendly' },
  { id: 'professional', label: 'Professional' },
  { id: 'honest', label: 'Honest' },
  { id: 'concise', label: 'Concise' },
  { id: 'cynical', label: 'Cynical' },
  { id: 'whimsical', label: 'Whimsical' },
];

const MODIFIER_KEYS = [
  'honest',
  'concise',
  'warm',
  'technical',
  'educational',
  'critical',
  'headingsAndLists',
  'quickReplies',
  'emoji',
  'whimsical',
  'cynical',
  'enthusiastic',
];

export const StudioScreen: React.FC = () => {
  const {
    baseProfile,
    setBaseProfile,
    selectedOverlayId,
    setSelectedOverlayId,
    customOverlays,
    saveCustomOverlay,
    deleteCustomOverlay,
    allOverlays,
    activeOverlay,
    composedProfile,
    renderedInstructions,
    resetStudioProfile,
    setActiveTab,
    setToolsSubTab,
    showToast,
  } = useAistee();

  const [openSection, setOpenSection] = useState<'personality' | 'collaboration' | 'output' | null>(
    'personality',
  );
  const [showOverlayModal, setShowOverlayModal] = useState(false);
  const [overlayName, setOverlayName] = useState('');
  const [overlayDesc, setOverlayDesc] = useState('');
  const [overlayNote, setOverlayNote] = useState('');

  const validation = validateProfile(composedProfile);
  const tokenCount = countO200kTokens(renderedInstructions);

  const handleSaveOverlay = (e: React.FormEvent) => {
    e.preventDefault();
    const id =
      overlayName
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9-]+/g, '-')
        .replace(/^-|-$/g, '') || `custom-${Date.now().toString(36)}`;
    const newOverlay: ProfileOverlay = {
      id,
      name: overlayName.trim() || 'Custom Overlay',
      description: overlayDesc.trim() || 'Captured from Studio workspace',
      locale: composedProfile.locale,
      personalityBase: composedProfile.personality.base,
      personalityIntensity: composedProfile.personality.intensity,
      modifierOverrides: { ...composedProfile.personality.modifiers },
      adaptationOverrides: { ...composedProfile.personality.adaptation },
      preamble: composedProfile.collaboration.preamble,
      initiative: composedProfile.collaboration.initiative,
      verification: composedProfile.collaboration.verification,
      questionPolicy: composedProfile.collaboration.questionPolicy,
      assumptionPolicy: composedProfile.collaboration.assumptionPolicy,
      collabBoolOverrides: {
        answerFirst: composedProfile.collaboration.answerFirst,
        plainChatIsDefault: composedProfile.collaboration.plainChatIsDefault,
        respectExplicitTurnInstructions: composedProfile.collaboration.respectExplicitTurnInstructions,
        avoidRoutinePraise: composedProfile.collaboration.avoidRoutinePraise,
        avoidRoutineFollowUpOffer: composedProfile.collaboration.avoidRoutineFollowUpOffer,
        announceOnlyMaterialActions: composedProfile.collaboration.announceOnlyMaterialActions,
        reportPartialFailures: composedProfile.collaboration.reportPartialFailures,
        preferResultOverProcess: composedProfile.collaboration.preferResultOverProcess,
      },
      knowledgeOverrides: { ...composedProfile.knowledge },
      defaultFormat: composedProfile.output.defaultFormat,
      maxHeadingDepth: composedProfile.output.maxHeadingDepth,
      preferShortParagraphs: composedProfile.output.preferShortParagraphs,
      tables: composedProfile.output.tables,
      codeExamples: composedProfile.output.codeExamples,
      citations: composedProfile.output.citations,
      customNote: overlayNote.trim() || undefined,
    };
    saveCustomOverlay(newOverlay);
    setOverlayName('');
    setOverlayDesc('');
    setOverlayNote('');
    setShowOverlayModal(false);
  };

  const setModifier = (key: string, lvl: number) => {
    setBaseProfile((prev) => ({
      ...prev,
      personality: {
        ...prev.personality,
        modifiers: { ...prev.personality.modifiers, [key]: lvl },
      },
    }));
  };

  return (
    <div className="h-full overflow-y-auto bg-slate-50 dark:bg-[#0F172A] p-4 md:p-6">
      <div className="max-w-5xl mx-auto space-y-5">
        {/* Top Studio Summary & Quick Links */}
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-4">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="space-y-1">
              <div className="text-xs font-medium text-indigo-600 dark:text-indigo-400">
                Canonical .ai Profile Composition · Schema v{composedProfile.schemaVersion}
              </div>
              <h1 className="font-display text-lg font-semibold text-slate-900 dark:text-slate-100">
                Prompt & Profile Studio
              </h1>
              <p className="text-xs text-slate-600 dark:text-slate-300">
                Compose a portable assistant persona from base defaults + reusable overlays, inspect rendered instructions, or jump into tools.
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => setShowOverlayModal(true)}
                className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white whitespace-nowrap shrink-0"
              >
                <Plus className="h-3.5 w-3.5" />
                Save Overlay
              </button>
              <button
                type="button"
                onClick={resetStudioProfile}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Reset Default
              </button>
            </div>
          </div>

          {/* Delivery Preview */}
          <div className="rounded-lg border border-slate-200 dark:border-slate-700/80 bg-slate-50 dark:bg-slate-900/60 p-3.5 flex flex-wrap items-center justify-between gap-3">
            <div className="space-y-0.5 text-xs">
              <div className="font-semibold text-slate-800 dark:text-slate-100">
                Active Profile: <code className="font-mono text-indigo-600 dark:text-indigo-400">{composedProfile.id}</code>
                <span className="font-normal text-slate-500 dark:text-slate-400">
                  {' '}· {composedProfile.personality.base} ({composedProfile.personality.intensity ?? 1}/3) · {composedProfile.locale} ·{' '}
                  <span className="tabular-nums">{tokenCount} tokens</span>
                </span>
              </div>
              <div className="text-[11px] text-slate-500 dark:text-slate-400">
                {validation.isValid ? 'Schema validation passed' : validation.errors.join(' ')}
                {activeOverlay ? ` · Overlay: ${activeOverlay.name}` : ' · No overlay applied (base profile)'}
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => setActiveTab('INSTRUCTIONS')}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 hover:border-indigo-400 whitespace-nowrap"
              >
                <BookOpen className="h-3.5 w-3.5 text-indigo-500" />
                Instructions
              </button>
              <button
                type="button"
                onClick={() => setActiveTab('YAML')}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 hover:border-indigo-400 whitespace-nowrap"
              >
                <Code2 className="h-3.5 w-3.5 text-sky-500" />
                YAML & Docbench
              </button>
              <button
                type="button"
                onClick={() => setActiveTab('PLAYGROUND')}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 hover:border-indigo-400 whitespace-nowrap"
              >
                <FlaskConical className="h-3.5 w-3.5 text-violet-500" />
                Playground
              </button>
              <button
                type="button"
                onClick={() => {
                  setToolsSubTab('SKILLS');
                  setActiveTab('SKILLS');
                }}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 hover:border-indigo-400 whitespace-nowrap"
              >
                <Wrench className="h-3.5 w-3.5 text-emerald-500" />
                Tools Hub
              </button>
            </div>
          </div>

          {/* Overlay Selector Strip */}
          <div className="space-y-2">
            <div className="text-xs font-semibold text-slate-700 dark:text-slate-300">
              Select Profile Overlay
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
              <button
                type="button"
                onClick={() => setSelectedOverlayId(null)}
                className={`text-left rounded-lg border p-3 transition-colors ${
                  selectedOverlayId === null
                    ? 'border-indigo-500 bg-indigo-50/60 dark:bg-indigo-500/15'
                    : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600'
                }`}
              >
                <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                  Base Default Profile
                </div>
                <div className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                  Direct upstream defaults without downstream overrides.
                </div>
              </button>

              {allOverlays.map((ov) => {
                const isCustom = customOverlays.some((c) => c.id === ov.id);
                const selected = selectedOverlayId === ov.id;
                return (
                  <div
                    key={ov.id}
                    onClick={() => setSelectedOverlayId(ov.id)}
                    className={`relative rounded-lg border p-3 cursor-pointer transition-colors ${
                      selected
                        ? 'border-indigo-500 bg-indigo-50/60 dark:bg-indigo-500/15'
                        : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                        {ov.name}
                      </div>
                      {isCustom && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            deleteCustomOverlay(ov.id);
                          }}
                          className="text-slate-400 hover:text-rose-500"
                          title="Delete custom overlay"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      )}
                    </div>
                    <div className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5 line-clamp-2">
                      {ov.description}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </section>

        {/* Accordion 1: Personality & Voice */}
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] overflow-hidden">
          <button
            type="button"
            onClick={() => setOpenSection(openSection === 'personality' ? null : 'personality')}
            className="w-full flex items-center justify-between px-5 py-3.5 text-left hover:bg-slate-50 dark:hover:bg-slate-800/40"
          >
            <div className="flex items-center gap-2.5">
              <Sparkles className="h-4 w-4 text-indigo-500" />
              <div>
                <div className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Personality & Voice
                </div>
                <div className="text-xs text-slate-500 dark:text-slate-400">
                  Base: {baseProfile.personality.base} · Intensity: {baseProfile.personality.intensity ?? 1}/3 · Locale: {baseProfile.locale}
                </div>
              </div>
            </div>
            {openSection === 'personality' ? (
              <ChevronDown className="h-4 w-4 text-slate-400" />
            ) : (
              <ChevronRight className="h-4 w-4 text-slate-400" />
            )}
          </button>

          {openSection === 'personality' && (
            <div className="border-t border-slate-200 dark:border-slate-700/80 p-5 space-y-5">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="md:col-span-2 space-y-1.5">
                  <div className="text-xs font-medium text-slate-700 dark:text-slate-300">Base Voice</div>
                  <div className="flex flex-wrap gap-1.5">
                    {PERSONALITY_BASES.map((b) => (
                      <button
                        key={b.id}
                        type="button"
                        onClick={() =>
                          setBaseProfile((prev) => ({
                            ...prev,
                            personality: { ...prev.personality, base: b.id },
                          }))
                        }
                        className={`rounded-md px-3 py-1.5 text-xs font-medium border ${
                          baseProfile.personality.base === b.id
                            ? 'border-indigo-500 bg-indigo-600 text-white'
                            : 'border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300'
                        }`}
                      >
                        {b.label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="space-y-1.5">
                  <div className="text-xs font-medium text-slate-700 dark:text-slate-300">
                    Locale & Intensity
                  </div>
                  <div className="flex items-center gap-2">
                    <select
                      value={baseProfile.locale}
                      onChange={(e) => setBaseProfile((prev) => ({ ...prev, locale: e.target.value }))}
                      className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                    >
                      <option value="en-US">en-US</option>
                      <option value="pl-PL">pl-PL</option>
                      <option value="en-GB">en-GB</option>
                    </select>
                    <select
                      value={baseProfile.personality.intensity ?? 1}
                      onChange={(e) =>
                        setBaseProfile((prev) => ({
                          ...prev,
                          personality: { ...prev.personality, intensity: Number(e.target.value) },
                        }))
                      }
                      className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100 tabular-nums"
                    >
                      <option value={0}>Intensity 0</option>
                      <option value={1}>Intensity 1</option>
                      <option value={2}>Intensity 2</option>
                      <option value={3}>Intensity 3</option>
                    </select>
                  </div>
                </div>
              </div>

              <div className="space-y-2">
                <div className="text-xs font-medium text-slate-700 dark:text-slate-300">
                  Modifiers (0 = Off, 3 = Strong)
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2.5">
                  {MODIFIER_KEYS.map((key) => {
                    const val = baseProfile.personality.modifiers[key] ?? 0;
                    return (
                      <div
                        key={key}
                        className="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700/80 px-3 py-2"
                      >
                        <span className="font-mono text-xs text-slate-700 dark:text-slate-200">{key}</span>
                        <div className="flex items-center gap-1">
                          {[0, 1, 2, 3].map((lvl) => (
                            <button
                              key={lvl}
                              type="button"
                              onClick={() => setModifier(key, lvl)}
                              className={`h-6 w-6 rounded text-xs font-semibold tabular-nums ${
                                val === lvl
                                  ? 'bg-indigo-600 text-white'
                                  : 'bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-700'
                              }`}
                            >
                              {lvl}
                            </button>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>

              <div className="space-y-2">
                <div className="text-xs font-medium text-slate-700 dark:text-slate-300">Context Adaptation</div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {(
                    [
                      ['followUserRegister', 'Match user register'],
                      ['preserveRequestedArtifactStyle', 'Artifact style outranks persona'],
                      ['reduceHumorInSeriousContexts', 'Reduce humor in serious contexts'],
                      ['mirrorLanguage', 'Mirror user language'],
                      ['allowCasualProfanity', 'Allow casual profanity in casual chat'],
                    ] as Array<[keyof AdaptationConfig, string]>
                  ).map(([k, label]) => (
                    <label
                      key={k}
                      className="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700/80 px-3 py-2 text-xs cursor-pointer"
                    >
                      <span className="text-slate-700 dark:text-slate-200">{label}</span>
                      <input
                        type="checkbox"
                        checked={baseProfile.personality.adaptation[k]}
                        onChange={(e) =>
                          setBaseProfile((prev) => ({
                            ...prev,
                            personality: {
                              ...prev.personality,
                              adaptation: { ...prev.personality.adaptation, [k]: e.target.checked },
                            },
                          }))
                        }
                        className="h-4 w-4 accent-indigo-600"
                      />
                    </label>
                  ))}
                </div>
              </div>
            </div>
          )}
        </section>

        {/* Accordion 2: Collaboration & Execution Policy */}
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] overflow-hidden">
          <button
            type="button"
            onClick={() => setOpenSection(openSection === 'collaboration' ? null : 'collaboration')}
            className="w-full flex items-center justify-between px-5 py-3.5 text-left hover:bg-slate-50 dark:hover:bg-slate-800/40"
          >
            <div className="flex items-center gap-2.5">
              <Sliders className="h-4 w-4 text-sky-500" />
              <div>
                <div className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Collaboration & Execution Policy
                </div>
                <div className="text-xs text-slate-500 dark:text-slate-400">
                  Verification: {baseProfile.collaboration.verification} · Initiative: {baseProfile.collaboration.initiative} · Question threshold: {baseProfile.collaboration.questionPolicy}
                </div>
              </div>
            </div>
            {openSection === 'collaboration' ? (
              <ChevronDown className="h-4 w-4 text-slate-400" />
            ) : (
              <ChevronRight className="h-4 w-4 text-slate-400" />
            )}
          </button>

          {openSection === 'collaboration' && (
            <div className="border-t border-slate-200 dark:border-slate-700/80 p-5 space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-3 lg:grid-cols-5 gap-3">
                {(
                  [
                    ['preamble', 'Preamble', ['off', 'multiStepOnly', 'always']],
                    ['initiative', 'Initiative', ['conservative', 'balanced', 'proactive']],
                    ['verification', 'Verification', ['light', 'normal', 'strict']],
                    ['questionPolicy', 'Question Policy', ['blockingOnly', 'materialAmbiguity', 'earlyAlignment']],
                    ['assumptionPolicy', 'Assumptions', ['cautious', 'balanced', 'decisive']],
                  ] as Array<[keyof CollaborationConfig, string, string[]]>
                ).map(([field, label, opts]) => (
                  <label key={field} className="block space-y-1">
                    <span className="text-xs font-medium text-slate-600 dark:text-slate-300">{label}</span>
                    <select
                      value={String(baseProfile.collaboration[field])}
                      onChange={(e) =>
                        setBaseProfile((prev) => ({
                          ...prev,
                          collaboration: { ...prev.collaboration, [field]: e.target.value },
                        }))
                      }
                      className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                    >
                      {opts.map((o) => (
                        <option key={o} value={o}>
                          {o}
                        </option>
                      ))}
                    </select>
                  </label>
                ))}
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {(
                  [
                    ['answerFirst', 'Lead with answer or decision'],
                    ['plainChatIsDefault', 'Plain chat is default over agentic machinery'],
                    ['respectExplicitTurnInstructions', 'Explicit turn instructions override style'],
                    ['avoidRoutinePraise', 'Avoid routine praise openers'],
                    ['avoidRoutineFollowUpOffer', 'Avoid routine follow-up offers'],
                    ['announceOnlyMaterialActions', 'Announce only material state changes'],
                    ['reportPartialFailures', 'Distinguish full success, partial success, failure'],
                    ['preferResultOverProcess', 'Present result before process'],
                  ] as const
                ).map(([k, label]) => (
                  <label
                    key={k}
                    className="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700/80 px-3 py-2 text-xs cursor-pointer"
                  >
                    <span className="text-slate-700 dark:text-slate-200">{label}</span>
                    <input
                      type="checkbox"
                      checked={baseProfile.collaboration[k]}
                      onChange={(e) =>
                        setBaseProfile((prev) => ({
                          ...prev,
                          collaboration: { ...prev.collaboration, [k]: e.target.checked },
                        }))
                      }
                      className="h-4 w-4 accent-indigo-600"
                    />
                  </label>
                ))}
              </div>
            </div>
          )}
        </section>

        {/* Accordion 3: Output & Knowledge Rules */}
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] overflow-hidden">
          <button
            type="button"
            onClick={() => setOpenSection(openSection === 'output' ? null : 'output')}
            className="w-full flex items-center justify-between px-5 py-3.5 text-left hover:bg-slate-50 dark:hover:bg-slate-800/40"
          >
            <div className="flex items-center gap-2.5">
              <Code2 className="h-4 w-4 text-violet-500" />
              <div>
                <div className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Output & Knowledge Rules
                </div>
                <div className="text-xs text-slate-500 dark:text-slate-400">
                  Format: {baseProfile.output.defaultFormat} · Code: {baseProfile.output.codeExamples} · Tables: {baseProfile.output.tables}
                </div>
              </div>
            </div>
            {openSection === 'output' ? (
              <ChevronDown className="h-4 w-4 text-slate-400" />
            ) : (
              <ChevronRight className="h-4 w-4 text-slate-400" />
            )}
          </button>

          {openSection === 'output' && (
            <div className="border-t border-slate-200 dark:border-slate-700/80 p-5 space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
                <label className="block space-y-1">
                  <span className="text-xs font-medium text-slate-600 dark:text-slate-300">Default Format</span>
                  <select
                    value={baseProfile.output.defaultFormat}
                    onChange={(e) =>
                      setBaseProfile((prev) => ({
                        ...prev,
                        output: { ...prev.output, defaultFormat: e.target.value },
                      }))
                    }
                    className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  >
                    <option value="prose">prose</option>
                    <option value="markdown">markdown</option>
                    <option value="bullets">bullets</option>
                  </select>
                </label>

                <label className="block space-y-1">
                  <span className="text-xs font-medium text-slate-600 dark:text-slate-300">Tables</span>
                  <select
                    value={baseProfile.output.tables}
                    onChange={(e) =>
                      setBaseProfile((prev) => ({
                        ...prev,
                        output: { ...prev.output, tables: e.target.value as OutputConfig['tables'] },
                      }))
                    }
                    className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  >
                    <option value="avoid">avoid</option>
                    <option value="whenUseful">whenUseful</option>
                    <option value="prefer">prefer</option>
                  </select>
                </label>

                <label className="block space-y-1">
                  <span className="text-xs font-medium text-slate-600 dark:text-slate-300">Code Examples</span>
                  <select
                    value={baseProfile.output.codeExamples}
                    onChange={(e) =>
                      setBaseProfile((prev) => ({
                        ...prev,
                        output: { ...prev.output, codeExamples: e.target.value as OutputConfig['codeExamples'] },
                      }))
                    }
                    className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  >
                    <option value="minimal">minimal</option>
                    <option value="runnable">runnable</option>
                    <option value="explanatory">explanatory</option>
                  </select>
                </label>

                <label className="block space-y-1">
                  <span className="text-xs font-medium text-slate-600 dark:text-slate-300">Citations</span>
                  <select
                    value={baseProfile.output.citations}
                    onChange={(e) =>
                      setBaseProfile((prev) => ({
                        ...prev,
                        output: { ...prev.output, citations: e.target.value as OutputConfig['citations'] },
                      }))
                    }
                    className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                  >
                    <option value="platformDefault">platformDefault</option>
                    <option value="whenAvailable">whenAvailable</option>
                    <option value="requiredForExternalFacts">requiredForExternalFacts</option>
                  </select>
                </label>
              </div>

              <div className="space-y-2">
                <div className="text-xs font-medium text-slate-700 dark:text-slate-300">Knowledge Boundaries</div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {(
                    [
                      ['distinguishRawFromSynthesis', 'Distinguish raw source material from synthesis'],
                      ['treatMemoryAsFallible', 'Treat remembered context as fallible'],
                      ['surfaceSourceConflicts', 'Surface source conflicts instead of choosing silently'],
                      ['preferMaintainedSynthesisForOrientation', 'Prefer maintained synthesis for orientation'],
                      ['requireTraceableClaims', 'Keep verifiable claims traceable to evidence'],
                    ] as Array<[keyof KnowledgeConfig, string]>
                  ).map(([k, label]) => (
                    <label
                      key={k}
                      className="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700/80 px-3 py-2 text-xs cursor-pointer"
                    >
                      <span className="text-slate-700 dark:text-slate-200">{label}</span>
                      <input
                        type="checkbox"
                        checked={baseProfile.knowledge[k]}
                        onChange={(e) =>
                          setBaseProfile((prev) => ({
                            ...prev,
                            knowledge: { ...prev.knowledge, [k]: e.target.checked },
                          }))
                        }
                        className="h-4 w-4 accent-indigo-600"
                      />
                    </label>
                  ))}
                </div>
              </div>
            </div>
          )}
        </section>
      </div>

      {/* Save Custom Overlay Modal */}
      {showOverlayModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setShowOverlayModal(false)}
        >
          <form
            onSubmit={handleSaveOverlay}
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700">
              <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                Save Current Settings as Overlay
              </h3>
              <button
                type="button"
                onClick={() => setShowOverlayModal(false)}
                className="rounded p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="p-4 space-y-3">
              <label className="block space-y-1">
                <span className="text-xs font-medium text-slate-700 dark:text-slate-300">Overlay Name</span>
                <input
                  type="text"
                  required
                  value={overlayName}
                  onChange={(e) => setOverlayName(e.target.value)}
                  placeholder="My Architecture Reviewer"
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                />
              </label>
              <label className="block space-y-1">
                <span className="text-xs font-medium text-slate-700 dark:text-slate-300">Description</span>
                <input
                  type="text"
                  value={overlayDesc}
                  onChange={(e) => setOverlayDesc(e.target.value)}
                  placeholder="Short summary of when to apply"
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                />
              </label>
              <label className="block space-y-1">
                <span className="text-xs font-medium text-slate-700 dark:text-slate-300">Private Extension Note</span>
                <input
                  type="text"
                  value={overlayNote}
                  onChange={(e) => setOverlayNote(e.target.value)}
                  placeholder="Optional extensions.local.note"
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
                />
              </label>
            </div>
            <div className="flex justify-end gap-2 px-4 py-3 border-t border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50">
              <button
                type="button"
                onClick={() => setShowOverlayModal(false)}
                className="rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3.5 py-1.5 text-xs font-semibold text-white"
              >
                Save Overlay
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};
