<a href="https://deepwiki.com/travnie/aistee"><img src="https://deepwiki.com/badge.svg" alt="DeepWiki"></a> <a href="https://doi.org/10.5281/zenodo.22307997"><img src="https://zenodo.org/badge/DOI/10.5281/zenodo.22307997.svg" alt="DOI"></a>

# Aistee

Kotlin Multiplatform workspace for using multiple AI services from one client instead of installing a separate app for each provider.

> Status: early prototype. Android is the first client; portable provider/domain logic lives in the shared KMP core.

Rolling Android build: [download the signed APK](https://github.com/travnie/aistee/releases/download/aistee-latest/aistee.apk). The asset is replaced after each successful release build from `main`.

Versioned releases: tag `vX.Y` (must equal `versionName`) on `main`; assets `aistee_X.Y.apk` / `aistee_X.Y.aab` on the [releases page](https://github.com/travnie/aistee/releases).

## What it is

Aistee combines:

- **account-backed WebViews** with persistent provider sessions,
- **native/API chat and compare** for direct providers and OpenAI-compatible gateways,
- **local tooling** such as profiles, prompts, skills, Project Library and Bench integrations.

Android also accepts shared text, images and application files. Web content is staged for the selected provider and file delivery requires explicit confirmation.

## Provider support matrix

**Full** means the Aistee integration is implemented; provider-side login/page changes can still break an embedded web client. **Partial** means a known embedded-flow limitation remains.

| Provider / surface | Status | Authentication | Uploads | Activity | Notes |
| --- | --- | --- | --- | --- | --- |
| ChatGPT Web | Full | Provider WebView | Yes | Generating + unread | Persistent session; mobile/desktop mode; Codex Cloud shortcut |
| Claude Web | Full | Provider WebView | Yes | Generating + unread | Persistent session |
| Gemini Chat Web | Partial | Fresh Google OAuth may reject embedded user-agents | Yes | Generating + unread | Existing sessions work; embedded OAuth is provider-limited |
| DeepSeek Web | Full | Provider WebView | Yes | Generating + unread | Persistent session |
| Kimi Web | Full | Provider WebView | Yes | Generating + unread | Persistent session |
| Mistral Vibe Web | Full | Provider WebView | Yes | Generating + unread | Provider-scoped activity tracking |
| Qwen Web | Partial | Provider sign-in surface; embedded flow unverified | Documented; embedded unverified | Not yet | Needs device verification |
| Microsoft Copilot Web | Partial | Microsoft auth in-provider; other embedded methods unverified | Documented; embedded unverified | Not yet | Provider-owned navigation is bounded in-WebView |
| Z.ai Web | Partial | Provider sign-in surface; embedded flow unverified | Page-driven | Not yet | Needs device verification |
| Grok Web | Partial | Provider sign-in surface; embedded flow unverified | Documented; embedded unverified | Not yet | Needs device verification |
| Character.AI Web | Partial | Provider sign-in surface; embedded flow unverified | Documented; embedded unverified | Not yet | Needs device verification |
| Venice Web | Partial | Provider sign-in surface; embedded flow unverified | Documented; embedded unverified | Not yet | Needs device verification |
| Meta AI Web | Partial | Provider sign-in surface; embedded flow unverified | Not verified | Not yet | Needs device verification |
| OpenRouter Free | Native gateway | API key | N/A | Native request state | `openrouter/free`; excluded from default All Models compare |
| AIHubMix Free | Native gateway | API key | N/A | Native request state | Zero-cost text models; excluded from default All Models compare |
| Vercel AI Gateway | Native gateway | API key | N/A | Native request state | Live compatible text-model catalog; account/key budgets remain the spend boundary |

Direct Gemini, OpenAI and Claude chats use provider-scoped history and cancellable streaming. Gateway catalogs refresh live; All Models uses direct providers only, avoiding duplicate aggregator routes.

Provider-specific verification and API research live in [docs/provider-capability-research.md](docs/provider-capability-research.md). Native transport details and account-plan work live in [docs/provider-runtime.md](docs/provider-runtime.md).

## Runtime boundaries

- Provider account sessions stay provider-owned. Aistee does not copy passwords, cookies, OAuth tokens or browser sessions.
- WebViews are pooled and evicted under memory pressure; provider tweaks are static, host-scoped and bundled with the app.
- Diagnostics exclude page text, full URLs, form values, file names and credentials.
- Aistee has no account wall. Local chats, projects, files, tools, settings and backup/import/export remain usable without an Aistee cloud account.
- Native API keys are encrypted at rest with an Android Keystore-backed AES-GCM key and are never logged or exported.
- [`trvny/.ai`](https://github.com/trvny/.ai) remains the canonical portable profiles/instructions/skills source. Aistee consumes it through an explicit boundary instead of vendoring a copy.

## Architecture

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

Backends are optional. Add one only when a feature requires server-side state or execution.

## Current focus

- verify embedded sign-in, upload and activity probes for the newer Web providers,
- add browser-backed auth handoff only where a provider exposes a verifiable contract,
- extend Codebench handoffs while keeping Streambench a companion UI,
- continue measured Material 3 Adaptive / large-screen UX work,
- keep security and privacy as release gates across storage, WebViews, tools, exports, widgets and notifications.

## Docs

- [Provider runtime](docs/provider-runtime.md)
- [Provider capability research](docs/provider-capability-research.md)
- [Product ideas](docs/product-ideas.md)
- [Active skills runtime](docs/skills-runtime.md)
- [Android performance](docs/android-performance.md)
- [Design](DESIGN.md)

## Development

Android keeps minSdk 26; current compile/target SDK levels live in `app/build.gradle.kts`. Portable logic belongs in `shared`; platform APIs stay in platform modules/source sets. Long-chat responsiveness, reliable uploads and provider isolation are product constraints, not optional polish.

## License

ISC, see [LICENSE](LICENSE).

## 📰 Mininewsy

<!--README_FEED:START-->
- [Chwila nieuwagi na ulicy Matejki. Policja ukarała 39-latkę - Przelom.pl](https://news.google.com/atom/articles/CBMiqAFBVV95cUxOYWVucmkycVFvSGk3cS0ydXc2dXFtelNHY3VNMU5RRU42RlpRbjNvYndldjVIenJXU1c4V0o1eC00MWtsMkc2LUZNLW9XRUx3VzItYV9VV28xeXE0RHMyQTR6NUlzVzZnVXl3Z2NoT0ZLenNmRnBQUVVkdktaOGp2clROQ1U4cjZEWGdmaFpLODNYSUdLOUZhby02aTFkanBCSHNkNll3M2o?oc=5)
- [kanarek-companion created review 5537758476 on PR #170 in travnie/Kanarek](https://github.com/travnie/Kanarek/pull/170?feedseek_event=17342195699#pullrequestreview-5537758476)
- [gptomek created review 5537630750 on PR #170 in travnie/Kanarek](https://github.com/travnie/Kanarek/pull/170?feedseek_event=17341748350#pullrequestreview-5537630750)
- [Volvo Cars expands automated driving partnership with Nvidia](https://www.reuters.com/business/autos-transportation/volvo-cars-expands-automated-driving-partnership-with-nvidia-2026-10-15/)
- [Kolej wróci na trasę Trzebinia–Wadowice? Jest nowa deklaracja i ważny ruch PKP PLK - Przelom.pl](https://news.google.com/atom/articles/CBMivgFBVV95cUxQM3J3Y2VQblVnQ3NPR1hsdEVuSmNKd0JURWNIQkRyNkZsa2lWNHVIQWNTX3NZY3ZJWURvMm0xZ1lzYlRjM0gzNEY0aHYyNlF1MmNST3lmS2ExX3M2cnFPSFZCcHhyU1ZVYW1oNXZ5ZDNJVGluN0xWQTllZUhleURpNVBHMTZrSHJqcTI1NmRZVnhkUnNXc1JrZkxwV1VFTElOcERCdWpqbDdaWU5CVjdsX2VubEVDbEpsdS0tb1dR?oc=5)
- [gptomek created review 5537371072 on PR #168 in travnie/Kanarek](https://github.com/travnie/Kanarek/pull/168?feedseek_event=17340832619#pullrequestreview-5537371072)
<!--README_FEED:END-->

## 💬 Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝“Perl: The only language that looks the same before and after RSA encryption.”— Keith Bostic❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->
