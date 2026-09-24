<a href="https://deepwiki.com/travnie/aistee"><img src="https://deepwiki.com/badge.svg" alt="DeepWiki"></a> <a href="https://doi.org/10.5281/zenodo.22307997"><img src="https://zenodo.org/badge/DOI/10.5281/zenodo.22307997.svg" alt="DOI"></a>

# Aistee

Kotlin Multiplatform workspace for using multiple AI services from one client without turning every provider into a separate installed app.

> Status: early prototype. Android is the first client; shared domain/provider logic is moving to Kotlin Multiplatform so desktop and iOS clients can reuse the same core.

Rolling Android build: [download the signed APK](https://github.com/travnie/aistee/releases/download/aistee-latest/aistee.apk). The asset is replaced after each successful release build from `main`.

## What it is

Aistee is intended to combine three layers:

1. **Web accounts** — persistent WebView tabs for services where the user signs in with their normal account.
2. **Native/API providers** — a shared chat/compare surface for direct APIs and OpenAI-compatible gateways such as OpenRouter, AIHubMix and Vercel AI Gateway.
3. **Optional AI tooling** — reusable profiles/instructions from [`trvny/.ai`](https://github.com/trvny/.ai) without copying that repository into this one.

The first prototype already contains Compose UI, persistent per-provider WebViews, a native comparison chat, profile/instruction rendering, YAML editing and a small skills/docs browser.

On Android, Aistee also appears in the system share sheet for text, images and application files. Shared content is routed to a chosen web provider; text uses the existing focused-empty-composer bridge, while granted content URIs are staged for the provider's next compatible file chooser and require an explicit one-time confirmation before delivery to the embedded page.

## Initial provider targets

### Account-backed WebViews

- ChatGPT
- Claude
- Gemini
- DeepSeek
- Kimi
- Mistral Vibe (formerly Le Chat)
- Qwen
- Microsoft Copilot
- Z.ai
- Grok
- Character.AI
- Venice
- Meta AI
### Native / API layer

The native/API layer supports OpenRouter Free, AIHubMix and Vercel AI Gateway through the shared OpenAI-compatible gateway adapter. Direct Gemini, OpenAI and Claude chats preserve bounded provider-scoped history with native message roles and stream text incrementally with cancellable requests; interrupted partial replies are never replayed. Gateway model pickers refresh from each live catalog: OpenRouter and AIHubMix keep only zero-cost text models, while Vercel exposes compatible text models ordered by listed token price so account credits and configured Vercel budgets remain the spend boundary. Gateways stay outside the default All Models comparison to avoid duplicate aggregator routing. All Models dispatches only to direct providers with configured API keys and reports API failures without substituting simulated answers. Provider-specific details belong behind adapters rather than being spread through UI code.

## Provider support matrix

Full means the Aistee-side integration is implemented; provider-side login or page changes can still affect an embedded web client. Partial calls out a known limitation rather than hiding it.

| Provider / surface | Status | Authentication | Uploads | Activity tracking | Notes |
| --- | --- | --- | --- | --- | --- |
| ChatGPT Web | Full | Provider page in WebView | Yes | Generating + unread | Persistent session, provider-scoped mobile/desktop mode |
| Claude Web | Full | Provider page in WebView | Yes | Generating + unread | Persistent session and provider-scoped tweaks |
| Gemini Chat Web | Partial | Google sign-in may be blocked in embedded user-agents | Yes | Generating + unread | The chat surface is integrated, but fresh Google OAuth inside WebView is not a supported flow |
| DeepSeek Web | Full | Provider page in WebView | Yes | Generating + unread | Persistent session |
| Kimi Web | Full | Provider page in WebView | Yes | Generating + unread | Persistent session |
| Mistral Vibe Web | Full | Provider page in WebView | Yes | Generating + unread | Tracks the locale-independent square stop control in the composer |
| Qwen Web | Partial | Qwen-owned email/password + Google/GitHub sign-in surface; embedded not verified | Provider-documented image upload; embedded not verified | Not yet | Qwen documents image upload in Qwen Chat; embedded sign-in/upload flow and provider-specific activity tracking still need verification |
| Microsoft Copilot Web | Partial | Microsoft auth hosts stay in-provider; Google/Apple embedded sign-in not verified | Provider-documented; embedded not verified | Not yet | Official Copilot and Microsoft 365 entry aliases are bounded in-WebView; unrelated HTTPS navigation with a user gesture opens externally, while navigation without a gesture is blocked; embedded sign-in/upload flow and provider-specific activity tracking still need verification |
| Z.ai Web | Partial | Provider-owned email plus Google/GitHub sign-in surface; embedded not verified | Page-driven | Not yet | `chat.z.ai` plus Google/GitHub auth hosts are bounded in-WebView; unrelated HTTPS navigation with a user gesture opens externally, while navigation without a gesture is blocked; embedded sign-in/upload flow and provider-specific activity tracking still need verification |
| Grok Web | Partial | X, Google, Apple or email on provider-owned surface; embedded not verified | Provider-documented; embedded not verified | Not yet | xAI documents multi-file upload on the web; embedded sign-in/upload flow and provider-specific activity tracking still need verification |
| Character.AI Web | Partial | Google, Apple or email-link on provider-owned surface; embedded not verified | Provider-documented image attachments; embedded not verified | Not yet | Character.AI documents image attachments in chats; embedded sign-in/upload flow and activity probe still need verification |
| Venice Web | Partial | Email or WalletConnect on provider-owned surface; embedded not verified | Provider-documented; embedded not verified | Not yet | Venice documents file uploads in the chat input; embedded sign-in/upload flow and provider-specific activity tracking still need verification |
| Meta AI Web | Partial | Meta account on provider-owned surface; embedded not verified | Not verified | Not yet | Meta account support for Meta AI is rolling out provider-side; `alpha.meta.ai` is a verified provider-owned login alias, while embedded sign-in, uploads and activity tracking still need verification |
| OpenRouter Free | Native gateway | API key | N/A | Native request state | Uses openrouter/free; excluded from default All Models compare |
| AIHubMix Free | Native gateway | API key | N/A | Native request state | Uses explicit -free models; excluded from default All Models compare |
| Vercel AI Gateway | Native gateway | API key | N/A | Native request state | Live text-model catalog ordered by listed price; spend remains bounded by the Vercel account/key budgets |

Verification references for the newer web providers: [Microsoft Copilot entry points](https://learn.microsoft.com/microsoft-365/copilot/microsoft-365-copilot-overview), [Microsoft Copilot file upload](https://support.microsoft.com/en-us/microsoft-copilot/file-upload-in-microsoft-copilot), [Grok files FAQ](https://docs.x.ai/grok/faq), [Venice upload changelog](https://featurebase.venice.ai/changelog/veniceai-change-log-march-1st-3rd-2025), [Character.AI image attachments](https://support.character.ai/hc/en-us/articles/35409588582683-Community-Update-March-2025), [Qwen VLo image upload in Qwen Chat](https://qwen.ai/blog?id=qwen-vlo), the provider-owned [Qwen sign-in surface](https://chat.qwen.ai/auth?action=signin), the provider-owned [Z.ai sign-in surface](https://chat.z.ai/auth), and the provider-owned [Meta AI login surface](https://alpha.meta.ai/). These verify provider capabilities or owned hosts, not Android WebView login compatibility or stable generation DOM selectors.

Google documents the embedded-user-agent restriction in its [OAuth 2.0 policies](https://developers.google.com/identity/protocols/oauth2/policies).

## WebView approach

Account sessions persist, but Aistee must not keep every heavy provider SPA alive forever. The Android host uses a small LRU pool, pauses inactive WebViews and evicts them under memory pressure while cookies/session state remain provider-owned. Provider tweaks live in a small, auditable in-app registry: scripts are static, scoped to the matching provider host and applied after page load; remote userscript code is never fetched.

**Sign-in help** in the web chat menu covers Qwen, Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI through provider-owned entry pages. It remembers the user's explicitly chosen provider-supported identity method locally and offers an in-app or external-browser route. This is a guide, not automatic sign-in: embedded compatibility remains provider/method-scoped, browser sessions are not transferred into Aistee, and Aistee never copies cookies or OAuth tokens.

**Find in page** is available from the web chat menu. It searches and highlights text already loaded in the current page, with previous/next navigation and a match count. It does not search unloaded or virtualized chat history. Search text stays in memory and is cleared on page/provider changes or when leaving web chats; it is never exported or sent through an API.

Provider diagnostics expose only the provider host, WebView package/version, capability counts, activity-tracking support and file-picker events. They never collect page text, full URLs, form values, file names, cookies or authentication tokens.

Aistee must not scrape passwords, session cookies, OAuth tokens or other login credentials. Authentication remains between the embedded provider page and that provider.

## Relationship to `.ai`

[`trvny/.ai`](https://github.com/trvny/.ai) remains the canonical portable AI configuration core. This repository contains the multiplatform Aistee core plus platform clients; Android is the first shipping client.

```text
trvny/.ai             reusable profiles / instructions / skills
      │
      └── optional consumption
              │
              ▼
travnie/aistee        shared KMP core + platform clients
```

Do not vendor a second copy of `.ai` here. If runtime integration becomes useful, consume a pinned/exported representation with an explicit boundary.

## Architecture direction

```text
shared (Kotlin Multiplatform)
├── provider/model registry
├── profile + prompt tools
└── portable domain logic

platform clients
├── Android app
│   ├── WebView host + file chooser
│   ├── provider tweaks / mobile performance
│   └── Custom Tabs / intents / Keystore
├── Desktop app (planned)
└── iOS app (planned)
```

Technical details for native provider transport, conversation state, Prompt Studio boundaries and API-level TODOs live in [docs/provider-runtime.md](docs/provider-runtime.md). Product/workflow ideas collected from app research and sibling tools live in [docs/product-ideas.md](docs/product-ideas.md).

Backends are optional, not the default. If a feature truly needs one, prefer a tiny stateless service and evaluate Cloudflare, Google Cloud, AWS or Oracle free tiers based on the actual requirement rather than choosing infrastructure first.

**No account wall:** Aistee itself must remain useful without an Aistee account, cloud sync or hosted backup. Provider logins/API keys are required only for the providers the user explicitly chooses. Local chats, projects, files, tools, settings, import/export and manual backup/restore stay available locally. Any future Aistee cloud/sync/integration account is additive and opt-in, never a prerequisite for local features.

## Near-term roadmap

- [x] remove generated/build-machine files from version control
- [x] normalize app name, namespace and application ID to Aistee
- [x] harden WebView security while preserving provider login compatibility
- [x] migrate portable domain/provider logic to KMP `shared`
- [x] add mobile WebView LRU/memory-pressure handling for long chats
- [x] implement reliable provider file uploads through the platform file picker
- [x] apply rendered Studio instructions to an empty focused web composer with clipboard fallback
- [x] add OpenRouter, AIHubMix and Vercel OpenAI-compatible gateways
- [x] create a provider-tweak/userscript interface instead of hard-coded WebView hacks
- [x] add privacy-safe provider diagnostics for embedded capability verification
- [x] show generating and unread response status on ChatGPT, Claude, Gemini, DeepSeek, Kimi and Vibe web tabs
- [x] add Qwen, Microsoft Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI account-backed WebView entries
- [ ] **1. New Web provider verification:** verify embedded sign-in, upload flows and provider-specific generation/activity probes for Qwen, Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI; add browser-backed auth handoff only where a verifiable redirect/session contract exists
- [x] add identity-assisted provider onboarding metadata and provider-owned sign-in guidance for Qwen, Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI without a mandatory Aistee account or cookie/token copying
- [x] add a local Markdown prompt vault with edit/import/export and chat-to-`.md` workflows
- [x] add manually editable/importable `SKILL.md` assets with safe capability gating
- [x] add a local Project Library for reusable chat Markdown and generated text/SVG artifacts, with canonical Aistee chat Markdown import/export
- [x] bring Docbench format/repair, EOL normalization, o200k token counting and Text Inspector into local prompt/file tooling and native typed chat tools
- [x] add first-party Docbench typed chat tools and local Codebench QR-to-Library generation for compatible native providers
- [ ] **7. UX/security polish:** modernize chat/navigation with stable Material 3 Adaptive and measured Compose work, finish large-screen/list-detail and IME/predictive-back polish, and keep sensitive storage/backup, WebView isolation, tool permissions, safe exports and regression tests as release gates
- [x] add provider-aware chat/message widgets and native/API notification Direct Reply where the transport can safely send in background
- [x] add local staged Draft Reply handoff for account WebViews without background sending or cookie/session automation
- [ ] **6. Widgets/notifications v2:** add richer recent/status widgets, Android conversation `MessagingStyle`/`Person`, long-lived conversation shortcuts and user-controlled privacy/redaction
- [ ] **8. Native/API modes and jobs:** keep a small provider-aware GUI for useful processing choices instead of generic expert sliders; ship OpenAI Auto/Flex first, then add background/batch job surfaces where providers support them cleanly
- [x] add CI build/lint checks
- [x] document which providers work fully, partially, or block embedded login

## Development

Android targets API 35 with minSdk 26. The shared core uses Kotlin Multiplatform; platform UI remains Compose-first and moves into Compose Multiplatform only where it does not weaken native WebView, upload, authentication or secure-storage behavior.

Mobile UX is a product constraint: long chats must stay responsive, file upload must work, and provider tweaks should reduce wasted chrome/animation without breaking provider pages. Native API keys are encrypted at rest with an Android Keystore-backed AES-GCM key; credentials are never logged or exported.

## License

ISC, see [LICENSE](LICENSE).

