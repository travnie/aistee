import React, { useState } from 'react';
import {
  Clock,
  Copy,
  ExternalLink,
  Layers,
  Play,
  Plus,
  RefreshCw,
  Server,
  ShieldCheck,
  Terminal,
  Trash2,
  X,
} from 'lucide-react';
import { useAistee } from '../../context/AisteeContext';
import { AsyncProviderJob, DEFAULT_PROJECT_ID, getApiKeyForProvider } from '../../lib/models';
import {
  discoverMcpToolsDirect,
  executeMcpToolCallDirect,
  inspectMcpAuthMetadataDirect,
  runAsyncJobActionDirect,
} from '../../lib/directFetch';

export const ClaudeCodeHandoffPanel: React.FC = () => {
  const { showToast } = useAistee();
  const [task, setTask] = useState('');
  const [repo, setRepo] = useState('');
  const [branch, setBranch] = useState('main');
  const [mode, setMode] = useState<'plan' | 'code'>('plan');
  const [confirmUrl, setConfirmUrl] = useState<string | null>(null);

  const buildUrl = () => {
    const cleanTask = task.trim();
    const cleanRepo = repo.trim();
    if (!cleanTask) {
      showToast('Enter a task prompt first.');
      return null;
    }
    if (cleanRepo && !/^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/.test(cleanRepo)) {
      showToast('Repository must be in owner/repo format.');
      return null;
    }
    const params = new URLSearchParams();
    params.set('q', cleanTask);
    if (cleanRepo) params.set('repo', cleanRepo);
    if (branch.trim()) params.set('branch', branch.trim());
    params.set('mode', mode);
    return `https://claude.ai/code/new?${params.toString()}`;
  };

  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-4">
      <div className="flex items-start gap-3">
        <Terminal className="h-5 w-5 text-indigo-500 shrink-0 mt-0.5" />
        <div>
          <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
            Claude Code External Handoff
          </h2>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Prepares an explicit handoff URL targeting `claude.ai/code/new` for repository coding tasks.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <label className="block space-y-1">
          <span className="text-xs font-medium text-slate-600 dark:text-slate-300">GitHub Repo (optional)</span>
          <input
            type="text"
            value={repo}
            onChange={(e) => setRepo(e.target.value)}
            placeholder="owner/repository"
            className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100"
          />
        </label>
        <label className="block space-y-1">
          <span className="text-xs font-medium text-slate-600 dark:text-slate-300">Branch</span>
          <input
            type="text"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100"
          />
        </label>
        <label className="block space-y-1">
          <span className="text-xs font-medium text-slate-600 dark:text-slate-300">Mode</span>
          <select
            value={mode}
            onChange={(e) => setMode(e.target.value as 'plan' | 'code')}
            className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
          >
            <option value="plan">Plan first</option>
            <option value="code">Implement directly</option>
          </select>
        </label>
      </div>

      <textarea
        rows={4}
        value={task}
        onChange={(e) => setTask(e.target.value)}
        placeholder="Describe the engineering task, acceptance criteria, or bug to investigate…"
        className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 p-3 text-xs text-slate-900 dark:text-slate-100"
      />

      <div className="flex justify-end">
        <button
          type="button"
          onClick={() => {
            const url = buildUrl();
            if (url) setConfirmUrl(url);
          }}
          className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-4 py-2 text-xs font-semibold text-white"
        >
          Preview External Handoff
        </button>
      </div>

      {confirmUrl && (
        <div className="rounded-lg border border-indigo-300 dark:border-indigo-700 bg-indigo-50/60 dark:bg-indigo-950/30 p-3.5 space-y-2">
          <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
            Confirm External Destination
          </div>
          <div className="font-mono text-[11px] text-slate-600 dark:text-slate-300 break-all">{confirmUrl}</div>
          <div className="flex items-center justify-end gap-2">
            <button
              type="button"
              onClick={() => setConfirmUrl(null)}
              className="rounded-md border border-slate-300 dark:border-slate-600 px-3 py-1 text-xs"
            >
              Cancel
            </button>
            <a
              href={confirmUrl}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => setConfirmUrl(null)}
              className="inline-flex items-center gap-1.5 rounded-md bg-indigo-600 px-3 py-1 text-xs font-semibold text-white"
            >
              Open Claude Code
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </div>
        </div>
      )}
    </div>
  );
};

