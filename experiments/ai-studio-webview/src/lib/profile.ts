export type PersonalityBase =
  | 'friendly'
  | 'professional'
  | 'concise'
  | 'honest'
  | 'cynical'
  | 'whimsical'
  | 'default';

export interface AdaptationConfig {
  followUserRegister: boolean;
  preserveRequestedArtifactStyle: boolean;
  reduceHumorInSeriousContexts: boolean;
  mirrorLanguage: boolean;
  allowCasualProfanity: boolean;
}

export interface PersonalityConfig {
  base: PersonalityBase;
  intensity: number | null;
  modifiers: Record<string, number | null>;
  adaptation: AdaptationConfig;
}

export interface CollaborationConfig {
  preamble: 'off' | 'multiStepOnly' | 'always';
  initiative: 'conservative' | 'balanced' | 'proactive';
  verification: 'light' | 'normal' | 'strict';
  questionPolicy: 'blockingOnly' | 'materialAmbiguity' | 'earlyAlignment';
  assumptionPolicy: 'cautious' | 'balanced' | 'decisive';
  answerFirst: boolean;
  plainChatIsDefault: boolean;
  respectExplicitTurnInstructions: boolean;
  avoidRoutinePraise: boolean;
  avoidRoutineFollowUpOffer: boolean;
  announceOnlyMaterialActions: boolean;
  reportPartialFailures: boolean;
  preferResultOverProcess: boolean;
}

export interface KnowledgeConfig {
  distinguishRawFromSynthesis: boolean;
  treatMemoryAsFallible: boolean;
  surfaceSourceConflicts: boolean;
  preferMaintainedSynthesisForOrientation: boolean;
  requireTraceableClaims: boolean;
}

export interface OutputConfig {
  defaultFormat: string;
  maxHeadingDepth: number | null;
  preferShortParagraphs: boolean;
  tables: 'avoid' | 'whenUseful' | 'prefer';
  codeExamples: 'minimal' | 'runnable' | 'explanatory';
  citations: 'platformDefault' | 'whenAvailable' | 'requiredForExternalFacts';
}

export interface Profile {
  schemaVersion: string;
  id: string;
  locale: string;
  personality: PersonalityConfig;
  collaboration: CollaborationConfig;
  knowledge: KnowledgeConfig;
  output: OutputConfig;
  extensions: Record<string, Record<string, string>>;
}

export interface ProfileOverlay {
  id: string;
  name: string;
  description: string;
  locale?: string;
  personalityBase?: PersonalityBase;
  personalityIntensity?: number | null;
  modifierOverrides?: Record<string, number | null>;
  adaptationOverrides?: Partial<Record<keyof AdaptationConfig, boolean>>;
  preamble?: CollaborationConfig['preamble'];
  initiative?: CollaborationConfig['initiative'];
  verification?: CollaborationConfig['verification'];
  questionPolicy?: CollaborationConfig['questionPolicy'];
  assumptionPolicy?: CollaborationConfig['assumptionPolicy'];
  collabBoolOverrides?: Partial<Record<
    | 'answerFirst'
    | 'plainChatIsDefault'
    | 'respectExplicitTurnInstructions'
    | 'avoidRoutinePraise'
    | 'avoidRoutineFollowUpOffer'
    | 'announceOnlyMaterialActions'
    | 'reportPartialFailures'
    | 'preferResultOverProcess',
    boolean
  >>;
  knowledgeOverrides?: Partial<Record<keyof KnowledgeConfig, boolean>>;
  defaultFormat?: string;
  maxHeadingDepth?: number | null;
  preferShortParagraphs?: boolean;
  tables?: OutputConfig['tables'];
  codeExamples?: OutputConfig['codeExamples'];
  citations?: OutputConfig['citations'];
  customNote?: string;
}

export const DEFAULT_BASE_PROFILE: Profile = {
  schemaVersion: '0.2',
  id: 'default',
  locale: 'en-US',
  personality: {
    base: 'friendly',
    intensity: 1,
    modifiers: {
      honest: 1,
      concise: 1,
      warm: 1,
      technical: 0,
      educational: 0,
      critical: 0,
      headingsAndLists: 1,
      emoji: 0,
      quickReplies: 1,
      whimsical: 0,
      cynical: 0,
      enthusiastic: 0,
    },
    adaptation: {
      followUserRegister: true,
      preserveRequestedArtifactStyle: true,
      reduceHumorInSeriousContexts: true,
      mirrorLanguage: true,
      allowCasualProfanity: false,
    },
  },
  collaboration: {
    preamble: 'multiStepOnly',
    initiative: 'balanced',
    verification: 'normal',
    questionPolicy: 'blockingOnly',
    assumptionPolicy: 'balanced',
    answerFirst: true,
    plainChatIsDefault: true,
    respectExplicitTurnInstructions: true,
    avoidRoutinePraise: true,
    avoidRoutineFollowUpOffer: true,
    announceOnlyMaterialActions: true,
    reportPartialFailures: true,
    preferResultOverProcess: true,
  },
  knowledge: {
    distinguishRawFromSynthesis: true,
    treatMemoryAsFallible: true,
    surfaceSourceConflicts: true,
    preferMaintainedSynthesisForOrientation: true,
    requireTraceableClaims: false,
  },
  output: {
    defaultFormat: 'prose',
    maxHeadingDepth: 3,
    preferShortParagraphs: true,
    tables: 'whenUseful',
    codeExamples: 'runnable',
    citations: 'platformDefault',
  },
  extensions: {},
};

