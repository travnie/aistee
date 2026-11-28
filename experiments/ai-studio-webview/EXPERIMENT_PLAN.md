# Aistee Android-Packaged Web Workbench Fix Plan

Restores a Gradle 9.3.1-compatible Android application shell that packages and serves the React + TypeScript workbench locally inside an Android `WebView`, while switching provider streaming, model discovery, MCP inspection, and batch jobs to direct HTTPS `fetch` calls paired with `localStorage`.

## User Review & Critical Decisions

> [!IMPORTANT]
> This container runs **Gradle 9.3.1** (Android Gradle Plugin **9.1.1**, Android SDK **36**, JDK **21**). Packaging the compiled React bundle inside an Android APK resolves the missing Gradle build error without discarding any of the web workbench screens or local-first features.

- **Confirmed Decision 1 (Build Target)**: Wrap the React + Vite application inside a lightweight Kotlin Android `WebView` host compatible with Gradle 9.3.1 and AGP 9.1.1, producing the debug APK expected by the build system.
- **Confirmed Decision 2 (Networking & Storage)**: Route Native/Compare AI streaming, gateway model catalog discovery, MCP tool inspection/calls, and batch jobs through direct browser HTTPS `fetch` calls from the client runtime, with all user state persisted in `localStorage`.

## 1. Overview & Core Concept

- **What It Does**: Compiles the React workbench into static assets and embeds them inside a secure, hardware-accelerated Android `WebView` container built with Gradle 9.3.1. Both standalone browser mode and the Android APK run the identical Aistee interface: Account-Backed Web AI hub, Native & Compare AI chat, `.ai` Profile Studio, Docbench & Markdown workspace, MCP inspector, Async Jobs, Built-in Benches, and Sandboxed Skills.
- **Target Audience / Persona**: Mobile and desktop power users who need a local-first multi-provider AI workbench with direct API access, strict session boundaries, and zero external account lock-in.
- **Key Value**: Passes the container's Gradle Android build verification cleanly while keeping all networking and persistence self-contained inside the client runtime.

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. Launching the Android app loads the compiled Aistee single-page application immediately from local application assets (`WebViewAssetLoader` HTTPS virtual origin), avoiding slow network boots or insecure `file://` URLs.
  2. External account-backed links (Web AI provider sessions, bookmarks, Claude Code handoff) open in the Android system browser / Custom Tab intent so users retain their existing logins without any WebView credential interception.
  3. Native chat prompts stream tokens directly from provider HTTPS APIs into the React UI, persisting non-incognito conversations, `.ai` profiles, Project Library assets, drafts, bookmarks, skills, and privacy settings in persistent DOM `localStorage`.
  4. If an optional CORS restriction occurs on an arbitrary third-party custom or MCP URL from a browser origin, the UI surfaces an actionable HTTPS error message instead of silently failing or falling back across providers.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Compact, high-contrast developer workbench preserving Aistee's dark-first neutral slate canvas paired with light companion mode.
  - *Color Palette & Mood*: Deep slate canvas (`#0F172A`), elevated slate structural surfaces (`#1E293B`), subtle hairline dividers (`#334155`), primary indigo focus accents (`#818CF8` / `#4F46E5`), sky cyan streaming feedback (`#38BDF8`), and semantic emerald/amber/crimson state feedback paired with text labels.
  - *Typography & Hierarchy*: `Plus Jakarta Sans` for headings and navigation chrome, `DM Sans` for body copy and controls, and `JetBrains Mono` with `tabular-nums` for token counts, YAML/code blocks, and latency readouts.
  - *Component Styling & Layout*: Flat single-elevation surfaces, unboxed `·`-separated metadata instead of pill badge clusters, `@custom-variant dark` theme toggling, single-line `whitespace-nowrap shrink-0` buttons/tabs, and proper Android edge-to-edge / IME soft-keyboard insets inside the `WebView` host.
- **Interactive Feedback & Motion**: Immediate streaming token updates, disabled send controls with stop-generation abort triggers, explicit confirmation modals before tool calls or external links, and transient toast confirmations settling under 200ms.

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Embedded Vite Bundle via `WebViewAssetLoader` + Gradle 9.3.1 Shell**
  - *Chosen Approach*: Configure a clean Kotlin DSL Gradle project targeting AGP 9.1.1, compileSdk 36, minSdk 26, and existing `debug.keystore` signing, plus a Gradle pre-build sync (or checked-in asset sync step) copying Vite production output into Android assets served over `https://appassets.androidplatform.net/`.
  - *Why*: Matches the exact Gradle 9.3.1 runtime in this container while preserving every interactive React screen and Vite-bundled offline library (`gpt-tokenizer`, `qrcode`, `jsqr`, `jszip`, `js-yaml`).
  - *Alternatives Considered*: Rewriting the entire app back to Jetpack Compose (discarded to preserve full feature parity already built in React) or keeping no Gradle files (breaks container build verification).