interface DiscoveredMcpTool {
  name: string;
  title?: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export const McpDiscoveryPanel: React.FC = () => {
  const { mcpServers, addMcpServer, removeMcpServer, showToast } = useAistee();
  const [label, setLabel] = useState('');
  const [endpoint, setEndpoint] = useState('');
  const [discoveringId, setDiscoveringId] = useState<string | null>(null);
  const [selectedEndpoint, setSelectedEndpoint] = useState<string>('');
  const [tools, setTools] = useState<DiscoveredMcpTool[]>([]);
  const [authChallenge, setAuthChallenge] = useState<{
    resourceMetadataUrl: string | null;
    scopes: string[];
  } | null>(null);
  const [authMetadataResult, setAuthMetadataResult] = useState<string | null>(null);
  const [toolSearch, setToolSearch] = useState('');
  const [callingTool, setCallingTool] = useState<DiscoveredMcpTool | null>(null);
  const [callArgsJson, setCallArgsJson] = useState('{}');
  const [callOutput, setCallOutput] = useState<string | null>(null);

  const handleDiscover = async (serverId: string, url: string) => {
    setDiscoveringId(serverId);
    setSelectedEndpoint(url);
    setTools([]);
    setAuthChallenge(null);
    setAuthMetadataResult(null);
    try {
      const data = await discoverMcpToolsDirect(url);
      if (data.status === 'success') {
        setTools(data.tools);
        showToast(`Discovered ${data.tools.length} MCP tool(s)`);
      } else if (data.status === 'authorization_required') {
        setAuthChallenge(data.challenge);
        showToast('MCP server requires OAuth authorization (401)');
      } else {
        showToast(`MCP discovery failed: ${data.reason}`);
      }
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Discovery failed');
    } finally {
      setDiscoveringId(null);
    }
  };

  const handleInspectAuth = async () => {
    if (!authChallenge?.resourceMetadataUrl) return;
    try {
      const data = await inspectMcpAuthMetadataDirect(authChallenge.resourceMetadataUrl);
      setAuthMetadataResult(JSON.stringify(data, null, 2));
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Auth inspection failed');
    }
  };

  const handleExecuteTool = async () => {
    if (!callingTool || !selectedEndpoint) return;
    try {
      const argsObj = JSON.parse(callArgsJson || '{}');
      const data = await executeMcpToolCallDirect(selectedEndpoint, callingTool.name, argsObj);
      setCallOutput(JSON.stringify(data, null, 2));
    } catch (err) {
      setCallOutput(err instanceof Error ? err.message : 'Invalid JSON arguments or call failure');
    }
  };

  const filteredTools = tools.filter(
    (t) =>
      !toolSearch.trim() ||
      t.name.toLowerCase().includes(toolSearch.toLowerCase()) ||
      t.description.toLowerCase().includes(toolSearch.toLowerCase()),
  );

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-4">
        <div className="flex items-start gap-3">
          <Server className="h-5 w-5 text-indigo-500 shrink-0 mt-0.5" />
          <div>
            <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
              MCP Server Tool Discovery & Schema Inspector (MCP 2026-07-28)
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Add up to 8 public HTTPS MCP endpoints to inspect <code className="font-mono">tools/list</code>, RFC 9728 OAuth metadata, and run user-approved one-shot tool calls.
            </p>
          </div>
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            const res = addMcpServer(label, endpoint);
            if (res.ok) {
              setLabel('');
              setEndpoint('');
              showToast('Saved MCP server endpoint');
            } else {
              showToast(res.error || 'Could not add MCP server');
            }
          }}
          className="grid grid-cols-1 sm:grid-cols-4 gap-2.5"
        >
          <input
            type="text"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="Label (e.g. Docs MCP)"
            className="rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
          />
          <input
            type="url"
            required
            value={endpoint}
            onChange={(e) => setEndpoint(e.target.value)}
            placeholder="https://example.com/mcp"
            className="sm:col-span-2 rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100"
          />
          <button
            type="submit"
            className="inline-flex items-center justify-center gap-1 rounded-lg bg-indigo-600 hover:bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white"
          >
            <Plus className="h-3.5 w-3.5" />
            Add Server ({mcpServers.length}/8)
          </button>
        </form>

        {mcpServers.length > 0 && (
          <div className="space-y-2">
            {mcpServers.map((s) => (
              <div
                key={s.id}
                className="flex items-center justify-between gap-3 rounded-lg border border-slate-200 dark:border-slate-700 px-3.5 py-2.5"
              >
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">{s.label}</div>
                  <div className="font-mono text-[11px] text-slate-500 truncate">{s.endpoint}</div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    type="button"
                    disabled={discoveringId === s.id}
                    onClick={() => handleDiscover(s.id, s.endpoint)}
                    className="inline-flex items-center gap-1 rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium text-indigo-600 dark:text-indigo-400"
                  >
                    <RefreshCw className={`h-3.5 w-3.5 ${discoveringId === s.id ? 'animate-spin' : ''}`} />
                    Discover Tools
                  </button>
                  <button
                    type="button"
                    onClick={() => removeMcpServer(s.id)}
                    className="rounded p-1 text-slate-400 hover:text-rose-500"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {authChallenge && (
        <div className="rounded-xl border border-amber-300 dark:border-amber-700/60 bg-amber-50/70 dark:bg-amber-950/30 p-4 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-amber-900 dark:text-amber-200">
              Authorization Challenge Detected (401 Unauthorized)
            </span>
            {authChallenge.resourceMetadataUrl && (
              <button
                type="button"
                onClick={handleInspectAuth}
                className="rounded-md bg-amber-600 px-2.5 py-1 text-xs font-semibold text-white"
              >
                Inspect RFC 9728 Metadata
              </button>
            )}
          </div>
          <div className="font-mono text-[11px] text-slate-700 dark:text-slate-300">
            Resource Metadata: {authChallenge.resourceMetadataUrl || 'Not advertised'} · Scopes:{' '}
            {authChallenge.scopes.join(', ') || 'none'}
          </div>
          {authMetadataResult && (
            <pre className="rounded bg-white dark:bg-slate-900 p-2.5 font-mono text-[11px] overflow-x-auto">
              {authMetadataResult}
            </pre>
          )}
        </div>
      )}

      {tools.length > 0 && (
        <div className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-3">
          <div className="flex items-center justify-between gap-3">
            <h3 className="font-display text-xs font-semibold uppercase tracking-wider text-slate-500">
              Discovered Tools ({filteredTools.length})
            </h3>
            <input
              type="text"
              value={toolSearch}
              onChange={(e) => setToolSearch(e.target.value)}
              placeholder="Search tools…"
              className="w-52 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-2.5 py-1 text-xs"
            />
          </div>

          <div className="space-y-2.5">
            {filteredTools.map((t) => (
              <div key={t.name} className="rounded-lg border border-slate-200 dark:border-slate-700 p-3 space-y-2">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <div className="font-mono text-xs font-semibold text-slate-900 dark:text-slate-100">{t.name}</div>
                    {t.description && <p className="text-xs text-slate-600 dark:text-slate-300">{t.description}</p>}
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0">
                    <button
                      type="button"
                      onClick={async () => {
                        await navigator.clipboard.writeText(
                          JSON.stringify({ method: 'tools/call', params: { name: t.name, arguments: {} } }, null, 2),
                        );
                        showToast(`Copied ${t.name} call template`);
                      }}
                      className="inline-flex items-center gap-1 rounded border border-slate-200 dark:border-slate-700 px-2 py-1 text-xs"
                    >
                      <Copy className="h-3 w-3" />
                      Draft
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setCallingTool(t);
                        setCallArgsJson('{}');
                        setCallOutput(null);
                      }}
                      className="inline-flex items-center gap-1 rounded bg-indigo-600 px-2.5 py-1 text-xs font-semibold text-white"
                    >
                      <Play className="h-3 w-3" />
                      Run Tool
                    </button>
                  </div>
                </div>
                <pre className="rounded bg-slate-50 dark:bg-slate-900/70 p-2 font-mono text-[11px] text-slate-600 dark:text-slate-300 overflow-x-auto max-h-32">
                  {JSON.stringify(t.inputSchema, null, 2)}
                </pre>
              </div>
            ))}
          </div>
        </div>
      )}

      {callingTool && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 backdrop-blur-sm p-4"
          onClick={() => setCallingTool(null)}
        >
          <div
            className="w-full max-w-lg rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#1E293B] shadow-2xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700">
              <div className="flex items-center gap-2">
                <ShieldCheck className="h-4 w-4 text-indigo-500" />
                <span className="font-mono text-xs font-semibold">Approve Tool Call · {callingTool.name}</span>
              </div>
              <button type="button" onClick={() => setCallingTool(null)} className="rounded p-1 text-slate-400">
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="p-4 space-y-3">
              <label className="block space-y-1">
                <span className="text-xs font-medium text-slate-600 dark:text-slate-300">JSON Arguments</span>
                <textarea
                  rows={4}
                  value={callArgsJson}
                  onChange={(e) => setCallArgsJson(e.target.value)}
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 p-2.5 font-mono text-xs"
                />
              </label>
              {callOutput && (
                <pre className="rounded-lg bg-slate-900 text-slate-100 p-3 font-mono text-[11px] max-h-48 overflow-auto">
                  {callOutput}
                </pre>
              )}
            </div>
            <div className="flex justify-end gap-2 px-4 py-3 border-t border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50">
              <button
                type="button"
                onClick={() => setCallingTool(null)}
                className="rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-1.5 text-xs"
              >
                Close
              </button>
              <button
                type="button"
                onClick={handleExecuteTool}
                className="rounded-lg bg-indigo-600 px-3.5 py-1.5 text-xs font-semibold text-white"
              >
                Approve & Execute
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export const AsyncJobsPanel: React.FC = () => {
  const { asyncJobs, addOrUpdateAsyncJob, removeAsyncJob, apiKeys, setIsApiKeysOpen, upsertProjectAsset, showToast } =
    useAistee();
  const [provider, setProvider] = useState<'chatgpt' | 'claude' | 'gemini'>('chatgpt');
  const [model, setModel] = useState('gpt-4o');
  const [prompt, setPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleProviderSwitch = (p: 'chatgpt' | 'claude' | 'gemini') => {
    setProvider(p);
    setModel(p === 'chatgpt' ? 'gpt-4o' : p === 'claude' ? 'claude-sonnet-4-5' : 'gemini-2.5-flash');
  };

  const handleStartJob = async (e: React.FormEvent) => {
    e.preventDefault();
    const key = getApiKeyForProvider(apiKeys, provider);
    if (!key) {
      setIsApiKeysOpen(true);
      showToast(`Configure ${provider} API key first.`);
      return;
    }
    setSubmitting(true);
    try {
      const data = await runAsyncJobActionDirect({ action: 'start', provider, apiKey: key, model, prompt });
      const now = Date.now();
      const job: AsyncProviderJob = {
        id: `job_${now.toString(36)}`,
        provider,
        kind: provider === 'chatgpt' ? 'BACKGROUND_RESPONSE' : 'BATCH',
        remoteId: data.remoteId,
        model,
        projectId: DEFAULT_PROJECT_ID,
        state: data.state as AsyncProviderJob['state'],
        createdAtEpochMs: now,
        updatedAtEpochMs: now,
        outputPreview: data.outputText,
      };
      addOrUpdateAsyncJob(job);
      setPrompt('');
      showToast(`Started ${provider} job (${job.remoteId})`);
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Failed to start job');
    } finally {
      setSubmitting(false);
    }
  };

  const handlePoll = async (job: AsyncProviderJob) => {
    const key = getApiKeyForProvider(apiKeys, job.provider);
    if (!key) {
      setIsApiKeysOpen(true);
      return;
    }
    try {
      const data = await runAsyncJobActionDirect({
        action: 'poll',
        provider: job.provider,
        apiKey: key,
        remoteId: job.remoteId,
      });
      addOrUpdateAsyncJob({
        ...job,
        state: data.state as AsyncProviderJob['state'],
        updatedAtEpochMs: Date.now(),
        outputPreview: data.outputText ?? job.outputPreview,
        errorMessage: data.errorMessage,
      });
      showToast(`Job status: ${data.state}`);
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Poll failed');
    }
  };

  const handleCancel = async (job: AsyncProviderJob) => {
    const key = getApiKeyForProvider(apiKeys, job.provider);
    if (!key) return;
    try {
      await runAsyncJobActionDirect({
        action: 'cancel',
        provider: job.provider,
        apiKey: key,
        remoteId: job.remoteId,
      });
      addOrUpdateAsyncJob({ ...job, state: 'CANCELLED', updatedAtEpochMs: Date.now() });
      showToast('Job cancelled');
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Cancel request failed');
    }
  };

  return (
    <div className="space-y-4">
      <form
        onSubmit={handleStartJob}
        className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-5 space-y-3"
      >
        <div className="flex items-start gap-2.5">
          <Clock className="h-5 w-5 text-indigo-500 shrink-0 mt-0.5" />
          <div>
            <h2 className="font-display text-sm font-semibold text-slate-900 dark:text-slate-100">
              Background & Batch Provider Jobs
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Submit asynchronous jobs directly to OpenAI Background Responses, Anthropic Message Batches, or Gemini Batch Generate.
            </p>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <select
            value={provider}
            onChange={(e) => handleProviderSwitch(e.target.value as 'chatgpt' | 'claude' | 'gemini')}
            className="rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 text-xs text-slate-900 dark:text-slate-100"
          >
            <option value="chatgpt">OpenAI Background Response</option>
            <option value="claude">Anthropic Message Batch</option>
            <option value="gemini">Gemini Batch Generate</option>
          </select>
          <input
            type="text"
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 px-3 py-1.5 font-mono text-xs text-slate-900 dark:text-slate-100"
          />
        </div>

        <textarea
          rows={3}
          required
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder="Long-running analysis or batch synthesis prompt…"
          className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 p-3 text-xs text-slate-900 dark:text-slate-100"
        />

        <div className="flex justify-end">
          <button
            type="submit"
            disabled={submitting || !prompt.trim()}
            className="rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 px-4 py-2 text-xs font-semibold text-white"
          >
            {submitting ? 'Submitting…' : 'Start Async Job'}
          </button>
        </div>
      </form>

      <div className="space-y-2.5">
        {asyncJobs.map((job) => (
          <div
            key={job.id}
            className="rounded-xl border border-slate-200 dark:border-slate-700/80 bg-white dark:bg-[#1E293B] p-4 space-y-2"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                  {job.provider.toUpperCase()} · {job.model} ·{' '}
                  <span className="text-indigo-600 dark:text-indigo-400">{job.state}</span>
                </div>
                <div className="font-mono text-[11px] text-slate-400">{job.remoteId}</div>
              </div>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => handlePoll(job)}
                  className="rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs font-medium"
                >
                  Poll
                </button>
                {(job.state === 'RUNNING' || job.state === 'QUEUED') && (
                  <button
                    type="button"
                    onClick={() => handleCancel(job)}
                    className="rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1 text-xs text-rose-500"
                  >
                    Cancel
                  </button>
                )}
                {job.outputPreview && (
                  <button
                    type="button"
                    onClick={async () => {
                      await upsertProjectAsset({
                        projectId: job.projectId,
                        title: `Job ${job.remoteId.slice(0, 12)} output`,
                        content: job.outputPreview!,
                        kind: 'ARTIFACT',
                        origin: 'JOB_OUTPUT',
                      });
                      showToast('Saved job output to Project Library');
                    }}
                    className="inline-flex items-center gap-1 rounded-md bg-indigo-600 px-2.5 py-1 text-xs font-semibold text-white"
                  >
                    <Layers className="h-3 w-3" />
                    Save to Library
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => removeAsyncJob(job.id)}
                  className="rounded p-1 text-slate-400 hover:text-rose-500"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
            {job.outputPreview && (
              <pre className="rounded bg-slate-50 dark:bg-slate-900/70 p-2.5 text-xs text-slate-700 dark:text-slate-300 max-h-40 overflow-y-auto whitespace-pre-wrap">
                {job.outputPreview}
              </pre>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};
