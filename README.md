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
- [Can Nuclear Fuel be Delivered in Time to Power Advanced Nuclear Reactors?](https://carnegieendowment.org/research/2026/10/can-nuclear-fuel-be-delivered-in-time-to-power-advanced-nuclear-reactors)
- [Policjantka z Chrzanowa najlepszym oskarżycielem publicznym - Przelom.pl - portal ziemi chrzanowskiej](https://news.google.com/atom/articles/CBMiqgFBVV95cUxNbGVjQnBfMzFfUmppWlZrWkpWUk9RbG1HM0wwdm9qak0wOVc0YUVCRm1tSF9mREYwNDVwZ1JKcU1NT1d6cUdwNkE0T18wLVVxWGJqbDRtSlRCSFgzeXJCeG9BNDM0R1JKSHNQRUhuV2E4T0x1VktwNHlrZkIwdFNKc0dkbnlaZUdkclZENGxWd080OW9CdTQyY2RqcS0zMF9kNUU1eDJ1NWdwQQ?oc=5)
- [Christa Pike 'angry and confused' about Tennessee's failed execution effort, lawyers say](https://www.reuters.com/legal/government/christa-pikes-lawyers-demand-see-syringes-drug-residue-botched-execution-2026-10-07/)
- [FBI arrests man for plotting mass shooting at Mall of America](https://www.reuters.com/legal/government/fbi-arrests-man-plotting-mass-shooting-mall-america-2026-10-07/)
- [Venezuela's Maduro to face new US charges over alleged torture of Americans, official says](https://www.reuters.com/world/americas/maduro-wife-expected-face-new-charges-over-alleged-torture-americans-cnn-says-2026-10-07/)
- [Spanish woman whose eviction ignited housing protests dies at 87](https://www.reuters.com/world/evicted-spanish-octogenarian-maricarmen-abascal-heart-spains-housing-protests-2026-10-07/)
<!--README_FEED:END-->

## 💬 Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝In Windows 98, minimized windows are actually moved far away outside the average monitor’s resolution.❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->
