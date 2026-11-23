import JSZip from 'jszip';

export interface BuiltInSkillItem {
  id: string;
  title: string;
  category: string;
  description: string;
  targetPlatform: string;
  content: string;
}

export interface BuiltInInstructionItem {
  id: string;
  title: string;
  category: string;
  summary: string;
  promptText: string;
}

export interface BuiltInTemplateItem {
  id: string;
  filename: string;
  type: string;
  description: string;
  code: string;
}

export const BUILT_IN_SKILLS: BuiltInSkillItem[] = [
  {
    id: 'english-polish',
    title: 'English ↔ Polish AI Specialist',
    category: 'Language & Localization',
    description:
      'Natural contextual translations between Polish and English preserving technical terminology and idiomatic register.',
    targetPlatform: 'Universal / OpenAI / Claude',
    content: `# English <-> Polish Skill

Specialized in natural bilingual translations and localized system prompts.

### Key Rules:
- Preserves technical jargon (e.g., 'submodule', 'dependency injection', 'overhead').
- Respects Polish formal/informal forms (pan/pani vs per ty).
- Correct declension of loanwords and programming identifiers.
- Context-aware adaptation for developer tooling.`,
  },
  {
    id: 'create-repo-docs',
    title: 'Repository Docs Generator',
    category: 'Documentation',
    description: 'Analyzes source tree and produces structured AGENTS.md, README.md, and architecture diagrams.',
    targetPlatform: 'Universal Agent Skill',
    content: `# Create Repo Docs Skill

Generates standard, maintainable developer documentation for repositories.

### Output Contract:
1. One-line project elevator pitch.
2. Architecture flow chart (Mermaid syntax).
3. Quick start commands without superfluous commentary.
4. Security boundary and secret segregation rules.`,
  },
  {
    id: 'github-code-security',
    title: 'GitHub Code Security Auditor',
    category: 'Security & Compliance',
    description: 'Scans codebase and CI pipelines for secret leaks, hardcoded credentials, and dependency CVEs.',
    targetPlatform: 'GitHub Actions / CodeQL',
    content: `# GitHub Code Security Skill

### Checks:
- Inspects .codex/config.toml environment variable exclusions.
- Ensures no API keys or tokens are in repo commits.
- Flags unsafe permissions in GitHub workflow files.
- Enforces read-only GITHUB_TOKEN permissions where possible.`,
  },
  {
    id: 'github-actions',
    title: 'CI/CD Pipeline Optimizer',
    category: 'DevOps & CI/CD',
    description: 'Hardens GitHub Actions workflows with caching, concurrency cancellation, and minimal privilege tokens.',
    targetPlatform: 'GitHub Actions',
    content: `# GitHub Actions Optimization Skill

### Best Practices:
- Always pin actions to full commit SHA or trusted major tags.
- Use explicit timeout-minutes on all jobs.
- Concurrency group with cancel-in-progress on pull requests.
- Cache Gradle/pip/npm artifacts cleanly.`,
  },
  {
    id: 'llms-txt',
    title: 'llms.txt Standard Generator',
    category: 'AI Metadata',
    description: 'Generates clean, standardized llms.txt and llms-full.txt files for AI indexers.',
    targetPlatform: 'Web Standards / llms.txt',
    content: `# llms.txt Generator Skill

Implements the llms.txt proposal for providing condensed, markdown-formatted project context to LLMs.
- Concise summary of primary APIs and documentation links.
- Strips HTML fluff and navigation headers.`,
  },
];