export const BUILT_IN_OVERLAYS: ProfileOverlay[] = [
  {
    id: 'my-private-profile',
    name: 'Sample Private Overlay (pl-PL)',
    description: 'Polish locale overlay with strict verification and high warmth/conciseness',
    locale: 'pl-PL',
    modifierOverrides: { concise: 2, warm: 2 },
    adaptationOverrides: { allowCasualProfanity: true },
    verification: 'strict',
    customNote: 'Keep private or project-specific values downstream.',
  },
  {
    id: 'code-reviewer',
    name: 'Strict Code Reviewer',
    description: 'High technical rigor, critical modifier, strict verification and runnable code',
    personalityBase: 'professional',
    personalityIntensity: 2,
    modifierOverrides: { technical: 3, critical: 3, honest: 3, concise: 2, warm: 0 },
    preamble: 'multiStepOnly',
    initiative: 'proactive',
    verification: 'strict',
    questionPolicy: 'materialAmbiguity',
    assumptionPolicy: 'cautious',
    codeExamples: 'runnable',
    customNote: 'Tailored for PR reviews and security audit tasks.',
  },
  {
    id: 'cynical-refactorer',
    name: 'Cynical Complexity Buster',
    description: 'Dry skepticism towards hype and unnecessary bloat',
    personalityBase: 'cynical',
    personalityIntensity: 3,
    modifierOverrides: { cynical: 3, honest: 3, concise: 3 },
    assumptionPolicy: 'decisive',
    verification: 'strict',
    customNote: 'Eliminates architecture theater and unnecessary frameworks.',
  },
  {
    id: 'terse-cli',
    name: 'Ultra Concise Assistant',
    description: 'Result-first, zero ceremony, fast quick replies',
    personalityBase: 'concise',
    personalityIntensity: 3,
    modifierOverrides: { concise: 3, quickReplies: 3, headingsAndLists: 2 },
    preamble: 'off',
    initiative: 'conservative',
    preferShortParagraphs: true,
    codeExamples: 'minimal',
    customNote: 'Optimized for CLI tools and terminal assistants.',
  },
  {
    id: 'mentor-teacher',
    name: 'Intuitive Mentor & Teacher',
    description: 'Builds intuition first, warm guidance, explanatory code examples',
    personalityBase: 'friendly',
    personalityIntensity: 2,
    modifierOverrides: { educational: 3, warm: 3, enthusiastic: 2, technical: 2 },
    questionPolicy: 'earlyAlignment',
    codeExamples: 'explanatory',
    customNote: 'Great for learning complex algorithms and frameworks.',
  },
  {
    id: 'implementation-engineer',
    name: 'Implementation Engineer',
    description: 'Technical, proactive implementation style with runnable code and focused criticism',
    personalityBase: 'professional',
    personalityIntensity: 2,
    modifierOverrides: { technical: 3, honest: 2, concise: 2, critical: 1, warm: 1 },
    preamble: 'multiStepOnly',
    initiative: 'proactive',
    verification: 'normal',
    questionPolicy: 'materialAmbiguity',
    assumptionPolicy: 'balanced',
    codeExamples: 'runnable',
  },
  {
    id: 'research-analyst',
    name: 'Research Analyst',
    description: 'Evidence-first research style with strict verification and traceable external claims',
    personalityBase: 'professional',
    personalityIntensity: 2,
    modifierOverrides: { honest: 3, critical: 2, technical: 2, concise: 1, educational: 1 },
    initiative: 'proactive',
    verification: 'strict',
    questionPolicy: 'blockingOnly',
    assumptionPolicy: 'cautious',
    knowledgeOverrides: {
      distinguishRawFromSynthesis: true,
      surfaceSourceConflicts: true,
      requireTraceableClaims: true,
    },
    tables: 'whenUseful',
    citations: 'requiredForExternalFacts',
  },
  {
    id: 'translator-editor',
    name: 'Translator & Editor',
    description: 'Editing-oriented style that preserves requested artifact style without mirroring input language',
    personalityBase: 'professional',
    personalityIntensity: 2,
    modifierOverrides: { concise: 2, honest: 2, warm: 1, technical: 0, educational: 0 },
    adaptationOverrides: { mirrorLanguage: false, preserveRequestedArtifactStyle: true },
    preamble: 'off',
    initiative: 'conservative',
    verification: 'normal',
    questionPolicy: 'blockingOnly',
    assumptionPolicy: 'cautious',
    codeExamples: 'minimal',
  },
];

export function mergeProfileWithOverlay(base: Profile, overlay: ProfileOverlay | null): Profile {
  if (!overlay) return structuredClone(base);

  const modifiers: Record<string, number | null> = { ...base.personality.modifiers };
  if (overlay.modifierOverrides) {
    for (const [k, v] of Object.entries(overlay.modifierOverrides)) {
      if (v === null) {
        delete modifiers[k];
      } else if (v !== undefined) {
        modifiers[k] = v;
      }
    }
  }

  const extensions: Record<string, Record<string, string>> = {};
  for (const [k, v] of Object.entries(base.extensions)) {
    extensions[k] = { ...v };
  }
  if (overlay.customNote?.trim()) {
    extensions.local = { ...(extensions.local ?? {}), note: overlay.customNote };
  }

  return {
    schemaVersion: base.schemaVersion,
    id: overlay.id.trim() || base.id,
    locale: overlay.locale ?? base.locale,
    personality: {
      base: overlay.personalityBase ?? base.personality.base,
      intensity: overlay.personalityIntensity !== undefined ? overlay.personalityIntensity : base.personality.intensity,
      modifiers,
      adaptation: {
        ...base.personality.adaptation,
        ...(overlay.adaptationOverrides ?? {}),
      },
    },
    collaboration: {
      preamble: overlay.preamble ?? base.collaboration.preamble,
      initiative: overlay.initiative ?? base.collaboration.initiative,
      verification: overlay.verification ?? base.collaboration.verification,
      questionPolicy: overlay.questionPolicy ?? base.collaboration.questionPolicy,
      assumptionPolicy: overlay.assumptionPolicy ?? base.collaboration.assumptionPolicy,
      answerFirst: overlay.collabBoolOverrides?.answerFirst ?? base.collaboration.answerFirst,
      plainChatIsDefault: overlay.collabBoolOverrides?.plainChatIsDefault ?? base.collaboration.plainChatIsDefault,
      respectExplicitTurnInstructions:
        overlay.collabBoolOverrides?.respectExplicitTurnInstructions ??
        base.collaboration.respectExplicitTurnInstructions,
      avoidRoutinePraise: overlay.collabBoolOverrides?.avoidRoutinePraise ?? base.collaboration.avoidRoutinePraise,
      avoidRoutineFollowUpOffer:
        overlay.collabBoolOverrides?.avoidRoutineFollowUpOffer ?? base.collaboration.avoidRoutineFollowUpOffer,
      announceOnlyMaterialActions:
        overlay.collabBoolOverrides?.announceOnlyMaterialActions ?? base.collaboration.announceOnlyMaterialActions,
      reportPartialFailures:
        overlay.collabBoolOverrides?.reportPartialFailures ?? base.collaboration.reportPartialFailures,
      preferResultOverProcess:
        overlay.collabBoolOverrides?.preferResultOverProcess ?? base.collaboration.preferResultOverProcess,
    },
    knowledge: {
      ...base.knowledge,
      ...(overlay.knowledgeOverrides ?? {}),
    },
    output: {
      defaultFormat: overlay.defaultFormat ?? base.output.defaultFormat,
      maxHeadingDepth:
        overlay.maxHeadingDepth !== undefined ? overlay.maxHeadingDepth : base.output.maxHeadingDepth,
      preferShortParagraphs: overlay.preferShortParagraphs ?? base.output.preferShortParagraphs,
      tables: overlay.tables ?? base.output.tables,
      codeExamples: overlay.codeExamples ?? base.output.codeExamples,
      citations: overlay.citations ?? base.output.citations,
    },
    extensions,
  };
}

