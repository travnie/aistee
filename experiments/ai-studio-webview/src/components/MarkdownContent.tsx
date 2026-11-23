import React, { useMemo, useState } from 'react';
import { Check, Copy, Maximize2, X } from 'lucide-react';
import { markdownTableToCsv, MarkdownTableData } from '../lib/docbench';

interface MarkdownContentProps {
  content: string;
  onCopySuccess?: (label: string) => void;
}

type Block =
  | { type: 'heading'; level: number; text: string }
  | { type: 'code'; lang: string; code: string }
  | { type: 'math'; tex: string }
  | { type: 'table'; table: MarkdownTableData }
  | { type: 'blockquote'; lines: string[] }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }
  | { type: 'hr' }
  | { type: 'paragraph'; text: string };

function isTableSeparator(line: string): boolean {
  const trimmed = line.trim();
  if (!trimmed.includes('|')) return false;
  const cells = trimmed
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim());
  return cells.length > 0 && cells.every((c) => /^:?-{2,}:?$/.test(c));
}

function splitTableRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim());
}

function parseBlocks(src: string): Block[] {
  const lines = src.replace(/\r\n/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    // Blank line
    if (!line.trim()) {
      i++;
      continue;
    }

    // Fenced code block ``` or ~~~
    const fenceMatch = line.match(/^\s{0,3}(`{3,}|~{3,})\s*(.*)$/);
    if (fenceMatch) {
      const fenceChar = fenceMatch[1][0];
      const fenceLen = fenceMatch[1].length;
      const lang = fenceMatch[2].trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length) {
        const endMatch = lines[i].match(new RegExp(`^\\s{0,3}${fenceChar}{${fenceLen},}\\s*$`));
        if (endMatch) {
          i++;
          break;
        }
        codeLines.push(lines[i]);
        i++;
      }
      blocks.push({ type: 'code', lang, code: codeLines.join('\n') });
      continue;
    }

    // Display math $$ ... $$ or \[ ... \]
    if (line.trim().startsWith('$$') || line.trim().startsWith('\\[')) {
      const isBracket = line.trim().startsWith('\\[');
      const closeToken = isBracket ? '\\]' : '$$';
      const trimmed = line.trim();
      if (trimmed.length > 4 && trimmed.endsWith(closeToken)) {
        blocks.push({ type: 'math', tex: trimmed.slice(2, -2).trim() });
        i++;
        continue;
      }
      const mathLines: string[] = [];
      const firstRest = trimmed.slice(2).trim();
      if (firstRest) mathLines.push(firstRest);
      i++;
      while (i < lines.length) {
        const cur = lines[i];
        const endIdx = cur.indexOf(closeToken);
        if (endIdx >= 0) {
          const before = cur.slice(0, endIdx).trim();
          if (before) mathLines.push(before);
          i++;
          break;
        }
        mathLines.push(cur);
        i++;
      }
      blocks.push({ type: 'math', tex: mathLines.join('\n') });
      continue;
    }

    // Horizontal rule
    if (/^\s{0,3}([-*_])\s*(\1\s*){2,}$/.test(line)) {
      blocks.push({ type: 'hr' });
      i++;
      continue;
    }

    // Headings
    const hMatch = line.match(/^\s{0,3}(#{1,6})\s+(.+)$/);
    if (hMatch) {
      blocks.push({ type: 'heading', level: hMatch[1].length, text: hMatch[2].trim() });
      i++;
      continue;
    }

    // Markdown table
    if (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const headers = splitTableRow(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
        rows.push(splitTableRow(lines[i]));
        i++;
      }
      blocks.push({ type: 'table', table: { headers, rows } });
      continue;
    }

    // Blockquote
    if (/^\s{0,3}>/.test(line)) {
      const quoteLines: string[] = [];
      while (i < lines.length && /^\s{0,3}>/.test(lines[i])) {
        quoteLines.push(lines[i].replace(/^\s{0,3}>\s?/, ''));
        i++;
      }
      blocks.push({ type: 'blockquote', lines: quoteLines });
      continue;
    }

    // Unordered list
    if (/^\s{0,3}[-*+]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s{0,3}[-*+]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s{0,3}[-*+]\s+/, '').trim());
        i++;
      }
      blocks.push({ type: 'ul', items });
      continue;
    }

    // Ordered list
    if (/^\s{0,3}\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s{0,3}\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s{0,3}\d+\.\s+/, '').trim());
        i++;
      }
      blocks.push({ type: 'ol', items });
      continue;
    }

    // Paragraph (gather until blank or block start)
    const paraLines: string[] = [line];
    i++;
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !/^\s{0,3}(`{3,}|~{3,}|#{1,6}\s|>|[-*+]\s|\d+\.\s|\$\$|\\\[)/.test(lines[i])
    ) {
      if (lines[i].includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) break;
      paraLines.push(lines[i]);
      i++;
    }
    blocks.push({ type: 'paragraph', text: paraLines.join(' ') });
  }

  return blocks;
}

function renderInline(text: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  // Safe inline tokenizer: links, inline code, inline math $...$, bold, italic
  const tokenRe =
    /(\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)|`([^`]+)`|\$([^$\n]+?)\$|\*\*([^*]+)\*\*|\*([^*\n]+)\*)/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let keyIdx = 0;

  while ((match = tokenRe.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }
    if (match[2] && match[3]) {
      nodes.push(
        <a
          key={`a_${keyIdx++}`}
          href={match[3]}
          target="_blank"
          rel="noopener noreferrer"
          className="text-indigo-600 dark:text-indigo-400 underline underline-offset-2 hover:text-indigo-500"
        >
          {match[2]}
        </a>,
      );
    } else if (match[4] !== undefined) {
      nodes.push(
        <code
          key={`c_${keyIdx++}`}
          className="rounded bg-slate-200/80 dark:bg-slate-800 px-1.5 py-0.5 font-mono text-[12px] text-slate-900 dark:text-sky-300"
        >
          {match[4]}
        </code>,
      );
    } else if (match[5] !== undefined) {
      nodes.push(
        <code
          key={`m_${keyIdx++}`}
          className="rounded bg-indigo-50 dark:bg-indigo-950/60 border border-indigo-200/60 dark:border-indigo-800/60 px-1.5 py-0.5 font-mono text-[12px] text-indigo-800 dark:text-indigo-200"
          title="Inline TeX expression"
        >
          {match[5]}
        </code>,
      );
    } else if (match[6] !== undefined) {
      nodes.push(
        <strong key={`b_${keyIdx++}`} className="font-semibold text-slate-900 dark:text-slate-100">
          {match[6]}
        </strong>,
      );
    } else if (match[7] !== undefined) {
      nodes.push(
        <em key={`i_${keyIdx++}`} className="italic">
          {match[7]}
        </em>,
      );
    }
    lastIndex = tokenRe.lastIndex;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }
  return nodes;
}

export const MarkdownContent: React.FC<MarkdownContentProps> = ({ content, onCopySuccess }) => {
  const blocks = useMemo(() => parseBlocks(content), [content]);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [expandedTable, setExpandedTable] = useState<MarkdownTableData | null>(null);

  const handleCopy = async (id: string, value: string, label: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopiedId(id);
      onCopySuccess?.(label);
      window.setTimeout(() => setCopiedId((cur) => (cur === id ? null : cur)), 1600);
    } catch {
      // clipboard denied
    }
  };

  return (
    <div className="space-y-2.5 text-[14px] leading-relaxed text-slate-800 dark:text-slate-200 break-words">
      {blocks.map((block, idx) => {
        if (block.type === 'heading') {
          const sizes: Record<number, string> = {
            1: 'text-lg font-bold mt-3',
            2: 'text-base font-semibold mt-2.5',
            3: 'text-[15px] font-semibold mt-2',
            4: 'text-sm font-semibold mt-1.5',
            5: 'text-xs font-semibold uppercase tracking-wide mt-1',
            6: 'text-xs font-medium uppercase tracking-wide mt-1',
          };
          return (
            <div key={idx} className={`${sizes[block.level] ?? sizes[3]} font-display text-slate-900 dark:text-slate-100`}>
              {renderInline(block.text)}
            </div>
          );
        }

        if (block.type === 'code') {
          const cid = `code_${idx}`;
          return (
            <div
              key={idx}
              className="rounded-lg border border-slate-200 dark:border-slate-700/80 bg-slate-100 dark:bg-[#0B1120] overflow-hidden"
            >
              <div className="flex items-center justify-between border-b border-slate-200 dark:border-slate-800 px-3 py-1.5 bg-slate-200/60 dark:bg-slate-900/80">
                <span className="font-mono text-[11px] uppercase tracking-wider text-slate-600 dark:text-slate-400">
                  {block.lang || 'source'}
                </span>
                <button
                  type="button"
                  onClick={() => handleCopy(cid, block.code, 'Copied code')}
                  className="inline-flex items-center gap-1 text-[11px] font-medium text-slate-600 dark:text-slate-300 hover:text-indigo-600 dark:hover:text-indigo-400 whitespace-nowrap shrink-0"
                >
                  {copiedId === cid ? <Check className="h-3 w-3 text-emerald-500" /> : <Copy className="h-3 w-3" />}
                  {copiedId === cid ? 'Copied' : 'Copy source'}
                </button>
              </div>
              <pre className="overflow-x-auto p-3 font-mono text-[12.5px] leading-relaxed text-slate-800 dark:text-slate-200">
                <code>{block.code}</code>
              </pre>
            </div>
          );
        }

        if (block.type === 'math') {
          const mid = `math_${idx}`;
          return (
            <div
              key={idx}
              className="rounded-lg border border-indigo-200/70 dark:border-indigo-900/60 bg-indigo-50/50 dark:bg-indigo-950/30 overflow-hidden"
            >
              <div className="flex items-center justify-between px-3 py-1 border-b border-indigo-200/60 dark:border-indigo-900/50">
                <span className="font-mono text-[11px] uppercase tracking-wider text-indigo-700 dark:text-indigo-300">
                  Math TeX
                </span>
                <button
                  type="button"
                  onClick={() => handleCopy(mid, block.tex, 'Copied TeX')}
                  className="inline-flex items-center gap-1 text-[11px] font-medium text-indigo-700 dark:text-indigo-300 hover:underline whitespace-nowrap shrink-0"
                >
                  {copiedId === mid ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />}
                  {copiedId === mid ? 'Copied' : 'Copy TeX'}
                </button>
              </div>
              <pre className="overflow-x-auto p-3 font-mono text-[12.5px] text-indigo-950 dark:text-indigo-100">
                {block.tex}
              </pre>
            </div>
          );
        }

        if (block.type === 'table') {
          const tid = `tbl_${idx}`;
          return (
            <div
              key={idx}
              className="rounded-lg border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-slate-900/60 overflow-hidden"
            >
              <div className="flex items-center justify-between px-3 py-1.5 border-b border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-900">
                <span className="text-[11px] font-medium text-slate-500 dark:text-slate-400 tabular-nums">
                  Table · {block.table.rows.length} rows × {block.table.headers.length} cols
                </span>
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => handleCopy(tid, markdownTableToCsv(block.table), 'Copied CSV')}
                    className="inline-flex items-center gap-1 text-[11px] font-medium text-slate-600 dark:text-slate-300 hover:text-indigo-600 dark:hover:text-indigo-400 whitespace-nowrap shrink-0"
                  >
                    {copiedId === tid ? <Check className="h-3 w-3 text-emerald-500" /> : <Copy className="h-3 w-3" />}
                    {copiedId === tid ? 'Copied CSV' : 'Copy CSV'}
                  </button>
                  <button
                    type="button"
                    onClick={() => setExpandedTable(block.table)}
                    className="inline-flex items-center gap-1 text-[11px] font-medium text-indigo-600 dark:text-indigo-400 hover:underline whitespace-nowrap shrink-0"
                  >
                    <Maximize2 className="h-3 w-3" />
                    Expand
                  </button>
                </div>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse text-[13px]">
                  <thead>
                    <tr className="border-b border-slate-200 dark:border-slate-800 bg-slate-100/70 dark:bg-slate-800/60">
                      {block.table.headers.map((h, hi) => (
                        <th key={hi} className="px-3 py-2 font-semibold text-slate-800 dark:text-slate-100 whitespace-nowrap">
                          {renderInline(h)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {block.table.rows.map((row, ri) => (
                      <tr key={ri} className="border-b last:border-b-0 border-slate-100 dark:border-slate-800/70">
                        {block.table.headers.map((_, ci) => (
                          <td key={ci} className="px-3 py-1.5 text-slate-700 dark:text-slate-300 align-top">
                            {renderInline(row[ci] ?? '')}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          );
        }

        if (block.type === 'blockquote') {
          return (
            <blockquote
              key={idx}
              className="pl-3.5 border-l-2 border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 italic space-y-1"
            >
              {block.lines.map((l, li) => (
                <p key={li}>{renderInline(l)}</p>
              ))}
            </blockquote>
          );
        }

        if (block.type === 'ul') {
          return (
            <ul key={idx} className="list-disc pl-5 space-y-1">
              {block.items.map((item, ii) => (
                <li key={ii}>{renderInline(item)}</li>
              ))}
            </ul>
          );
        }

        if (block.type === 'ol') {
          return (
            <ol key={idx} className="list-decimal pl-5 space-y-1">
              {block.items.map((item, ii) => (
                <li key={ii}>{renderInline(item)}</li>
              ))}
            </ol>
          );
        }

        if (block.type === 'hr') {
          return <hr key={idx} className="border-slate-200 dark:border-slate-800 my-2" />;
        }

        return <p key={idx}>{renderInline(block.text)}</p>;
      })}

      {expandedTable && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setExpandedTable(null)}
        >
          <div
            className="w-full max-w-5xl max-h-[85vh] flex flex-col rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700">
              <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                Table Inspector · {expandedTable.rows.length} rows
              </h3>
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => handleCopy('modal_csv', markdownTableToCsv(expandedTable), 'Copied CSV')}
                  className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 dark:border-slate-600 px-2.5 py-1 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
                >
                  <Copy className="h-3.5 w-3.5" />
                  Copy CSV
                </button>
                <button
                  type="button"
                  onClick={() => setExpandedTable(null)}
                  className="rounded-md p-1 text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800"
                  aria-label="Close table modal"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </div>
            <div className="overflow-auto p-4">
              <table className="w-full text-left border-collapse text-sm">
                <thead>
                  <tr className="border-b border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-800">
                    {expandedTable.headers.map((h, hi) => (
                      <th key={hi} className="px-3 py-2 font-semibold text-slate-900 dark:text-slate-100 whitespace-nowrap">
                        {renderInline(h)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {expandedTable.rows.map((row, ri) => (
                    <tr key={ri} className="border-b border-slate-100 dark:border-slate-800">
                      {expandedTable.headers.map((_, ci) => (
                        <td key={ci} className="px-3 py-2 text-slate-700 dark:text-slate-300">
                          {renderInline(row[ci] ?? '')}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