export const BUILT_IN_INSTRUCTIONS: BuiltInInstructionItem[] = [
  {
    id: 'universal-core',
    title: 'Universal Core Guidance',
    category: 'Foundation',
    summary: 'Direct, factual, never-inventing baseline for any assistant.',
    promptText: `Respond naturally and directly. Address the user's main need first.
Match detail to the task. Keep simple answers short; develop complex topics far enough to support understanding or a decision. Do not hide material risks, conditions, or exceptions just to stay concise.
Separate facts, assumptions, interpretations, and recommendations when the distinction matters. State uncertainty where it belongs. Never invent sources, quotes, files, tool output, checks, or completed actions.
Style must remain subordinate to correctness, safety, permissions, user intent, and the requested output format.`,
  },
  {
    id: 'friendly-style',
    title: 'Friendly Style',
    category: 'Voice',
    summary: 'Warm, collaborative everyday tone without hype.',
    promptText: `Use warm, clear everyday language without forced enthusiasm. Treat the user as a capable collaborator. Explain difficult ideas without talking down to them. Match the user's register while keeping the response readable and accurate.`,
  },
  {
    id: 'professional-style',
    title: 'Professional Style',
    category: 'Voice',
    summary: 'Precise terminology, tradeoff-focused, zero bureaucratic filler.',
    promptText: `Lead with the conclusion or most important answer. Use precise terminology and explicit criteria. State tradeoffs and risks when they affect the decision. Avoid bureaucratic filler, marketing superlatives, and theatrical certainty.`,
  },
  {
    id: 'concise-style',
    title: 'Concise & Direct',
    category: 'Voice',
    summary: 'Start with result, strip boilerplate and ritual closings.',
    promptText: `Start with the result. Remove repeated framing, obvious restatements, and ritual closing lines. Keep necessary caveats and evidence even when the answer is short.`,
  },
  {
    id: 'critical-style',
    title: 'Critical & Skeptical',
    category: 'Voice',
    summary: 'Stress-test assumptions, spot weak points, direct skepticism at claims.',
    promptText: `Inspect claims, assumptions, and unnecessary complexity. Point out the weak part and suggest a concrete correction. Direct skepticism at the claim or system, not at the user.`,
  },
  {
    id: 'working-behavior',
    title: 'Working Behavior Policy',
    category: 'Collaboration',
    summary: 'Guidelines for asking questions, assuming safely, and progress reports.',
    promptText: `Ask only when missing information materially blocks useful progress. Prefer reasonable reversible assumptions when safe. Verify important claims and state changes in proportion to their risk. For multi-step work, communicate only material milestones rather than narrating every operation.`,
  },
];

export const BUILT_IN_TEMPLATES: BuiltInTemplateItem[] = [
  {
    id: 'outcome-task',
    filename: 'outcome-task.md',
    type: 'Markdown',
    description: 'Structured template for goal-driven autonomous agent tasks.',
    code: `# Outcome-Oriented Task Specification

## 1. Primary Objective
[State the exact observable end-state]

## 2. Constraints & Boundaries
- Strictly avoid: [Forbidden actions]
- Reversible assumptions allowed: [Yes/No]

## 3. Success Criteria & Verification
- [ ] Automated verification step passes
- [ ] User review checklist satisfied`,
  },
  {
    id: 'openai-agent',
    filename: 'openai-agent.py',
    type: 'Python',
    description: 'Minimal runnable agent implementation loading .ai profile instructions.',
    code: `import os
from pathlib import Path
from openai import OpenAI

client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))

def load_system_prompt() -> str:
    instructions_path = Path("generated/instructions.md")
    if instructions_path.exists():
        return instructions_path.read_text(encoding="utf-8")
    return "Write naturally and directly."

response = client.chat.completions.create(
    model="gpt-4o",
    messages=[
        {"role": "system", "content": load_system_prompt()},
        {"role": "user", "content": "Explain git submodules concisely"}
    ]
)
print(response.choices[0].message.content)`,
  },
  {
    id: 'wrangler-jsonc',
    filename: 'wrangler.jsonc',
    type: 'JSONC',
    description: 'Cloudflare Workers AI deployment configuration.',
    code: `{
  "name": "ai-core-worker",
  "main": "src/index.ts",
  "compatibility_date": "2026-08-01",
  "vars": {
    "PROFILE_ID": "default"
  }
}`,
  },
  {
    id: 'dev-vars',
    filename: '.dev.vars.example',
    type: 'Env',
    description: 'Segregated secret environment variables template.',
    code: `# Security: Keep secrets out of the public .ai core repo
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
GEMINI_API_KEY=...`,
  },
];

export type ActiveSkillToolId =
  | 'current_datetime'
  | 'create_calendar_event'
  | 'compose_email'
  | 'schedule_notification'
  | 'copy_to_clipboard';

export interface ActiveSkillDeclaration {
  runtime: string;
  tools: ActiveSkillToolId[];
  networkOrigins: string[];
}

export interface AgentSkillManifest {
  name: string;
  description: string;
  metadata: Record<string, string>;
  body: string;
  issues: string[];
  activeDeclaration: ActiveSkillDeclaration | null;
}

const VALID_SKILL_TOOLS: ActiveSkillToolId[] = [
  'current_datetime',
  'create_calendar_event',
  'compose_email',
  'schedule_notification',
  'copy_to_clipboard',
];

export interface LocalSkillBundle {
  name: string;
  source: string;
  scriptSource?: string;
  digest: string;
  trusted: boolean;
  trustedNetworkOrigins: string[];
  updatedAtEpochMs: number;
}