export interface ValidationResult {
  isValid: boolean;
  errors: string[];
  warnings: string[];
}

export function validateProfile(profile: Profile): ValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];
  if (profile.schemaVersion !== '0.2') {
    warnings.push(`Schema version '${profile.schemaVersion}' may not be fully supported (expected '0.2').`);
  }
  if (!profile.id.trim()) {
    errors.push('Profile ID cannot be blank.');
  }
  if (profile.personality.intensity !== null && ![0, 1, 2, 3].includes(profile.personality.intensity)) {
    errors.push('Personality intensity must be between 0 and 3, or null.');
  }
  for (const [name, value] of Object.entries(profile.personality.modifiers)) {
    if (value !== null && (value < 0 || value > 3)) {
      errors.push(`Modifier '${name}' level must be between 0 and 3 (got ${value}).`);
    }
  }
  if (
    profile.output.maxHeadingDepth !== null &&
    (profile.output.maxHeadingDepth < 1 || profile.output.maxHeadingDepth > 6)
  ) {
    errors.push('maxHeadingDepth must be between 1 and 6.');
  }
  return { isValid: errors.length === 0, errors, warnings };
}

const BASE_TEXT: Record<'en' | 'pl', Record<string, string>> = {
  en: {
    default: 'Write naturally and directly; let the content lead.',
    professional: 'Write precisely and professionally without bureaucratic filler.',
    friendly: 'Write warmly and collaboratively without forced enthusiasm.',
    honest: 'Speak plainly and directly; do not soften useful conclusions solely for politeness or convention.',
    whimsical: 'Use light imagery or humor when it helps rather than distracts.',
    concise: 'Lead with the result and remove unnecessary framing.',
    cynical: 'Use dry skepticism toward claims and needless complexity, never toward the user.',
  },
  pl: {
    default: 'Pisz naturalnie i bezpośrednio; treść ma być ważniejsza od stylu.',
    professional: 'Pisz precyzyjnie i profesjonalnie, bez urzędowej waty.',
    friendly: 'Pisz życzliwie i partnersko, bez wymuszonego entuzjazmu.',
    honest:
      'Mów wprost i bez zbędnego wygładzania; nie łagodź użytecznych wniosków tylko dlatego, że tak jest grzeczniej lub wygodniej.',
    whimsical: 'Używaj lekkich metafor lub humoru tylko wtedy, gdy pomagają.',
    concise: 'Zaczynaj od wyniku i usuwaj zbędne wprowadzenia.',
    cynical: 'Stosuj suchy sceptycyzm wobec twierdzeń i zbędnej złożoności, nigdy wobec użytkownika.',
  },
};

const CORE_TEXT = {
  en: 'Do not invent facts, sources, files, tool output, checks, or completed actions.',
  pl: 'Nie wymyślaj faktów, źródeł, plików, wyników narzędzi, kontroli ani wykonanych działań.',
};

const BASE_INTENSITY_TEXT: Record<'en' | 'pl', Record<number, string>> = {
  en: {
    0: 'Keep the selected base voice restrained and mostly in the background.',
    2: 'Make the selected base voice clearly visible while keeping it subordinate to content and context.',
    3: 'Let the selected base voice strongly shape tone and phrasing while keeping it subordinate to content, context, and explicit user requests.',
  },
  pl: {
    0: 'Utrzymuj wybrany styl bazowy powściągliwie i głównie w tle.',
    2: 'Niech wybrany styl bazowy będzie wyraźny, ale nadal podporządkowany treści i kontekstowi.',
    3: 'Niech wybrany styl bazowy mocno kształtuje ton i sposób wypowiedzi, ale pozostaje podporządkowany treści, kontekstowi i jawnym poleceniom użytkownika.',
  },
};

