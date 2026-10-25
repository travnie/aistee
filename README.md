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
| Claude Code (official app) | External handoff | Claude-owned app/site | Via Claude | Not tracked by Aistee | Studio Tools → Agents; optional plan/code task, GitHub repo + branch, explicit confirmation, no token proxy |
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
- [Star History Monthly October 2026 | Execution](https://www.star-history.com/blog/execution)
- [Time for Saudi Clarity on Normalization with Israel](https://carnegieendowment.org/research/2026/10/time-for-saudi-clarity-on-normalization-with-israel)
- [Pożar pociągu w Balinie. Iskry zapaliły benzynę w 1909 r. - Przelom.pl - portal ziemi chrzanowskiej](https://news.google.com/atom/articles/CBMioAFBVV95cUxPV1JPdk93VlQtRjY4bVoxQ0ljXzcxX3cwWXhvM2ZHSUE0VDdjNlhTR0xQSzlMa0tnYXItbHBieXBmUkVyajN6R1BWamRHU19sTC02Q3oxRXpRcWphS3V1LU1fcWJCN0FVMDg0Qm12Nk0tWTdMRVhRU0ZEWVM1ZGxRd3ZXQm1pdEJsRWhNZWtFSVMyOUdXTVRqdWVDc1czX3NB?oc=5)
- [Serbia's Vucic concedes defeat to student led movement in parliamentary election](https://www.reuters.com/world/europe/serbias-vucic-concedes-defeat-student-led-movement-parliamentary-election-2026-10-25/)
- [Będzie duży przełom w polityce Serbii? Zamknięto lokale wyborcze - Radio Zet](https://news.google.com/atom/articles/CBMiogFBVV95cUxPUXJYdUlWWGtzZnhBRTcwOTA0Sy1wRjBhYnVpRmxjWUhYbGNHS0hsOGNDMnl6RUdxZTNwY2dUWjhYc2MyanBNa2RxS09xQm5hT3JvSXk0OGZ0VHNYb3F0SUdNX1N4ZWdBdEZ5Z0ppVnB5U2FnNU10Z2ZLXzdYNVdOcnF5Wk1VczFzM2RwNE05Q0ttWk96VnFkLUxSWTJFakc1LXc?oc=5)
- [Komisje bez kamer. Czego obawiają się krzeszowiccy radni? - krzeszowiceone.pl](https://news.google.com/atom/articles/CBMiqAFBVV95cUxQMWZFUDVHWkNUVXU2V3NyUGZwcnVUZGF2MEFXRllQcldzd0dBeWY3WkhnQ3R2UGFhVzRXUmkyMWNRQ1VxZXFmVWhOS2c3MnhjcEJsdWpsUkVmemE5anFzUFlDMy16Wk02OTVQM0ppd0NOT2dTb1g5MmdUb090dXZuWGtJdFdoV19yS1o0X3hmcTNVX294Rl9fT3VPVFhGTGVxVnVKQlczcULSAa4BQVVfeXFMT0p4SHVfX2VsRmxFdzdFRmtkU19UVVFxcENYN0VSUTAtVUNoMWo1ZjJ5Q2owbkNxc1VncDhhVWpwUFdNd1Y5WTFYNTNDbDJDdGxxWFZDMDFmR1VweTBLbXhtSkc1eGkwdVpmM3V3VUVrN0tIZzRueFBfSnB6MXgwZ3hzb2gxMjRvUDZVUGI2SDc0UDN2cUVsaUIzU1N4WHV5c0V1ZW9RcW1ZVi1SOVJB?oc=5)
<!--README_FEED:END-->

## 💬 Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝All the domain names such as Google, Log In or Sign Up were free until 1995, but now everyone has to pay for every new domain name.❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->
