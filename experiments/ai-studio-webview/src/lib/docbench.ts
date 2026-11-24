import { encode } from 'gpt-tokenizer/encoding/o200k_base';

export function countO200kTokens(text: string): number {
  if (!text) return 0;
  try {
    return encode(text).length;
  } catch {
    return Math.ceil(text.length / 4);
  }
}

export type LineEndingStyle = 'NONE' | 'LF' | 'CRLF' | 'CR' | 'MIXED';

export interface LineEndingCounts {
  lf: number;
  crlf: number;
  cr: number;
  style: LineEndingStyle;
}

export function detectLineEndings(text: string): LineEndingCounts {
  let lf = 0;
  let crlf = 0;
  let cr = 0;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '\r') {
      if (text[i + 1] === '\n') {
        crlf++;
        i++;
      } else {
        cr++;
      }
    } else if (text[i] === '\n') {
      lf++;
    }
  }
  const activeKinds = [lf > 0, crlf > 0, cr > 0].filter(Boolean).length;
  let style: LineEndingStyle = 'NONE';
  if (activeKinds > 1) style = 'MIXED';
  else if (crlf > 0) style = 'CRLF';
  else if (lf > 0) style = 'LF';
  else if (cr > 0) style = 'CR';
  return { lf, crlf, cr, style };
}

export function normalizeLineEndings(text: string, target: 'LF' | 'CRLF'): string {
  const lfText = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  return target === 'CRLF' ? lfText.replace(/\n/g, '\r\n') : lfText;
}

export interface DocumentDiagnostic {
  kind: 'MIXED_LINE_ENDINGS' | 'NUL_CHARACTER' | 'UNCLOSED_FENCE' | 'HEADING_JUMP';
  severity: 'INFO' | 'WARNING' | 'ERROR';
  message: string;
  line: number;
}

export function diagnoseMarkdownDocument(text: string): DocumentDiagnostic[] {
  const diags: DocumentDiagnostic[] = [];
  const endings = detectLineEndings(text);
  if (endings.style === 'MIXED') {
    diags.push({
      kind: 'MIXED_LINE_ENDINGS',
      severity: 'WARNING',
      message: `Document mixes line endings (${endings.lf} LF, ${endings.crlf} CRLF, ${endings.cr} CR).`,
      line: 1,
    });
  }
  const nulIdx = text.indexOf('\u0000');
  if (nulIdx >= 0) {
    const line = text.slice(0, nulIdx).split('\n').length;
    diags.push({
      kind: 'NUL_CHARACTER',
      severity: 'WARNING',
      message: 'NUL character found in text; verify that this is not binary input.',
      line,
    });
  }

  // Check Markdown code fences and heading depth jumps
  const lines = text.split(/\r?\n/);
  let openFence: { marker: string; line: number } | null = null;
  let lastHeadingLevel = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const fenceMatch = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (fenceMatch) {
      const marker = fenceMatch[1][0];
      if (!openFence) {
        openFence = { marker, line: i + 1 };
      } else if (openFence.marker === marker) {
        openFence = null;
      }
      continue;
    }
    if (!openFence) {
      const hMatch = line.match(/^\s{0,3}(#{1,6})\s+/);
      if (hMatch) {
        const level = hMatch[1].length;
        if (lastHeadingLevel > 0 && level > lastHeadingLevel + 1) {
          diags.push({
            kind: 'HEADING_JUMP',
            severity: 'INFO',
            message: `Heading jumps from h${lastHeadingLevel} to h${level}.`,
            line: i + 1,
          });
        }
        lastHeadingLevel = level;
      }
    }
  }

  if (openFence) {
    diags.push({
      kind: 'UNCLOSED_FENCE',
      severity: 'ERROR',
      message: `Unclosed code fence opened at line ${openFence.line}.`,
      line: openFence.line,
    });
  }

  return diags;
}

export function repairMarkdownStructure(text: string): {
  text: string;
  changed: boolean;
  repairedIssueCount: number;
} {
  const lines = text.split(/\r?\n/);
  let openFenceMarker: string | null = null;
  let openFenceLen = 3;
  let repaired = 0;

  for (const line of lines) {
    const fenceMatch = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (fenceMatch) {
      const marker = fenceMatch[1][0];
      if (!openFenceMarker) {
        openFenceMarker = marker;
        openFenceLen = fenceMatch[1].length;
      } else if (openFenceMarker === marker && fenceMatch[1].length >= openFenceLen) {
        openFenceMarker = null;
      }
    }
  }

  let out = text;
  if (openFenceMarker) {
    const closing = openFenceMarker.repeat(openFenceLen);
    out = out.endsWith('\n') ? `${out}${closing}\n` : `${out}\n${closing}\n`;
    repaired += 1;
  }

  return {
    text: out,
    changed: out !== text,
    repairedIssueCount: repaired,
  };
}

export function formatJsonText(text: string): { ok: true; text: string } | { ok: false; error: string } {
  try {
    // Validate syntax without serializing JS numbers or collapsing duplicate keys.
    JSON.parse(text);
    const tokens = text.match(/"(?:\\.|[^"\\])*"|[{}\[\],:]|[^\s{}\[\],:"]+/g) ?? [];
    let depth = 0;
    let output = '';
    const newline = () => '\n' + '  '.repeat(depth);
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i];
      const next = tokens[i + 1];
      const previous = tokens[i - 1];
      if (token === '{' || token === '[') {
        output += token;
        depth++;
        if (next !== '}' && next !== ']') output += newline();
      } else if (token === '}' || token === ']') {
        depth--;
        if (previous !== '{' && previous !== '[') output += newline();
        output += token;
      } else if (token === ',') {
        output += ',' + newline();
      } else {
        output += token === ':' ? ': ' : token;
      }
    }
    return { ok: true, text: output + '\n' };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : 'Invalid JSON.' };
  }
}

export interface MarkdownTableData {
  headers: string[];
  rows: string[][];
}

export function markdownTableToCsv(table: MarkdownTableData): string {
  const escapeCell = (val: string) => {
    const cleaned = val.replace(/\r?\n/g, ' ').trim();
    if (/[",\n]/.test(cleaned)) {
      return `"${cleaned.replace(/"/g, '""')}"`;
    }
    return cleaned;
  };
  const lines = [table.headers.map(escapeCell).join(',')];
  for (const row of table.rows) {
    lines.push(table.headers.map((_, i) => escapeCell(row[i] ?? '')).join(','));
  }
  return lines.join('\n');
}