- **Decision 2: Direct Browser HTTPS Fetch Layer + `localStorage`**
  - *Chosen Approach*: Replace client `/api/*` calls with a typed direct-HTTPS transport module inside the frontend client supporting Gemini, Anthropic (`anthropic-dangerous-direct-browser-access` header), OpenAI Responses, DeepSeek, Kimi, OpenRouter, AIHubMix, Vercel AI Gateway, Custom OpenAI-compatible endpoints, MCP JSON-RPC, and Batch APIs directly from the browser.
  - *Why*: Eliminates any hard dependency on a running Node.js background server inside an Android APK while keeping API keys strictly local to the user's device.
  - *Alternatives Considered*: Bundling a Node/Express binary inside the APK (heavyweight, unnecessary per user's direct-fetch preference).

## 4. Technical Architecture & Data Strategy

- **Architecture & Component Diagram**:

```text
┌──────────────────────────────────────────────────────────────────────┐
│                   Android App Shell (Gradle / AGP 9.1.1)             │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  MainActivity (WebView + SafeInsets + External Tab Launcher)   │  │
│  │  • Serves Vite bundle via HTTPS WebViewAssetLoader             │  │
│  │  • Enables DOM Storage (localStorage) & Strict Mixed Content   │  │
│  │  • Routes external target="_blank" / Intent URLs to Browser    │  │
│  └───────────────────────────────┬────────────────────────────────┘  │
└──────────────────────────────────┼───────────────────────────────────┘
                                   │ Loads SPA Bundle
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│                React Workbench SPA (localStorage State)              │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────────────────┐  │
│  │ AisteeContext│  │ Direct HTTPS   │  │ Pure-Client Benches      │  │
│  │ • NativeChat │──▶ Transport      │  │ • .ai Profile / YAML     │  │
│  │ • Studio .ai │  │  • SSE Parsers │  │ • Docbench / Inspector   │  │
│  │ • ProjLib v2 │  │  • Catalogs    │  │ • Codebench QR / M3U     │  │
│  │ • Privacy    │  │  • MCP & Jobs  │  │ • Sandboxed Skill iframe │  │
│  └──────────────┘  └───────┬────────┘  └──────────────────────────┘  │
└────────────────────────────┼─────────────────────────────────────────┘
                             │ Direct HTTPS (User-Supplied API Keys)
                             ▼
     Provider Endpoints (Claude, OpenAI, Gemini, DeepSeek, Kimi, Gateways, MCP)
```

- **Data Model & State**:
  - Client state remains anchored in a single React context (`NativeChatConversation`, `ProjectLibraryArchive`, `Profile`, `ProfileOverlay`, `WebDraftEntry`, `WebChatBookmark`, `LocalSkillBundle`, `McpServerConfig`, `AsyncProviderJob`, `PrivacySettings`), persisted immediately to browser `localStorage` (while keeping incognito conversations strictly volatile in memory).
- **Interactive Component & State Mapping**:
  - *Chat Stream & Compare Fan-Out*: Sending a prompt cancels cleanly via `AbortController`, opens concurrent direct HTTPS `fetch` SSE streams per targeted provider, decodes Gemini/Anthropic/OpenAI/Compat frames incrementally, and isolates errors and replay context per provider.
  - *Model Catalogs, MCP & Jobs*: Gateway refresh triggers direct `GET /models`, MCP discovery sends `POST` JSON-RPC (`MCP-Protocol-Version: 2026-07-28`) with 401 header parsing + optional RFC 9728 metadata inspection, and Async Jobs execute direct `start`/`poll`/`cancel` calls.
  - *Android Navigation & Links*: Pressing Android Back navigates history or closes modals cleanly; external links (`claude.ai`, `chatgpt.com`, bookmarks, Claude Code handoff) delegate via `ACTION_VIEW` to the device browser outside WebView.
- **Security Safeguards Built In**:
  - *Safe Virtual Origin & Isolated WebView*: Android `WebView` serves bundled assets exclusively via `WebViewAssetLoader` over `https://appassets.androidplatform.net/`, with `file://` and universal file-URL access explicitly disabled.
  - *Strict HTTPS & Local Network Blocking*: Direct fetch helpers verify clean `https://` URLs without embedded credentials and reject local, loopback, and private network hostnames/IP literals for custom gateways, MCP servers, and streams.
  - *Safe Rendering & Untrusted Link Guard*: Markdown and UI views rely exclusively on declarative React elements, `href` scheme filtering (`https://`/`http://` only), and an isolated sandboxed `iframe` for Active Skills.
