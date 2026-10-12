<a href="https://deepwiki.com/travnie/aistee"><img src="https://deepwiki.com/badge.svg" alt="DeepWiki"></a> <a href="https://doi.org/10.5281/zenodo.22307997"><img src="https://zenodo.org/badge/DOI/10.5281/zenodo.22307997.svg" alt="DOI"></a>

# Aistee

Kotlin Multiplatform workspace for using multiple AI services from one client instead of installing a separate app for each provider.

> Status: early prototype. Android is the first client; portable provider/domain logic lives in the shared KMP core.

Rolling Android build: [download the signed APK](https://github.com/travnie/aistee/releases/download/aistee-latest/aistee.apk). The asset is replaced after each successful release build from `main`.

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
- [Operetka wróci do Młoszowej - Przelom.pl](https://news.google.com/atom/articles/CBMif0FVX3lxTE1zeVl6VHlCYlBqSlNRYU5TVGoxV19VSTU0a3FCckpiYjdUT0tMOEptNTRkLV92MzRXdUlINXIxajBRVWVvOVQzSkw3RVdCM3IxUFd5aVZETHNtOG1LN053dHRHUmEtaUZDeW5qTXJjUXl6WC1XdGFYYVZpRXJIUkU?oc=5)
- [Seniorzy z UTW ruszyli po wiedzę, zdrowie i przygodę - Przelom.pl](https://news.google.com/atom/articles/CBMirgFBVV95cUxPcGRwc1ludjBJSWFaZ0dWZ0JUUkxwczBoSWQ2cGVRUkgxb3NNWWo0ZnJqNW5Id1dyWkFCa1pZQVJRU3VmdXVkajlnZ0xsWHJFaDJrYUtMUmNFeVFta0otcFFSUS1UMlhrTXNhcHF4SExiTnJQZXY5YUtyY2xuSnFYV0ctcEhZcDR5bUFyUDZTdkpHbktDY2lIMFZSdVUyLWpSTkdfWGx4Rl93c1g2SVE?oc=5)
- [Amazon in talks to buy AI startup Decart for around $7 billion, WSJ reports](https://www.reuters.com/business/retail-consumer/amazon-talks-buy-ai-startup-decart-around-7-billion-wsj-reports-2026-10-11/)
- [W mijającym tygodniu odeszli od nas \[5 - 10 październik\] - Przelom.pl](https://news.google.com/atom/articles/CBMiogFBVV95cUxQRTFFRDRGbkVMMXI0Ym8yd2g1a3IwaXczZm1VaUpmbWRwaVdwd3Z0TWRlSWZOcW1IVUdPcGluV2VicHo2blVOREt5djFERnowR3dsSkFwN0FraV9HYUt1UEE5T3VXM1RqZG14bHNOVFhLMWUxenBFa2E5ZWpyaHROWERHUXVOZUVldnBfNWhnZnY1X0tnMjIydGQzeG9tQmF3NHc?oc=5)
- [Wiązanki i kwiaty na cmentarz Wszystkich Świętych 2026. Najpiękniejsze stroiki na cmentarz z żywych kwiatów 12.10.2026 - Dziennik Łódzki](https://news.google.com/atom/articles/CBMi8wFBVV95cUxObjFvTnNmTEh2c2ZWZGttUWJRX25MLU5sRHJJSWVlVWhQdjFvMjVjaFE3amdLd015THpMY2hjb3V3azJOY0xFQ1dRU3lQcjRRaTNZTzJBUEJYWGxOYS1TX3Y2M1N5V2dwQ2xLV0FSblkwaTVCZGFkU2xNdmNSRTY3WmRTal9COW95T1BfaE0zT2pBaENXTk9OU3JKaEwxRnpiZkcwOFBnX3h0ZnNDbTBmUTJEYm4wMVNQQ1pBckQzTE5GdHJwckl0b3hJbzBDaVJhX2ItZTZoMlJRTW5TUEJFOGtRT21udE5DSzBWeEE1LTJsdzg?oc=5)
- [At least 72 Sudanese artisanal miners killed in strike, rights monitor says](https://www.reuters.com/world/africa/least-72-sudanese-artisanal-miners-killed-strike-saturday-rights-monitor-says-2026-10-11/)
<!--README_FEED:END-->

## 💬 Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝A programmer developed an operating system called TempleOS in Hospitalized for mental health problems, he believes that TempleOS is literally the Third Temple as biblically prophesied. Per God’s “instructions,” the OS uses a 640×480, 16 color display, and uses the language HolyC.❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->
