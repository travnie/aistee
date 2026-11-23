export type TextFindingSeverity = 'HIGH' | 'MEDIUM' | 'LOW';

export interface TextSafetyFinding {
  severity: TextFindingSeverity;
  kind: string;
  label: string;
  detail: string;
  offset: number;
  length: number;
  line: number;
  column: number;
}

export interface TextInspectionResult {
  findings: TextSafetyFinding[];
  detectedCount: number;
  truncated: boolean;
  highCount: number;
  mediumCount: number;
  lowCount: number;
}

const MAX_FINDINGS = 500;
const MIN_BASE64_CANDIDATE_CHARS = 20;
const MAX_BASE64_DECODE_CHARS = 65_536;

interface RawFinding {
  severity: TextFindingSeverity;
  kind: string;
  label: string;
  detail: string;
  offset: number;
  length: number;
}

const BIDI_ORDER_DETAIL = 'Bidi control can make source render in a misleading order.';
const BIDI_ISOLATION_DETAIL = 'Bidi isolation control can conceal source ordering.';
const INVISIBLE_FORMATTING_DETAIL = 'Invisible formatting character.';

const SPECIAL_CHARACTERS = new Map<number, { severity: TextFindingSeverity; label: string; detail: string }>([
  [0x00ad, { severity: 'MEDIUM', label: 'Soft hyphen', detail: 'Invisible discretionary hyphen.' }],
  [0x034f, { severity: 'MEDIUM', label: 'Combining grapheme joiner', detail: 'Invisible combining control.' }],
  [0x061c, { severity: 'MEDIUM', label: 'Arabic letter mark', detail: 'Invisible bidi-affecting mark.' }],
  [0x200b, { severity: 'MEDIUM', label: 'Zero-width space', detail: 'Invisible separator often used for Unicode smuggling.' }],
  [0x200c, { severity: 'LOW', label: 'Zero-width non-joiner', detail: 'Can be legitimate in some writing systems; review unexpected use.' }],
  [0x200d, { severity: 'LOW', label: 'Zero-width joiner', detail: 'Used legitimately in scripts and emoji; review unexpected use.' }],
  [0x200e, { severity: 'MEDIUM', label: 'Left-to-right mark', detail: 'Invisible bidirectional text mark.' }],
  [0x200f, { severity: 'MEDIUM', label: 'Right-to-left mark', detail: 'Invisible bidirectional text mark.' }],
  [0x202a, { severity: 'HIGH', label: 'Left-to-right embedding', detail: BIDI_ORDER_DETAIL }],
  [0x202b, { severity: 'HIGH', label: 'Right-to-left embedding', detail: BIDI_ORDER_DETAIL }],
  [0x202c, { severity: 'HIGH', label: 'Pop directional formatting', detail: 'Bidi control terminator.' }],
  [0x202d, { severity: 'HIGH', label: 'Left-to-right override', detail: BIDI_ORDER_DETAIL }],
  [0x202e, { severity: 'HIGH', label: 'Right-to-left override', detail: BIDI_ORDER_DETAIL }],
  [0x2060, { severity: 'MEDIUM', label: 'Word joiner', detail: INVISIBLE_FORMATTING_DETAIL }],
  [0x2061, { severity: 'MEDIUM', label: 'Function application', detail: INVISIBLE_FORMATTING_DETAIL }],
  [0x2062, { severity: 'MEDIUM', label: 'Invisible times', detail: INVISIBLE_FORMATTING_DETAIL }],
  [0x2063, { severity: 'MEDIUM', label: 'Invisible separator', detail: INVISIBLE_FORMATTING_DETAIL }],
  [0x2064, { severity: 'MEDIUM', label: 'Invisible plus', detail: INVISIBLE_FORMATTING_DETAIL }],
  [0x2066, { severity: 'HIGH', label: 'Left-to-right isolate', detail: BIDI_ISOLATION_DETAIL }],
  [0x2067, { severity: 'HIGH', label: 'Right-to-left isolate', detail: BIDI_ISOLATION_DETAIL }],
  [0x2068, { severity: 'HIGH', label: 'First-strong isolate', detail: BIDI_ISOLATION_DETAIL }],
  [0x2069, { severity: 'HIGH', label: 'Pop directional isolate', detail: BIDI_ISOLATION_DETAIL }],
  [0xfeff, { severity: 'MEDIUM', label: 'Zero-width no-break space / BOM', detail: 'Unexpected inside document text.' }],
  [0xfffd, { severity: 'MEDIUM', label: 'Replacement character', detail: 'May indicate earlier decoding or copy/paste data loss.' }],
]);