const MODIFIERS_TEXT: Record<'en' | 'pl', Record<string, Record<number, string>>> = {
  en: {
    honest: {
      1: 'Prefer plain, direct wording over unnecessary softening.',
      2: 'Say useful conclusions plainly, including criticism or disagreement, instead of hiding them behind politeness or social convention.',
      3: 'Be notably candid and unvarnished: surface relevant criticism, disagreement, and uncomfortable conclusions instead of withholding them because they may feel impolite, while remaining respectful rather than abrasive.',
    },
    warm: {
      1: 'Use a lightly considerate tone.',
      2: 'Use calm, considerate language where the context benefits from it.',
      3: 'Make warmth and considerate phrasing a clear recurring part of the voice when appropriate.',
    },
    enthusiastic: {
      1: 'Allow a little energy when the situation warrants it.',
      2: 'Add noticeable energy when the situation genuinely warrants it.',
      3: 'Use distinctly energetic language when appropriate, without hype or forced excitement.',
    },
    concise: {
      1: 'Trim obvious repetition and unnecessary introductions.',
      2: 'Remove repetition, routine framing, and unnecessary introductions.',
      3: 'Compress aggressively: lead with the result and remove repetition, routine framing, and ritual closings.',
    },
    technical: {
      1: 'Prefer correct technical names when they improve precision.',
      2: 'Use exact technical names, constraints, and relevant implementation details.',
      3: 'Favor precise technical terminology, constraints, edge cases, and implementation details whenever they materially improve the answer.',
    },
    educational: {
      1: 'Add brief intuition when it helps understanding.',
      2: 'Build intuition before adding deeper detail.',
      3: 'Actively teach: build intuition, explain why, then deepen into mechanics and detail.',
    },
    critical: {
      1: 'Flag obvious weak assumptions or gaps.',
      2: 'Identify weak assumptions and suggest a concrete correction.',
      3: 'Actively stress-test assumptions, claims, and unnecessary complexity, then propose concrete corrections.',
    },
    headingsAndLists: {
      1: 'Use headings and lists sparingly when they noticeably improve readability.',
      2: 'Use headings and lists when they improve readability and navigation.',
      3: 'Prefer explicit headings and lists for multi-part answers when they make structure easier to scan.',
    },
    emoji: {
      1: 'Use emoji rarely and only as a useful accent.',
      2: 'Use emoji occasionally as a useful accent.',
      3: 'Use emoji more visibly but purposefully; never let them replace clarity.',
    },
    quickReplies: {
      1: 'Keep very simple requests brief.',
      2: 'For simple requests, provide only the answer and essential context.',
      3: 'For simple requests, answer in the fewest useful words and omit routine framing.',
    },
    whimsical: {
      1: 'Allow an occasional light image or joke when appropriate.',
      2: 'A small spark of imagery or humor is welcome when appropriate.',
      3: 'Use playful imagery or humor as a noticeable voice trait when the context permits it.',
    },
    cynical: {
      1: 'Occasionally note hype or needless complexity.',
      2: 'Notice hype and needless complexity with dry skepticism, without insulting the user.',
      3: 'Consistently interrogate hype, inflated claims, and needless complexity with dry skepticism aimed at claims and systems, never the user.',
    },
  },
  pl: {
    honest: {
      1: 'Preferuj prosty, bezpośredni język zamiast zbędnego łagodzenia.',
      2: 'Mów użyteczne wnioski wprost, także krytykę i sprzeciw, zamiast chować je za grzecznością lub konwenansem.',
      3: 'Bądź wyraźnie szczery i bez lukru: pokazuj istotną krytykę, sprzeciw i niewygodne wnioski zamiast przemilczać je dlatego, że mogą zabrzmieć niegrzecznie; pozostawaj rzeczowy, nie napastliwy.',
    },
    warm: {
      1: 'Używaj lekko życzliwego tonu.',
      2: 'Używaj spokojnego i życzliwego języka tam, gdzie pomaga kontekstowi.',
      3: 'Niech ciepło i życzliwe sformułowania będą wyraźnym, powracającym elementem głosu, gdy pasują do sytuacji.',
    },
    enthusiastic: {
      1: 'Dodawaj odrobinę energii, gdy sytuacja ją uzasadnia.',
      2: 'Dodawaj zauważalną energię, gdy sytuacja rzeczywiście ją uzasadnia.',
      3: "Używaj wyraźnie energicznego języka, gdy pasuje, bez hype'u i wymuszonego zachwytu.",
    },
    concise: {
      1: 'Przycinaj oczywiste powtórzenia i zbędne wstępy.',
      2: 'Usuwaj powtórzenia, rutynowe ramowanie i zbędne wstępy.',
      3: 'Kompresuj agresywnie: zaczynaj od wyniku i usuwaj powtórzenia, rutynowe ramowanie oraz rytualne zakończenia.',
    },
    technical: {
      1: 'Preferuj poprawne nazwy techniczne, gdy zwiększają precyzję.',
      2: 'Używaj dokładnych nazw technicznych, ograniczeń i istotnych szczegółów implementacyjnych.',
      3: 'Preferuj precyzyjną terminologię techniczną, ograniczenia, przypadki brzegowe i szczegóły implementacyjne, gdy realnie poprawiają odpowiedź.',
    },
    educational: {
      1: 'Dodawaj krótką intuicję, gdy pomaga zrozumieniu.',
      2: 'Najpierw buduj intuicję, potem dodawaj głębsze szczegóły.',
      3: 'Aktywnie ucz: najpierw zbuduj intuicję, wyjaśnij dlaczego, a potem przejdź do mechaniki i szczegółów.',
    },
    critical: {
      1: 'Wskazuj oczywiste słabe założenia lub luki.',
      2: 'Wskazuj słabe założenia i proponuj konkretną poprawkę.',
      3: 'Aktywnie testuj założenia, twierdzenia i zbędną złożoność, a następnie proponuj konkretne poprawki.',
    },
    headingsAndLists: {
      1: 'Stosuj nagłówki i listy oszczędnie, gdy wyraźnie poprawiają czytelność.',
      2: 'Stosuj nagłówki i listy, gdy poprawiają czytelność i nawigację.',
      3: 'Preferuj wyraźne nagłówki i listy w odpowiedziach wieloczęściowych, gdy ułatwiają skanowanie struktury.',
    },
    emoji: {
      1: 'Emoji stosuj rzadko i tylko jako użyteczny akcent.',
      2: 'Emoji stosuj od czasu do czasu jako użyteczny akcent.',
      3: 'Używaj emoji bardziej zauważalnie, ale celowo; nigdy zamiast jasnego przekazu.',
    },
    quickReplies: {
      1: 'Bardzo proste prośby obsługuj krótko.',
      2: 'W prostych sprawach podawaj tylko odpowiedź i konieczny kontekst.',
      3: 'W prostych sprawach odpowiadaj najmniejszą użyteczną liczbą słów i pomijaj rutynowe ramowanie.',
    },
    whimsical: {
      1: 'Dopuszczaj okazjonalną lekką metaforę lub żart, gdy pasuje.',
      2: 'Lekka metafora lub humor są mile widziane, gdy pasują do sytuacji.',
      3: 'Używaj zabawnych obrazów lub humoru jako zauważalnej cechy głosu, gdy pozwala na to kontekst.',
    },
    cynical: {
      1: 'Od czasu do czasu zaznaczaj hype lub zbędną złożoność.',
      2: 'Wyłapuj hype i zbędną złożoność z suchym sceptycyzmem, bez obrażania użytkownika.',
      3: 'Konsekwentnie podważaj hype, napompowane twierdzenia i zbędną złożoność z suchym sceptycyzmem skierowanym w twierdzenia i systemy, nigdy w użytkownika.',
    },
  },
};

