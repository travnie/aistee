# Experimental AI Studio WebView prototype

> **Not the shipping Aistee client.** Imported from the user-provided
> `aistee-nov-20-2026-11-44-08-pm.zip`.
> Source SHA-256: `51080247ce9ac962168dceacf04b4f365a1f710ec0cff570a2dfcd827b176ac2`.

This standalone React/Vite + Android WebView variant lives beside the existing
Kotlin Multiplatform implementation. The canonical `app/`, `shared/`,
registries, build, release pipeline and project docs remain unchanged.
**Do not merge or publish as the production Aistee APK.**

## Run

- Web: `cd experiments/ai-studio-webview && npm ci && npm run lint && npm run build`.
- Web dev server: `npm run dev`. It binds to `0.0.0.0:3000`; use only in a
  trusted local environment until proxy routes receive separate security review.
- Android: use the repository Gradle wrapper with
  `./gradlew -p experiments/ai-studio-webview :app:assembleDebug`.
- To refresh Android assets before packaging: copy `dist/` contents into
  `app/src/main/assets/web/`. The imported ZIP already includes a bundle.

The Android variant uses `ais.tee.experimental` and the label `Aistee Lab`.
It can coexist with production Aistee. App data and browser sessions are
separate; no credential or conversation migration is provided.

## Known gaps

- Android WebView virtual origin and direct provider HTTPS `fetch` can hit CORS
  restrictions. The original Express proxy has different behavior from its
  bundled Android assets. This is **not** a production-ready auth/API bridge.
- External account sign-ins may remain in the system browser, not the embedded UI.
- Generated JS/CSS assets are included for archive fidelity; treat source under
  `src/` as canonical and regenerate before publishing test APKs.
- API keys are session-only; no plaintext persistence. Active scripts are disabled
  until a capability runtime can enforce network and host permissions.
- Android runtime, MCP compatibility and mobile UX require device testing.
  No automatic rolling-release publication.

## Original prototype overview

# Aistee — Multi-Provider AI Workbench

One AI workbench, many providers. Your data stays yours.

Aistee is a local-first React + TypeScript web workbench (backed by a stateless Express SSE & HTTPS proxy) built for managing account-backed Web AI workflows, multi-provider Native/API chat & side-by-side comparison, `.ai` style profile composition, Markdown/Document preflight tooling, MCP tool discovery, and sandboxed local skills.

## Features

- **Account-Backed Web AI Hub (`Web AI`)**: Switch between 13 providers (Claude.ai, ChatGPT, Gemini, DeepSeek, Kimi, Mistral Vibe, Qwen, Copilot, Z.ai, Grok, Character.AI, Venice, Meta AI) with Favorites, comfortable 720px workspace width toggle, autosaved Draft Vault with snapshot restore points, 3-level Bookmark folders (with JSON import/export), and a Project Library Prompt Vault scanned with the local Text Inspector.
- **Native & API Compare Hub (`Compare`)**: Stream direct API conversations across Anthropic Claude, OpenAI Responses (`store: false`), Google Gemini, DeepSeek, Moonshot Kimi, OpenRouter, AIHubMix, Vercel AI Gateway, and custom HTTPS OpenAI-compatible endpoints—or fan out concurrently via **All Models (Compare)** with provider-scoped replay, incognito ephemeral chats, Turn Timeline navigation, reply starring/notes, conversation branching, and Markdown/TeX/CSV table rendering.
- **`.ai` Profile Studio & Renderer (`Studio`)**: Compose base profiles with built-in or custom overlays, tweak personality modifiers, adaptation flags, collaboration policies, knowledge integrity constraints, and output preferences; inspect deterministic EN/PL/Auto instructions, schema-validated `profile.yaml`, and test responses in the offline Playground.
- **Docbench & Markdown Workspace**: Live split editor/preview with `o200k_base` token estimation, line-ending detection & LF normalization, unclosed Markdown fence repair, JSON formatting, structural diagnostics, and Text Inspector scanning.
- **Tools Hub (`Skills`, `Benches`, `Agents`, `MCP`, `Jobs`, `Privacy`)**:
  - **Skills**: Browse built-in skills/instructions/templates, manage `SKILL.md` local bundles with SHA-256 trust digests, export `.zip` bundles (`PORTABLE`, `.claude/skills`, `.agents/skills`), Active Skill packages can be imported/exported; execution is disabled.
  - **Benches**: Docbench Text Inspector (bidi overrides, Unicode tags, variation selectors, mixed-script confusables, Base64 carriers), Codebench QR SVG generator & offline image decoder, and Streambench M3U/M3U8 in-memory audio player.
  - **Agents / MCP / Jobs**: Claude Code external handoff builder, SSRF-protected MCP `tools/list` + RFC 9728 OAuth inspection + one-shot manual tool execution, and OpenAI/Anthropic/Gemini async/batch job orchestration.
  - **Privacy**: Screen Privacy veil, Quick Privacy toggle, Passcode App Lock, and full local data wipe.

## Architecture

- **Frontend**: React 19, TypeScript, Tailwind CSS v4 (`src/`)
- **Stateless Proxy Server**: Express + Vite middleware on `0.0.0.0:3000` (`server.ts`) enforcing public-HTTPS SSRF validation and zero server-side persistence of API keys or conversations.
- **Local Storage**: All user state (`localStorage`) stays in the browser; incognito chats remain strictly in volatile memory.

## Scripts

```bash
npm install
npm run dev      # Starts Express + Vite server on http://0.0.0.0:3000
npm run lint     # TypeScript type-check (tsc --noEmit)
npm run build    # Production Vite build -> dist/
npm start        # Production server serving dist/
```