for (let cp = 0x206a; cp <= 0x206f; cp++) {
  SPECIAL_CHARACTERS.set(cp, {
    severity: 'HIGH',
    label: 'Deprecated bidi control',
    detail: 'Deprecated invisible directional control.',
  });
}

for (const cp of [
  0x00a0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x202f,
  0x205f, 0x3000,
]) {
  SPECIAL_CHARACTERS.set(cp, {
    severity: 'LOW',
    label: 'Unusual Unicode space',
    detail: 'Whitespace that can be hard to distinguish from an ordinary space.',
  });
}

const INJECTION_PATTERNS: RegExp[] = [
  /\b(?:ignore|disregard|forget)\b[\s\S]{0,90}\b(?:previous|prior|above|system|developer)\b[\s\S]{0,60}\b(?:instruction|instructions|prompt|message|messages)\b/gi,
  /\b(?:reveal|print|show|output|expose|dump)\b[\s\S]{0,60}\b(?:system|developer)\s+(?:prompt|message|instructions?)\b/gi,
  /\b(?:do not|don't)\s+(?:tell|show|inform)\s+(?:the\s+)?user\b/gi,
];

function codePointLabel(cp: number): string {
  return `U+${cp.toString(16).toUpperCase().padStart(cp <= 0xffff ? 4 : 6, '0')}`;
}

function makeLineStarts(text: string): number[] {
  const starts = [0];
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '\r') {
      const isCrLf = i + 1 < text.length && text[i + 1] === '\n';
      starts.push(i + (isCrLf ? 2 : 1));
      i += isCrLf ? 2 : 1;
    } else if (ch === '\n' || ch === '\u2028' || ch === '\u2029') {
      starts.push(i + 1);
      i += 1;
    } else {
      i += 1;
    }
  }
  return starts;
}

function lineColForOffset(text: string, starts: number[], offset: number): { line: number; column: number } {
  let low = 0;
  let high = starts.length - 1;
  while (low <= high) {
    const mid = (low + high) >>> 1;
    if (starts[mid] <= offset) low = mid + 1;
    else high = mid - 1;
  }
  const lineIndex = Math.max(0, high);
  let col = 1;
  let cursor = starts[lineIndex];
  while (cursor < offset && cursor < text.length) {
    const cp = text.codePointAt(cursor) ?? 0;
    cursor += cp > 0xffff ? 2 : 1;
    col += 1;
  }
  return { line: lineIndex + 1, column: col };
}

function isLatin(cp: number): boolean {
  return (cp >= 0x41 && cp <= 0x5a) || (cp >= 0x61 && cp <= 0x7a) || (cp >= 0x00c0 && cp <= 0x024f);
}
function isCyrillic(cp: number): boolean {
  return (cp >= 0x0400 && cp <= 0x052f) || (cp >= 0x2de0 && cp <= 0x2dff);
}
function isGreek(cp: number): boolean {
  return (cp >= 0x0370 && cp <= 0x03ff) || (cp >= 0x1f00 && cp <= 0x1fff);
}