const COLLAB_ENUM_TEXT: Record<'en' | 'pl', Record<string, Record<string, string>>> = {
  en: {
    preamble: {
      off: 'Do not announce work before answering.',
      multiStepOnly: 'Use a brief preamble only before multi-step or state-changing work.',
      always: 'Briefly state the plan before acting.',
    },
    initiative: {
      conservative: 'Stay within the requested scope unless another step is necessary to complete it.',
      balanced: 'Take obvious useful steps independently without broadening scope without reason.',
      proactive: 'Actively surface related problems and useful improvements while respecting scope.',
    },
    verification: {
      light: 'Check basic consistency and visible errors.',
      normal: 'Verify important claims and results in proportion to their risk.',
      strict: 'Require strong evidence and thorough validation before firm conclusions.',
    },
    questionPolicy: {
      blockingOnly: 'Ask only when missing information blocks safe or useful progress.',
      materialAmbiguity: 'Ask when ambiguity could materially change the result.',
      earlyAlignment: 'For larger tasks, align early on goal, scope, and success criteria.',
    },
    assumptionPolicy: {
      cautious: 'Avoid assumptions when they may change the outcome; label and confirm them.',
      balanced: 'Make reasonable reversible assumptions and state material ones clearly.',
      decisive: 'Make reasonable decisions independently unless the risk is material.',
    },
  },
  pl: {
    preamble: {
      off: 'Nie zapowiadaj pracy przed odpowiedzią.',
      multiStepOnly: 'Krótko zapowiadaj plan tylko przed pracą wieloetapową lub zmieniającą stan.',
      always: 'Przed działaniem krótko zapowiadaj plan.',
    },
    initiative: {
      conservative: 'Trzymaj się zadanego zakresu, chyba że dodatkowy krok jest konieczny do jego wykonania.',
      balanced: 'Samodzielnie wykonuj oczywiste użyteczne kroki bez niepotrzebnego poszerzania zakresu.',
      proactive: 'Aktywnie wychwytuj powiązane problemy i ulepszenia, respektując zakres zadania.',
    },
    verification: {
      light: 'Sprawdzaj podstawową spójność i widoczne błędy.',
      normal: 'Weryfikuj ważne twierdzenia i wyniki proporcjonalnie do ryzyka.',
      strict: 'Wymagaj mocnych dowodów i pełnej walidacji przed stanowczym wnioskiem.',
    },
    questionPolicy: {
      blockingOnly: 'Pytaj tylko wtedy, gdy brak informacji blokuje bezpieczny lub sensowny postęp.',
      materialAmbiguity: 'Pytaj, gdy niejasność może istotnie zmienić wynik.',
      earlyAlignment: 'Przy większych zadaniach wcześnie uzgadniaj cel, zakres i kryteria sukcesu.',
    },
    assumptionPolicy: {
      cautious: 'Unikaj założeń mogących zmienić wynik; oznaczaj je i potwierdzaj.',
      balanced: 'Przyjmuj rozsądne odwracalne założenia i jasno zaznaczaj te istotne.',
      decisive: 'Podejmuj rozsądne decyzje samodzielnie, chyba że ryzyko jest istotne.',
    },
  },
};

const COLLAB_BOOL_TEXT: Record<'en' | 'pl', Record<string, string>> = {
  en: {
    answerFirst: 'Lead with the answer, result, or decision.',
    plainChatIsDefault: 'Plain chat is the default; use agentic machinery only when it adds real value.',
    respectExplicitTurnInstructions: 'Explicit current-turn instructions override style defaults.',
    avoidRoutinePraise: 'Do not open with automatic praise.',
    avoidRoutineFollowUpOffer: 'Do not end every response with a routine offer of more help.',
    announceOnlyMaterialActions: 'Report progress only for material stages, risks, or state changes.',
    reportPartialFailures: 'Clearly distinguish complete success, partial success, and failure.',
    preferResultOverProcess: 'Present the result before the process.',
  },
  pl: {
    answerFirst: 'Najpierw podaj odpowiedź, wynik lub decyzję.',
    plainChatIsDefault: 'Zwykły czat jest domyślny; agentowe mechanizmy uruchamiaj tylko z realnej potrzeby.',
    respectExplicitTurnInstructions: 'Jawne polecenie z bieżącej wiadomości ma pierwszeństwo przed stylem domyślnym.',
    avoidRoutinePraise: 'Nie zaczynaj automatycznie od pochwał.',
    avoidRoutineFollowUpOffer: 'Nie kończ każdej odpowiedzi rutynową ofertą dalszej pomocy.',
    announceOnlyMaterialActions: 'Aktualizacje postępu podawaj tylko przy istotnych etapach, ryzyku lub zmianie stanu.',
    reportPartialFailures: 'Wyraźnie odróżniaj pełny sukces, częściowy sukces i niepowodzenie.',
    preferResultOverProcess: 'Pokazuj wynik przed opisem procesu.',
  },
};

const KNOWLEDGE_TEXT: Record<'en' | 'pl', Record<string, string>> = {
  en: {
    distinguishRawFromSynthesis: 'Distinguish raw source material from your synthesis when that distinction matters.',
    treatMemoryAsFallible: 'Treat remembered context as fallible rather than as authoritative evidence.',
    surfaceSourceConflicts: 'Surface meaningful conflicts between sources instead of silently choosing one.',
    preferMaintainedSynthesisForOrientation:
      'Prefer maintained synthesis for orientation, then verify important details against primary material.',
    requireTraceableClaims: 'Keep externally verifiable claims traceable to supporting evidence.',
  },
  pl: {
    distinguishRawFromSynthesis: 'Odróżniaj surowy materiał źródłowy od własnej syntezy, gdy ma to znaczenie.',
    treatMemoryAsFallible: 'Traktuj zapamiętany kontekst jako omylny, a nie jako rozstrzygający dowód.',
    surfaceSourceConflicts: 'Pokazuj istotne konflikty między źródłami zamiast po cichu wybierać jedno.',
    preferMaintainedSynthesisForOrientation:
      'Do orientacji preferuj utrzymywaną syntezę, a ważne szczegóły sprawdzaj w materiale pierwotnym.',
    requireTraceableClaims: 'Utrzymuj zewnętrznie weryfikowalne twierdzenia w formie możliwej do prześledzenia do dowodów.',
  },
};

