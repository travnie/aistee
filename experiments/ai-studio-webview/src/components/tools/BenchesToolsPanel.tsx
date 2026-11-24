import { downloadBlob } from '../../lib/nativeWorkbench';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import QRCode from 'qrcode';
import jsQR from 'jsqr';
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  QrCode,
  Radio,
  ShieldAlert,
  Star,
  Upload,
} from 'lucide-react';
import { useAistee } from '../../context/AisteeContext';
import { BUILT_IN_BENCH_TOOLS } from '../../lib/models';
import { inspectTextSafety } from '../../lib/textInspector';
import { sha256Hex } from '../../lib/skills';

interface ParsedStation {
  name: string;
  url: string;
  hash: string;
}

export const BenchesToolsPanel: React.FC = () => {
  const {
    enabledBenches,
    toggleBenchEnabled,
    streambenchFavorites,
    streambenchRecents,
    toggleStreambenchFavorite,
    recordStreambenchPlay,
    showToast,
  } = useAistee();

  // Text Inspector state
  const [inspectInput, setInspectInput] = useState(
    'Paste suspect text here (zero-width chars \u200B, bidi overrides, or encoded prompt carriers) to inspect locally.',
  );
  const inspectResult = useMemo(() => inspectTextSafety(inspectInput), [inspectInput]);

  // Codebench QR state
  const [qrInput, setQrInput] = useState('https://aistee.local/workbench');
  const [qrSvg, setQrSvg] = useState<string>('');
  const [decodedQrText, setDecodedQrText] = useState<string>('');
  const qrFileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    if (!qrInput.trim()) {
      setQrSvg('');
      return;
    }
    QRCode.toString(qrInput, { type: 'svg', margin: 1, width: 200 })
      .then((svg) => {
        if (!cancelled) setQrSvg(svg);
      })
      .catch(() => {
        if (!cancelled) setQrSvg('');
      });
    return () => {
      cancelled = true;
    };
  }, [qrInput]);

  const handleDecodeQrImage = async (file: File) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = img.width;
      canvas.height = img.height;
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.drawImage(img, 0, 0);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const result = jsQR(imageData.data, imageData.width, imageData.height);
        setDecodedQrText(result ? result.data : 'No QR payload found in image.');
      }
      URL.revokeObjectURL(url);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      setDecodedQrText('Failed to load image.');
    };
    img.src = url;
  };

  // Streambench M3U state
  const [stations, setStations] = useState<ParsedStation[]>([]);
  const [stationSearch, setStationSearch] = useState('');
  const [playingStation, setPlayingStation] = useState<ParsedStation | null>(null);
  const m3uFileInputRef = useRef<HTMLInputElement>(null);

  const handleParseM3u = async (raw: string) => {
    const lines = raw.split(/\r?\n/);
    const parsed: ParsedStation[] = [];
    let pendingName = '';
    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line) continue;
      if (line.startsWith('#EXTINF:')) {
        const comma = line.indexOf(',');
        pendingName = comma >= 0 ? line.slice(comma + 1).trim() : '';
        continue;
      }
      if (!line.startsWith('#') && line.toLowerCase().startsWith('https://')) {
        const name = pendingName || new URL(line).host;
        const hash = (await sha256Hex(line)).slice(0, 16);
        parsed.push({ name, url: line, hash });
        pendingName = '';
      }
    }
    setStations(parsed);
    showToast(`Parsed ${parsed.length} HTTPS streams in memory`);
  };

  const visibleStations = useMemo(() => {
    const q = stationSearch.trim().toLowerCase();
    const list = q ? stations.filter((s) => s.name.toLowerCase().includes(q)) : stations;
    return list.slice(0, 40);
  }, [stations, stationSearch]);

  return (
    <div className="space-y-6">
      {/* Bench Toggles */}
      <section className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {BUILT_IN_BENCH_TOOLS.map((tool) => {
          const enabled = enabledBenches.includes(tool.id);
          return (
            <div
              key={tool.id}
              className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 flex items-start justify-between gap-3"
            >
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                    {tool.displayName}
                  </span>
                  <span className="text-[11px] text-slate-400">· {tool.networkBehavior}</span>
                </div>
                <p className="text-xs text-slate-600 dark:text-slate-300">{tool.description}</p>
              </div>
              <input
                type="checkbox"
                checked={enabled}
                onChange={() => toggleBenchEnabled(tool.id)}
                className="h-4 w-4 accent-indigo-600 shrink-0 mt-1"
              />
            </div>
          );
        })}
      </section>

      {/* Docbench Text Inspector */}
      {enabledBenches.includes('docbench-text-inspector') && (
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-3">
          <div className="flex items-center justify-between gap-2">
            <div>
              <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                Docbench Text Inspector (Local Unicode & Prompt Injection Scanner)
              </h3>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Detects invisible bidi overrides, Unicode tags, variation selectors, mixed-script confusables, and Base64 carriers.
              </p>
            </div>
            <div className="text-xs font-semibold tabular-nums">
              {inspectResult.detectedCount === 0 ? (
                <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                  <CheckCircle2 className="h-4 w-4" /> Clean
                </span>
              ) : (
                <span className="inline-flex items-center gap-1 text-rose-600 dark:text-rose-400">
                  <ShieldAlert className="h-4 w-4" /> {inspectResult.detectedCount} finding(s)
                </span>
              )}
            </div>
          </div>

          <textarea
            rows={3}
            value={inspectInput}
            onChange={(e) => setInspectInput(e.target.value)}
            className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 p-3 font-mono text-xs text-slate-900 dark:text-slate-100"
          />

          {inspectResult.findings.length > 0 && (
            <div className="space-y-1.5 max-h-48 overflow-y-auto">
              {inspectResult.findings.map((f, idx) => (
                <div
                  key={idx}
                  className="flex items-start justify-between gap-3 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2 text-xs"
                >
                  <div>
                    <span
                      className={`font-semibold mr-2 ${
                        f.severity === 'HIGH'
                          ? 'text-rose-600 dark:text-rose-400'
                          : f.severity === 'MEDIUM'
                            ? 'text-amber-600 dark:text-amber-400'
                            : 'text-slate-500'
                      }`}
                    >
                      [{f.severity}]
                    </span>
                    <span className="font-semibold text-slate-800 dark:text-slate-100">{f.label}:</span>{' '}
                    <span className="text-slate-600 dark:text-slate-300">{f.detail}</span>
                  </div>
                  <span className="font-mono text-[11px] text-slate-400 shrink-0 tabular-nums">
                    L{f.line}:C{f.column}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {/* Codebench QR & Barcode */}
      {enabledBenches.includes('codebench-qr-barcode') && (
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-4">
          <div className="flex items-center gap-2">
            <QrCode className="h-4 w-4 text-indigo-500" />
            <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
              Codebench QR Generator & Offline Image Decoder
            </h3>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-5 items-start">
            <div className="space-y-3">
              <label className="block space-y-1">
                <span className="text-xs font-medium text-slate-600 dark:text-slate-300">QR Payload Text / URL</span>
                <textarea
                  rows={3}
                  value={qrInput}
                  onChange={(e) => setQrInput(e.target.value)}
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 p-2.5 font-mono text-xs text-slate-900 dark:text-slate-100"
                />
              </label>

              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  disabled={!qrSvg}
                  onClick={() => {
                    const blob = new Blob([qrSvg], { type: 'image/svg+xml;charset=utf-8' });
                    downloadBlob(blob, 'codebench-qr.svg', showToast);
                  }}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 px-3 py-1.5 text-xs font-semibold text-white"
                >
                  <Download className="h-3.5 w-3.5" />
                  Download SVG
                </button>

                <input
                  ref={qrFileInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) handleDecodeQrImage(file);
                    e.target.value = '';
                  }}
                />
                <button
                  type="button"
                  onClick={() => qrFileInputRef.current?.click()}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-700 dark:text-slate-200"
                >
                  <Upload className="h-3.5 w-3.5" />
                  Decode QR from Image
                </button>
              </div>

              {decodedQrText && (
                <div className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 p-2.5 text-xs">
                  <div className="text-[11px] font-semibold text-slate-500 mb-0.5">Decoded Output</div>
                  <div className="font-mono text-slate-800 dark:text-slate-100 break-all">{decodedQrText}</div>
                </div>
              )}
            </div>

            <div className="flex justify-center">
              {qrSvg ? (
                <div
                  className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white p-4"
                  dangerouslySetInnerHTML={{ __html: qrSvg }}
                />
              ) : (
                <div className="rounded-xl border border-dashed border-slate-300 dark:border-slate-700 p-10 text-xs text-slate-400">
                  Enter text to render QR SVG
                </div>
              )}
            </div>
          </div>
        </section>
      )}

      {/* Streambench Audio Player */}
      {enabledBenches.includes('streambench-player') && (
        <section className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <Radio className="h-4 w-4 text-sky-500" />
              <div>
                <h3 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Streambench M3U/M3U8 Player
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Parses user-supplied M3U playlists locally in memory; only SHA-256 station hashes are saved for favorites/recents.
                </p>
              </div>
            </div>

            <div>
              <input
                ref={m3uFileInputRef}
                type="file"
                accept=".m3u,.m3u8,.txt"
                className="hidden"
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  if (file) await handleParseM3u(await file.text());
                  e.target.value = '';
                }}
              />
              <button
                type="button"
                onClick={() => m3uFileInputRef.current?.click()}
                className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white"
              >
                <Upload className="h-3.5 w-3.5" />
                Load .m3u / .m3u8
              </button>
            </div>
          </div>

          {playingStation && (
            <div className="rounded-lg border border-indigo-200 dark:border-indigo-800/60 bg-indigo-50/40 dark:bg-indigo-950/20 p-3 space-y-2">
              <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                Now Playing: {playingStation.name}
              </div>
              <audio controls src={playingStation.url} autoPlay className="w-full h-9" />
            </div>
          )}

          {stations.length > 0 && (
            <div className="space-y-2.5">
              <input
                type="text"
                value={stationSearch}
                onChange={(e) => setStationSearch(e.target.value)}
                placeholder="Filter stations…"
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs"
              />
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 max-h-56 overflow-y-auto">
                {visibleStations.map((st) => {
                  const isFav = streambenchFavorites.includes(st.hash);
                  const isRecent = streambenchRecents.includes(st.hash);
                  return (
                    <div
                      key={st.hash}
                      className="flex items-center justify-between gap-2 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2 text-xs"
                    >
                      <button
                        type="button"
                        onClick={() => {
                          setPlayingStation(st);
                          recordStreambenchPlay(st.hash);
                        }}
                        className="text-left min-w-0 flex-1 font-medium text-slate-800 dark:text-slate-100 truncate hover:text-indigo-500"
                      >
                        {st.name} {isRecent && <span className="text-[10px] text-slate-400">· recent</span>}
                      </button>
                      <button
                        type="button"
                        onClick={() => toggleStreambenchFavorite(st.hash)}
                        className={isFav ? 'text-amber-500' : 'text-slate-400'}
                      >
                        <Star className="h-3.5 w-3.5" fill={isFav ? 'currentColor' : 'none'} />
                      </button>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </section>
      )}
    </div>
  );
};