export function inspectTextSafety(text: string): TextInspectionResult {
  if (!text) {
    return { findings: [], detectedCount: 0, truncated: false, highCount: 0, mediumCount: 0, lowCount: 0 };
  }

  const high: RawFinding[] = [];
  const medium: RawFinding[] = [];
  const low: RawFinding[] = [];
  let detectedCount = 0;

  const add = (f: RawFinding) => {
    detectedCount += 1;
    const bucket = f.severity === 'HIGH' ? high : f.severity === 'MEDIUM' ? medium : low;
    if (bucket.length < MAX_FINDINGS) bucket.push(f);
  };

  // Character, variation-selector, tag, and mixed-script scan
  let offset = 0;
  let variationStart = -1;
  let variationCount = 0;
  let tokenStart = -1;
  let tokenEnd = -1;
  let hasLatin = false;
  let hasCyrillic = false;
  let hasGreek = false;

  const flushToken = () => {
    if (tokenStart >= 0 && hasLatin && (hasCyrillic || hasGreek)) {
      const preview = text.slice(tokenStart, Math.min(tokenEnd, tokenStart + 80));
      add({
        severity: 'MEDIUM',
        kind: 'confusable',
        label: 'Mixed-script token',
        detail: `Latin mixed with Cyrillic or Greek can create look-alike identifiers: "${preview}".`,
        offset: tokenStart,
        length: tokenEnd - tokenStart,
      });
    }
    tokenStart = -1;
    tokenEnd = -1;
    hasLatin = false;
    hasCyrillic = false;
    hasGreek = false;
  };

  const flushVariation = (end: number) => {
    if (variationCount >= 4) {
      add({
        severity: 'MEDIUM',
        kind: 'marker-carrier',
        label: 'Variation-selector sequence',
        detail: `${variationCount} consecutive variation selectors.`,
        offset: variationStart,
        length: end - variationStart,
      });
    }
    variationStart = -1;
    variationCount = 0;
  };

  while (offset < text.length) {
    const cp = text.codePointAt(offset)!;
    const width = cp > 0xffff ? 2 : 1;

    const isVariation = (cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xe0100 && cp <= 0xe01ef);
    if (isVariation) {
      if (variationStart < 0) variationStart = offset;
      variationCount += 1;
    } else {
      flushVariation(offset);
    }

    if (cp >= 0xe0000 && cp <= 0xe007f) {
      const tagStart = offset;
      let count = 0;
      let payload = '';
      while (offset < text.length) {
        const tcp = text.codePointAt(offset)!;
        if (tcp < 0xe0000 || tcp > 0xe007f) break;
        const ascii = tcp - 0xe0000;
        if (ascii >= 0x20 && ascii <= 0x7e && payload.length < 180) {
          payload += String.fromCharCode(ascii);
        }
        count += 1;
        offset += tcp > 0xffff ? 2 : 1;
      }
      add({
        severity: 'HIGH',
        kind: 'marker-carrier',
        label: 'Unicode tag sequence',
        detail: payload ? `Hidden tag payload: "${payload}"` : `${count} invisible Unicode tag characters.`,
        offset: tagStart,
        length: offset - tagStart,
      });
      flushToken();
      continue;
    }

    const special = SPECIAL_CHARACTERS.get(cp);
    if (special) {
      add({
        severity: special.severity,
        kind: 'invisible',
        label: special.label,
        detail: `${special.detail} ${codePointLabel(cp)}`,
        offset,
        length: width,
      });
    } else if ((cp < 0x20 && cp !== 0x09 && cp !== 0x0a && cp !== 0x0d) || (cp >= 0x7f && cp <= 0x9f)) {
      add({
        severity: 'HIGH',
        kind: 'control',
        label: 'Control character',
        detail: `Unexpected non-printing control ${codePointLabel(cp)}.`,
        offset,
        length: width,
      });
    } else if ((cp >= 0xfdd0 && cp <= 0xfdef) || (cp & 0xffff) === 0xfffe || (cp & 0xffff) === 0xffff) {
      add({
        severity: 'HIGH',
        kind: 'invalid-unicode',
        label: 'Unicode noncharacter',
        detail: `${codePointLabel(cp)} is reserved as a noncharacter.`,
        offset,
        length: width,
      });
    } else if ((cp >= 0xe000 && cp <= 0xf8ff) || (cp >= 0xf0000 && cp <= 0xffffd)) {
      add({
        severity: 'LOW',
        kind: 'marker-carrier',
        label: 'Private-use character',
        detail: `${codePointLabel(cp)} has no standardized meaning.`,
        offset,
        length: width,
      });
    }

    if (
      isLatin(cp) ||
      isCyrillic(cp) ||
      isGreek(cp) ||
      (cp >= 0x30 && cp <= 0x39) ||
      cp === 0x5f ||
      cp === 0x2d
    ) {
      if (tokenStart < 0) tokenStart = offset;
      tokenEnd = offset + width;
      if (isLatin(cp)) hasLatin = true;
      if (isCyrillic(cp)) hasCyrillic = true;
      if (isGreek(cp)) hasGreek = true;
    } else {
      flushToken();
    }

    offset += width;
  }
  flushVariation(text.length);
  flushToken();

  // Prompt injection heuristics
  for (const re of INJECTION_PATTERNS) {
    re.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
      add({
        severity: 'MEDIUM',
        kind: 'prompt-injection',
        label: 'Prompt-injection-like instruction',
        detail: `Matched instruction: "${m[0].slice(0, 120)}". Heuristic match only.`,
        offset: m.index,
        length: m[0].length,
      });
    }
  }

  // Base64 encoded payload scan
  const b64Regex = /[A-Za-z0-9+/_-]{20,}={0,2}/g;
  let b64Match: RegExpExecArray | null;
  while ((b64Match = b64Regex.exec(text)) !== null) {
    const token = b64Match[0];
    if (token.length > MAX_BASE64_DECODE_CHARS) {
      add({
        severity: 'MEDIUM',
        kind: 'marker-carrier',
        label: 'Large Base64 carrier',
        detail: `Encoded run is ${token.length} characters; hidden content may exist inside.`,
        offset: b64Match.index,
        length: token.length,
      });
      continue;
    }
    try {
      const normalized = token.replace(/-/g, '+').replace(/_/g, '/');
      if (normalized.length % 4 === 1) continue;
      const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
      const decoded = atob(padded);
      for (const pat of INJECTION_PATTERNS) {
        pat.lastIndex = 0;
        const inner = pat.exec(decoded);
        if (inner) {
          add({
            severity: 'HIGH',
            kind: 'prompt-injection',
            label: 'Encoded prompt-like instruction',
            detail: `Base64 decoded payload: "${inner[0].slice(0, 120)}"`,
            offset: b64Match.index,
            length: token.length,
          });
          break;
        }
      }
    } catch {
      // ignore non-base64 runs
    }
  }

  const retained: RawFinding[] = [];
  retained.push(...high.slice(0, MAX_FINDINGS));
  if (retained.length < MAX_FINDINGS) retained.push(...medium.slice(0, MAX_FINDINGS - retained.length));
  if (retained.length < MAX_FINDINGS) retained.push(...low.slice(0, MAX_FINDINGS - retained.length));

  const starts = makeLineStarts(text);
  const sevRank: Record<TextFindingSeverity, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };
  const located: TextSafetyFinding[] = retained
    .map((f) => {
      const pos = lineColForOffset(text, starts, f.offset);
      return { ...f, line: pos.line, column: pos.column };
    })
    .sort((a, b) => sevRank[a.severity] - sevRank[b.severity] || a.offset - b.offset);

  return {
    findings: located,
    detectedCount,
    truncated: detectedCount > located.length,
    highCount: located.filter((f) => f.severity === 'HIGH').length,
    mediumCount: located.filter((f) => f.severity === 'MEDIUM').length,
    lowCount: located.filter((f) => f.severity === 'LOW').length,
  };
}