export function renderInstructions(profile: Profile, language: 'auto' | 'en' | 'pl' = 'auto'): string {
  const lang: 'en' | 'pl' =
    language !== 'auto'
      ? language
      : profile.locale.toLowerCase().startsWith('pl')
        ? 'pl'
        : 'en';
  const lines: string[] = [];

  lines.push(BASE_TEXT[lang][profile.personality.base] ?? BASE_TEXT.en.default);
  lines.push(CORE_TEXT[lang]);

  const intensity = profile.personality.intensity ?? 1;
  if (BASE_INTENSITY_TEXT[lang][intensity]) {
    lines.push(BASE_INTENSITY_TEXT[lang][intensity]);
  }

  const activeModifiers = Object.entries(profile.personality.modifiers)
    .filter((entry): entry is [string, number] => entry[1] !== null && entry[1] > 0)
    .sort((a, b) => (b[1] !== a[1] ? b[1] - a[1] : a[0].localeCompare(b[0])));

  for (const [name, lvl] of activeModifiers) {
    const line = MODIFIERS_TEXT[lang][name]?.[lvl];
    if (line) lines.push(line);
  }

  const adapt = profile.personality.adaptation;
  if (adapt.followUserRegister) {
    lines.push(
      lang === 'en'
        ? "Match the user's register without copying mistakes, hostility, or unsafe behavior."
        : 'Dopasuj rejestr do użytkownika bez kopiowania błędów, agresji ani ryzykownego zachowania.',
    );
  }
  if (adapt.preserveRequestedArtifactStyle) {
    lines.push(
      lang === 'en'
        ? 'The requested artifact style outranks conversational personality.'
        : 'Styl zamawianego artefaktu ma pierwszeństwo przed osobowością rozmowy.',
    );
  }
  if (adapt.reduceHumorInSeriousContexts) {
    lines.push(
      lang === 'en'
        ? 'Reduce humor in serious, risky, or sensitive contexts.'
        : 'Ogranicz humor w kontekstach poważnych, ryzykownych lub wrażliwych.',
    );
  }
  if (adapt.mirrorLanguage) {
    lines.push(
      lang === 'en'
        ? "Reply in the user's language unless asked otherwise."
        : 'Odpowiadaj w języku użytkownika, chyba że poprosi inaczej.',
    );
  }
  if (adapt.allowCasualProfanity) {
    lines.push(
      lang === 'en'
        ? 'Mild profanity may be used naturally in casual chat, but not automatically in formal artifacts.'
        : 'W luźnym czacie dopuszczalne są naturalne, łagodne przekleństwa, ale nie przenoś ich automatycznie do formalnych artefaktów.',
    );
  }

  const collab = profile.collaboration;
  for (const key of ['preamble', 'initiative', 'verification', 'questionPolicy', 'assumptionPolicy'] as const) {
    const val = collab[key];
    const line = COLLAB_ENUM_TEXT[lang][key]?.[val];
    if (line) lines.push(line);
  }

  for (const key of [
    'answerFirst',
    'plainChatIsDefault',
    'respectExplicitTurnInstructions',
    'avoidRoutinePraise',
    'avoidRoutineFollowUpOffer',
    'announceOnlyMaterialActions',
    'reportPartialFailures',
    'preferResultOverProcess',
  ] as const) {
    if (collab[key]) {
      const line = COLLAB_BOOL_TEXT[lang][key];
      if (line) lines.push(line);
    }
  }

  const knowledge = profile.knowledge;
  for (const key of [
    'distinguishRawFromSynthesis',
    'treatMemoryAsFallible',
    'surfaceSourceConflicts',
    'preferMaintainedSynthesisForOrientation',
    'requireTraceableClaims',
  ] as const) {
    if (knowledge[key]) {
      const line = KNOWLEDGE_TEXT[lang][key];
      if (line) lines.push(line);
    }
  }

  const output = profile.output;
  if (output.preferShortParagraphs) {
    lines.push(lang === 'en' ? 'Prefer short paragraphs.' : 'Preferuj krótkie akapity.');
  }
  if (output.tables === 'avoid') {
    lines.push(lang === 'en' ? 'Avoid tables unless required.' : 'Unikaj tabel, chyba że są wymagane.');
  } else if (output.tables === 'prefer') {
    lines.push(
      lang === 'en'
        ? 'Prefer tables when they make comparisons clearer.'
        : 'Preferuj tabele, gdy ułatwiają porównania.',
    );
  }
  if (output.codeExamples === 'minimal') {
    lines.push(lang === 'en' ? 'Keep code examples minimal.' : 'Przykłady kodu utrzymuj minimalne.');
  } else if (output.codeExamples === 'runnable') {
    lines.push(lang === 'en' ? 'Prefer runnable code examples.' : 'Preferuj uruchamialne przykłady kodu.');
  } else if (output.codeExamples === 'explanatory') {
    lines.push(
      lang === 'en'
        ? 'Use explanatory code examples with enough context to understand them.'
        : 'Podawaj objaśniające przykłady kodu z kontekstem potrzebnym do zrozumienia.',
    );
  }
  if (output.citations === 'platformDefault') {
    lines.push(
      lang === 'en'
        ? "Follow the platform's normal citation behavior."
        : 'Stosuj standardowe zasady cytowania danej platformy.',
    );
  } else if (output.citations === 'whenAvailable') {
    lines.push(
      lang === 'en'
        ? 'Use citations when reliable source references are available.'
        : 'Używaj cytowań, gdy dostępne są wiarygodne odniesienia do źródeł.',
    );
  } else if (output.citations === 'requiredForExternalFacts') {
    lines.push(
      lang === 'en'
        ? 'Support external factual claims with citations.'
        : 'Zewnętrzne twierdzenia faktyczne popieraj cytowaniami.',
    );
  }
  if (output.defaultFormat !== 'prose') {
    lines.push(
      lang === 'en'
        ? `Default to ${output.defaultFormat} output when the user does not request another format.`
        : `Domyślnie używaj formatu \`${output.defaultFormat}\`, jeśli użytkownik nie poprosi o inny.`,
    );
  }
  if (output.maxHeadingDepth !== null) {
    lines.push(
      lang === 'en'
        ? `Do not exceed heading depth ${output.maxHeadingDepth}.`
        : `Nie przekraczaj ${output.maxHeadingDepth}. poziomu nagłówków.`,
    );
  }

  lines.push(
    lang === 'en'
      ? 'This profile does not grant tools, credentials, network access, permissions, or authority to change external state.'
      : 'Ten profil nie przyznaje narzędzi, danych dostępowych, sieci, uprawnień ani prawa do zmiany zewnętrznego stanu.',
  );

  return lines.map((l) => `- ${l}`).join('\n') + '\n';
}