export async function sha256Hex(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const hashBuffer = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export function parseSkillManifest(source: string): AgentSkillManifest {
  const issues: string[] = [];
  const metadata: Record<string, string> = {};
  let name = '';
  let description = '';
  let body = source;

  const fmMatch = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  if (!fmMatch) {
    issues.push('SKILL.md must begin with YAML frontmatter (--- ... ---).');
  } else {
    const yamlBlock = fmMatch[1];
    body = fmMatch[2].trim();
    const lines = yamlBlock.split(/\r?\n/);
    let inMetadata = false;
    for (const line of lines) {
      if (!line.trim() || line.trim().startsWith('#')) continue;
      if (/^metadata:\s*$/.test(line)) {
        inMetadata = true;
        continue;
      }
      if (inMetadata && /^\s{2,}/.test(line)) {
        const kv = line.trim().match(/^([A-Za-z0-9_.-]+):\s*(.*)$/);
        if (kv) {
          metadata[kv[1]] = kv[2].replace(/^["']|["']$/g, '').trim();
        }
        continue;
      }
      inMetadata = false;
      const topKv = line.match(/^([A-Za-z0-9_.-]+):\s*(.*)$/);
      if (topKv) {
        const val = topKv[2].replace(/^["']|["']$/g, '').trim();
        if (topKv[1] === 'name') name = val;
        if (topKv[1] === 'description') description = val;
      }
    }
  }

  if (!name || !/^[a-z0-9][a-z0-9-]{0,62}$/.test(name)) {
    issues.push('Frontmatter `name` must be lowercase kebab-case (1–63 chars).');
  }
  if (!description) {
    issues.push('Frontmatter `description` is required.');
  }

  let activeDeclaration: ActiveSkillDeclaration | null = null;
  const runtime = metadata['aistee-runtime'];
  if (runtime) {
    if (runtime !== 'webview-v1') {
      issues.push(`Unsupported metadata.aistee-runtime '${runtime}'; expected 'webview-v1'.`);
    }
    const tools: ActiveSkillToolId[] = [];
    for (const token of (metadata['aistee-tools'] ?? '').split(/\s+/).filter(Boolean)) {
      if (VALID_SKILL_TOOLS.includes(token as ActiveSkillToolId)) {
        tools.push(token as ActiveSkillToolId);
      } else {
        issues.push(`Unknown active skill tool '${token}'.`);
      }
    }
    const networkOrigins = (metadata['aistee-network'] ?? '').split(/\s+/).filter(Boolean);
    if (networkOrigins.length > 4) {
      issues.push('At most 4 network origins are allowed.');
    }
    if (issues.length === 0) {
      activeDeclaration = { runtime, tools, networkOrigins };
    }
  }

  return { name, description, metadata, body, issues, activeDeclaration };
}

export function createSkillDraft(name: string, description: string, active: boolean): string {
  const safeName =
    name
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9-]+/g, '-')
      .replace(/^-|-$/g, '') || 'custom-skill';
  const safeDesc = description.trim() || 'Reusable Aistee local skill';
  if (active) {
    return `---
name: ${safeName}
description: ${safeDesc}
metadata:
  aistee-runtime: webview-v1
  aistee-tools: current_datetime copy_to_clipboard
---

# ${safeName}

${safeDesc}
`;
  }
  return `---
name: ${safeName}
description: ${safeDesc}
---

# ${safeName}

${safeDesc}
`;
}

export async function exportStudioPackageZip(
  skills: LocalSkillBundle[],
  host: 'PORTABLE' | 'CLAUDE' | 'CODEX',
  renderedInstructions: string,
): Promise<Blob> {
  const zip = new JSZip();
  zip.file('INSTRUCTIONS.md', renderedInstructions);
  const rootDir = host === 'CLAUDE' ? '.claude/skills' : host === 'CODEX' ? '.agents/skills' : 'skills';
  for (const s of skills) {
    zip.file(`${rootDir}/${s.name}/SKILL.md`, s.source);
    if (s.scriptSource) {
      zip.file(`${rootDir}/${s.name}/scripts/index.js`, s.scriptSource);
    }
  }
  return zip.generateAsync({ type: 'blob' });
}

export async function readSkillArchiveZip(file: File): Promise<{ source: string; scriptSource?: string }> {
  const zip = await JSZip.loadAsync(file);
  const skillFile = Object.values(zip.files).find(
    (f) => !f.dir && f.name.toLowerCase().endsWith('skill.md'),
  );
  if (!skillFile) {
    throw new Error('Archive does not contain a SKILL.md manifest.');
  }
  const source = await skillFile.async('string');
  const scriptFile = Object.values(zip.files).find(
    (f) => !f.dir && (f.name.endsWith('.js') || f.name.endsWith('.mjs')),
  );
  const scriptSource = scriptFile ? await scriptFile.async('string') : undefined;
  return { source, scriptSource };
}