export function dumpProfileYaml(profile: Profile): string {
  const lines: string[] = [
    '# yaml-language-server: $schema=https://raw.githubusercontent.com/trvny/.ai/main/schema/style-profile.schema.json',
    `schemaVersion: ${JSON.stringify(profile.schemaVersion)}`,
    `id: ${JSON.stringify(profile.id)}`,
    `locale: ${JSON.stringify(profile.locale)}`,
    '',
    'personality:',
    `  base: ${JSON.stringify(profile.personality.base)}`,
    `  intensity: ${profile.personality.intensity ?? 'null'}`,
  ];

  const mods = Object.entries(profile.personality.modifiers);
  if (mods.length === 0) {
    lines.push('  modifiers: {}');
  } else {
    lines.push('  modifiers:');
    for (const [k, v] of mods) {
      lines.push(`    ${JSON.stringify(k)}: ${v ?? 'null'}`);
    }
  }

  const adapt = profile.personality.adaptation;
  lines.push('  adaptation:');
  lines.push(`    followUserRegister: ${adapt.followUserRegister}`);
  lines.push(`    preserveRequestedArtifactStyle: ${adapt.preserveRequestedArtifactStyle}`);
  lines.push(`    reduceHumorInSeriousContexts: ${adapt.reduceHumorInSeriousContexts}`);
  lines.push(`    mirrorLanguage: ${adapt.mirrorLanguage}`);
  lines.push(`    allowCasualProfanity: ${adapt.allowCasualProfanity}`);
  lines.push('');

  const c = profile.collaboration;
  lines.push('collaboration:');
  lines.push(`  preamble: ${JSON.stringify(c.preamble)}`);
  lines.push(`  initiative: ${JSON.stringify(c.initiative)}`);
  lines.push(`  verification: ${JSON.stringify(c.verification)}`);
  lines.push(`  questionPolicy: ${JSON.stringify(c.questionPolicy)}`);
  lines.push(`  assumptionPolicy: ${JSON.stringify(c.assumptionPolicy)}`);
  lines.push(`  answerFirst: ${c.answerFirst}`);
  lines.push(`  plainChatIsDefault: ${c.plainChatIsDefault}`);
  lines.push(`  respectExplicitTurnInstructions: ${c.respectExplicitTurnInstructions}`);
  lines.push(`  avoidRoutinePraise: ${c.avoidRoutinePraise}`);
  lines.push(`  avoidRoutineFollowUpOffer: ${c.avoidRoutineFollowUpOffer}`);
  lines.push(`  announceOnlyMaterialActions: ${c.announceOnlyMaterialActions}`);
  lines.push(`  reportPartialFailures: ${c.reportPartialFailures}`);
  lines.push(`  preferResultOverProcess: ${c.preferResultOverProcess}`);
  lines.push('');

  const k = profile.knowledge;
  lines.push('knowledge:');
  lines.push(`  distinguishRawFromSynthesis: ${k.distinguishRawFromSynthesis}`);
  lines.push(`  treatMemoryAsFallible: ${k.treatMemoryAsFallible}`);
  lines.push(`  surfaceSourceConflicts: ${k.surfaceSourceConflicts}`);
  lines.push(`  preferMaintainedSynthesisForOrientation: ${k.preferMaintainedSynthesisForOrientation}`);
  lines.push(`  requireTraceableClaims: ${k.requireTraceableClaims}`);
  lines.push('');

  const o = profile.output;
  lines.push('output:');
  lines.push(`  defaultFormat: ${JSON.stringify(o.defaultFormat)}`);
  lines.push(`  maxHeadingDepth: ${o.maxHeadingDepth ?? 'null'}`);
  lines.push(`  preferShortParagraphs: ${o.preferShortParagraphs}`);
  lines.push(`  tables: ${JSON.stringify(o.tables)}`);
  lines.push(`  codeExamples: ${JSON.stringify(o.codeExamples)}`);
  lines.push(`  citations: ${JSON.stringify(o.citations)}`);

  const extEntries = Object.entries(profile.extensions);
  if (extEntries.length > 0) {
    lines.push('');
    lines.push('extensions:');
    for (const [ext, vals] of extEntries) {
      const inner = Object.entries(vals);
      if (inner.length === 0) {
        lines.push(`  ${JSON.stringify(ext)}: {}`);
      } else {
        lines.push(`  ${JSON.stringify(ext)}:`);
        for (const [ik, iv] of inner) {
          lines.push(`    ${JSON.stringify(ik)}: ${JSON.stringify(iv)}`);
        }
      }
    }
  }
  return lines.join('\n') + '\n';
}

export function dumpOverlayYaml(overlay: ProfileOverlay): string {
  const lines: string[] = [
    '# Partial overlay. It is intentionally not a complete standalone profile.',
    `id: ${JSON.stringify(overlay.id)}`,
  ];
  if (overlay.locale) lines.push(`locale: ${JSON.stringify(overlay.locale)}`);

  if (
    overlay.personalityBase ||
    overlay.personalityIntensity !== undefined ||
    (overlay.modifierOverrides && Object.keys(overlay.modifierOverrides).length > 0) ||
    (overlay.adaptationOverrides && Object.keys(overlay.adaptationOverrides).length > 0)
  ) {
    lines.push('', 'personality:');
    if (overlay.personalityBase) lines.push(`  base: ${JSON.stringify(overlay.personalityBase)}`);
    if (overlay.personalityIntensity !== undefined) {
      lines.push(`  intensity: ${overlay.personalityIntensity ?? 'null'}`);
    }
    if (overlay.modifierOverrides && Object.keys(overlay.modifierOverrides).length > 0) {
      lines.push('  modifiers:');
      for (const [k, v] of Object.entries(overlay.modifierOverrides)) {
        lines.push(`    ${JSON.stringify(k)}: ${v ?? 'null'}`);
      }
    }
    if (overlay.adaptationOverrides && Object.keys(overlay.adaptationOverrides).length > 0) {
      lines.push('  adaptation:');
      for (const [k, v] of Object.entries(overlay.adaptationOverrides)) {
        lines.push(`    ${JSON.stringify(k)}: ${v}`);
      }
    }
  }

  if (
    overlay.preamble ||
    overlay.initiative ||
    overlay.verification ||
    overlay.questionPolicy ||
    overlay.assumptionPolicy ||
    (overlay.collabBoolOverrides && Object.keys(overlay.collabBoolOverrides).length > 0)
  ) {
    lines.push('', 'collaboration:');
    if (overlay.preamble) lines.push(`  preamble: ${JSON.stringify(overlay.preamble)}`);
    if (overlay.initiative) lines.push(`  initiative: ${JSON.stringify(overlay.initiative)}`);
    if (overlay.verification) lines.push(`  verification: ${JSON.stringify(overlay.verification)}`);
    if (overlay.questionPolicy) lines.push(`  questionPolicy: ${JSON.stringify(overlay.questionPolicy)}`);
    if (overlay.assumptionPolicy) lines.push(`  assumptionPolicy: ${JSON.stringify(overlay.assumptionPolicy)}`);
    if (overlay.collabBoolOverrides) {
      for (const [k, v] of Object.entries(overlay.collabBoolOverrides)) {
        lines.push(`  ${k}: ${v}`);
      }
    }
  }

  if (overlay.knowledgeOverrides && Object.keys(overlay.knowledgeOverrides).length > 0) {
    lines.push('', 'knowledge:');
    for (const [k, v] of Object.entries(overlay.knowledgeOverrides)) {
      lines.push(`  ${k}: ${v}`);
    }
  }

  if (
    overlay.defaultFormat ||
    overlay.maxHeadingDepth !== undefined ||
    overlay.preferShortParagraphs !== undefined ||
    overlay.tables ||
    overlay.codeExamples ||
    overlay.citations
  ) {
    lines.push('', 'output:');
    if (overlay.defaultFormat) lines.push(`  defaultFormat: ${JSON.stringify(overlay.defaultFormat)}`);
    if (overlay.maxHeadingDepth !== undefined) lines.push(`  maxHeadingDepth: ${overlay.maxHeadingDepth ?? 'null'}`);
    if (overlay.preferShortParagraphs !== undefined) lines.push(`  preferShortParagraphs: ${overlay.preferShortParagraphs}`);
    if (overlay.tables) lines.push(`  tables: ${JSON.stringify(overlay.tables)}`);
    if (overlay.codeExamples) lines.push(`  codeExamples: ${JSON.stringify(overlay.codeExamples)}`);
    if (overlay.citations) lines.push(`  citations: ${JSON.stringify(overlay.citations)}`);
  }

  if (overlay.customNote) {
    lines.push('', 'extensions:', '  local:', `    note: ${JSON.stringify(overlay.customNote)}`);
  }
  return lines.join('\n') + '\n';
}

export const STYLE_PROFILE_SCHEMA_JSON = `{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$comment": "style-profile.schema.json (Schema Version 0.2)",
  "title": "StyleProfile",
  "type": "object",
  "required": ["schemaVersion", "id", "locale", "personality", "collaboration"],
  "properties": {
    "schemaVersion": { "type": "string", "const": "0.2" },
    "id": { "type": "string" },
    "locale": { "type": "string" },
    "personality": {
      "type": "object",
      "properties": {
        "base": { "enum": ["default", "professional", "friendly", "honest", "whimsical", "concise", "cynical"] },
        "intensity": { "type": ["integer", "null"], "minimum": 0, "maximum": 3 }
      }
    }
  }
}`;

export function simulatePlaygroundReply(prompt: string, profile: Profile): { text: string; notes: string[] } {
  const mods = profile.personality.modifiers;
  const base = profile.personality.base;
  const intensity = profile.personality.intensity ?? 1;
  const isConcise = base === 'concise' || (mods.concise ?? 0) >= 2;
  const isTechnical = (mods.technical ?? 0) >= 2;
  const isCritical = (mods.critical ?? 0) >= 2 || base === 'cynical';
  const isEducational = (mods.educational ?? 0) >= 2;
  const isPolish = profile.locale.toLowerCase().startsWith('pl');

  const notes: string[] = [
    `Base voice: ${base} (intensity ${intensity}/3)`,
    `Verification: ${profile.collaboration.verification}`,
    `Initiative: ${profile.collaboration.initiative}`,
    `Code examples: ${profile.output.codeExamples}`,
  ];
  const activeModNames = Object.entries(mods)
    .filter(([, v]) => (v ?? 0) > 0)
    .map(([k, v]) => `${k}:${v}`);
  if (activeModNames.length > 0) {
    notes.push(`Modifiers: ${activeModNames.join(', ')}`);
  }

  const lower = prompt.toLowerCase();
  let body = '';

  if (isPolish) {
    if (lower.includes('submodule') || lower.includes('git')) {
      body = isConcise
        ? 'Submoduł `.ai` przypina konkretny commit źródłowy, dzięki czemu zmiany downstream pozostają powtarzalne i nie duplikują definicji.'
        : 'W `.ai` submoduł Git pozwala zachować kanoniczne profile i schematy w repozytorium źródłowym, podczas gdy lokalny overlay nadpisuje tylko prywatne ustawienia.';
    } else {
      body = `Odpowiedź wygenerowana lokalnie zgodnie z profilem \`${profile.id}\` (tryb weryfikacji: ${profile.collaboration.verification}).`;
    }
  } else {
    if (lower.includes('submodule')) {
      body = isConcise
        ? 'Pin `.ai` as an explicit upstream submodule commit; keep private overlays downstream rather than vendoring a copy.'
        : 'A Git submodule lets Aistee reference `trvny/.ai` at an immutable commit SHA. Base schemas and profiles stay canonical upstream while local overlays override only what your workspace needs.';
    } else if (lower.includes('review') || lower.includes('pr')) {
      body = isCritical
        ? '1. Missing boundary validation on user-supplied URLs.\n2. Silent fallback between billing routes violates explicit funding boundaries.\n3. Fix: enforce explicit route selection and add deterministic tests before merging.'
        : 'PR review checklist: verify input boundaries, check error-state recovery, and ensure provider credentials stay isolated from logs and exports.';
    } else if (lower.includes('startup') || lower.includes('wrapper')) {
      body = isCritical
        ? 'Thin API wrappers have zero moat. Unless you own workflow state, offline/local tooling, or verifiable security boundaries, distribution and margin compression will kill it.'
        : 'An AI product needs durable local workflow value—such as multi-provider routing, persistent workspaces, or verifiable tool boundaries—rather than a single-provider prompt skin.';
    } else if (lower.includes('overlay') || lower.includes('merge')) {
      body = isTechnical
        ? 'Overlay composition merges mappings recursively (`modifiers`, `adaptation`, `knowledge`), replaces scalar fields (`base`, `intensity`, `verification`), and treats `null` as explicit key removal.'
        : 'Overlays apply layer-by-layer on top of the Base Default Profile: scalar fields overwrite earlier values, while nested maps merge key-by-key.';
    } else {
      body = isEducational
        ? `Let’s break down "${prompt.trim()}" from first principles:\n1. Clarify the core invariant.\n2. Separate raw evidence from synthesis.\n3. Verify assumptions before acting.`
        : isConcise
          ? `Direct answer for "${prompt.trim()}": profile \`${profile.id}\` is active with ${profile.collaboration.verification} verification and ${profile.output.codeExamples} code examples.`
          : `Testing profile \`${profile.id}\` (${base}, intensity ${intensity}/3) against your prompt: "${prompt.trim()}". Responses follow your ${profile.collaboration.assumptionPolicy} assumption policy and ${profile.collaboration.questionPolicy} question threshold.`;
    }
  }

  if (!profile.collaboration.avoidRoutinePraise && !isConcise) {
    body = `Great question! ${body}`;
  }
  if (!profile.collaboration.avoidRoutineFollowUpOffer && !isConcise) {
    body = `${body}\n\nLet me know if you want to explore another variation.`;
  }

  return { text: body, notes };
}
