<div align="center">

# :zap: Aistee

**One AI workbench, many providers. Your data stays yours.**

Kotlin Multiplatform · Android first · Local-first tools

[![Android CI](https://img.shields.io/github/actions/workflow/status/travnie/aistee/android-ci.yml?branch=main&label=Android%20%2B%20KMP&logo=android&logoColor=white&style=flat-square)](https://github.com/travnie/aistee/actions/workflows/android-ci.yml)
[![Performance](https://img.shields.io/github/actions/workflow/status/travnie/aistee/performance-ci.yml?branch=main&label=performance&logo=githubactions&logoColor=white&style=flat-square)](https://github.com/travnie/aistee/actions/workflows/performance-ci.yml)
[![Latest APK](https://img.shields.io/badge/APK-rolling-818CF8?logo=android&logoColor=white&style=flat-square)](https://github.com/travnie/aistee/releases/download/aistee-latest/aistee.apk)
[![Last commit](https://img.shields.io/github/last-commit/travnie/aistee?color=A78BFA&logo=git&style=flat-square)](https://github.com/travnie/aistee/commits/main)
[![License](https://img.shields.io/github/license/travnie/aistee?color=818CF8&logo=opensourceinitiative&logoColor=white&style=flat-square)](https://spdx.org/licenses/ISC.html)

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin%20Multiplatform-7F52FF?logo=kotlin&logoColor=white&style=flat-square)](https://kotlinlang.org/docs/multiplatform.html)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white&style=flat-square)](https://developer.android.com/about/versions/oreo)
[![Material 3](https://img.shields.io/badge/Material%203-4285F4?logo=jetpackcompose&logoColor=white&style=flat-square)](https://m3.material.io/)

<a href="https://deepwiki.com/travnie/aistee"><img src="https://img.shields.io/badge/DeepWiki-travnie%2Faistee-blue.svg?logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACwAAAAyCAYAAAAnWDnqAAAAAXNSR0IArs4c6QAAA05JREFUaEPtmUtyEzEQhtWTQyQLHNak2AB7ZnyXZMEjXMGeK/AIi+QuHrMnbChYY7MIh8g01fJoopFb0uhhEqqcbWTp06/uv1saEDv4O3n3dV60RfP947Mm9/SQc0ICFQgzfc4CYZoTPAswgSJCCUJUnAAoRHOAUOcATwbmVLWdGoH//PB8mnKqScAhsD0kYP3j/Yt5LPQe2KvcXmGvRHcDnpxfL2zOYJ1mFwrryWTz0advv1Ut4CJgf5uhDuDj5eUcAUoahrdY/56ebRWeraTjMt/00Sh3UDtjgHtQNHwcRGOC98BJEAEymycmYcWwOprTgcB6VZ5JK5TAJ+fXGLBm3FDAmn6oPPjR4rKCAoJCal2eAiQp2x0vxTPB3ALO2CRkwmDy5WohzBDwSEFKRwPbknEggCPB/imwrycgxX2NzoMCHhPkDwqYMr9tRcP5qNrMZHkVnOjRMWwLCcr8ohBVb1OMjxLwGCvjTikrsBOiA6fNyCrm8V1rP93iVPpwaE+gO0SsWmPiXB+jikdf6SizrT5qKasx5j8ABbHpFTx+vFXp9EnYQmLx02h1QTTrl6eDqxLnGjporxl3NL3agEvXdT0WmEost648sQOYAeJS9Q7bfUVoMGnjo4AZdUMQku50McDcMWcBPvr0SzbTAFDfvJqwLzgxwATnCgnp4wDl6Aa+Ax283gghmj+vj7feE2KBBRMW3FzOpLOADl0Isb5587h/U4gGvkt5v60Z1VLG8BhYjbzRwyQZemwAd6cCR5/XFWLYZRIMpX39AR0tjaGGiGzLVyhse5C9RKC6ai42ppWPKiBagOvaYk8lO7DajerabOZP46Lby5wKjw1HCRx7p9sVMOWGzb/vA1hwiWc6jm3MvQDTogQkiqIhJV0nBQBTU+3okKCFDy9WwferkHjtxib7t3xIUQtHxnIwtx4mpg26/HfwVNVDb4oI9RHmx5WGelRVlrtiw43zboCLaxv46AZeB3IlTkwouebTr1y2NjSpHz68WNFjHvupy3q8TFn3Hos2IAk4Ju5dCo8B3wP7VPr/FGaKiG+T+v+TQqIrOqMTL1VdWV1DdmcbO8KXBz6esmYWYKPwDL5b5FA1a0hwapHiom0r/cKaoqr+27/XcrS5UwSMbQAAAABJRU5ErkJggg==" alt="DeepWiki"></a> <a href="https://doi.org/10.5281/zenodo.22307997"><img src="https://zenodo.org/badge/DOI/10.5281/zenodo.22307997.svg" alt="DOI"></a>  
<a href="https://app.codacy.com/gh/travnie/aistee/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/4afd14185a5246519f43693a4ef1c2a6" alt="Codacy code quality grade"/></a> ![CodeRabbit](https://img.shields.io/coderabbit/prs/github/travnie/aistee?utm_source=oss&utm_medium=github&utm_campaign=travnie%2Faistee&labelColor=171717&color=FF570A&link=https%3A%2F%2Fcoderabbit.ai&label=CodeRabbit+Reviews) <a href="https://app.deepsource.com/gh/travnie/aistee/" target="_blank"><img alt="DeepSource" title="DeepSource" src="https://app.deepsource.com/gh/travnie/aistee.svg/?label=active+issues&show_trend=false&token=GNeeCBgsa1IFYO9g4ocUQ2Uj"/></a>

[**:package: Install**](#package-install) · [**:sparkles: Highlights**](#sparkles-highlights) · [**:electric_plug: Providers**](#electric_plug-providers) · [**:books: Docs**](#books-docs) · [**:hammer_and_wrench: Develop**](#hammer_and_wrench-development)

</div>

> [!NOTE]
> **Early prototype.** Android is the first client; shared provider/domain logic lives in Kotlin Multiplatform. Desktop and iOS are planned, not released.

## :package: Install

**[Download the rolling signed APK](https://github.com/travnie/aistee/releases/download/aistee-latest/aistee.apk)**, updated after successful release builds on `main`.

[Versioned releases](https://github.com/travnie/aistee/releases) use `vX.Y` tags matching `versionName`, with `aistee_X.Y.apk` / `aistee_X.Y.aab`. Minimum Android version: **8.0 (API 26)**.

## :sparkles: Highlights

| | Already implemented |
| --- | --- |
| :globe_with_meridians: **Web chats** | Provider-owned WebView sessions, uploads, favorites, encrypted local bookmarks/folders, local Prompt Vault and selected activity tracking |
| :speech_balloon: **Native + API** | Gemini, OpenAI, Claude, compatible gateways and eligible ChatGPT-plan sign-in; compare, streaming and provider-scoped history |
| :card_file_box: **Local workspace** | Projects, editable Library assets, Markdown/Docbench previews, imports and exports |
| :toolbox: **Tools & skills** | Token counting, Bench actions, QR generation, explicit skill trust and sandbox execution |
| :iphone: **Android** | Adaptive Material 3 UI, conversation widgets, notifications and native Direct Reply |
| :closed_lock_with_key: **Privacy** | No Aistee account wall, encrypted native API keys, App lock and privacy controls |

See [implemented features](docs/implemented.md) for the delivered inventory and [product ideas](docs/product-ideas.md) for open work.

## What it is

Aistee combines:

- **account-backed WebViews** with persistent provider sessions,
- **native/API chat and compare** for direct providers and OpenAI-compatible gateways,
- **local tooling** such as profiles, prompts, skills, Project Library and Bench integrations.

Android also accepts shared text, images and application files. Web content is staged for the selected provider and file delivery requires explicit confirmation.

## :electric_plug: Providers

**Web sessions, API keys, gateways and external apps are separate authorization and funding routes.** A consumer login does not become an API key.

<details>
<summary><strong>Expand provider support matrix</strong> (login, uploads, activity)</summary>


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

</details>

## Runtime boundaries

- Web provider account sessions stay provider-owned. Aistee does not copy passwords, cookies or provider WebView tokens; its separate, user-authorized **Sign in with ChatGPT** grant is stored encrypted for the supported account-plan API route.
- WebViews are pooled and evicted under memory pressure; provider tweaks are static, host-scoped and bundled with the app.
- Failed Web page loads offer explicit GET retry or provider home navigation. Subresource errors and provider HTTP responses keep the provider page visible; the recovery action never replays POSTs.
- Diagnostics exclude page text, full URLs, form values, file names and credentials.
- Aistee has no account wall. Local chats, projects, files, tools, settings and backup/import/export remain usable without an Aistee cloud account.
- Native API keys are encrypted at rest with an Android Keystore-backed AES-GCM key and are never logged or exported.
- [`trvny/.ai`](https://github.com/trvny/.ai) remains the canonical portable profiles/instructions/skills source. Aistee consumes it through an explicit boundary instead of vendoring a copy.

## :building_construction: Architecture

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

## :compass: Current focus

- verify embedded sign-in, upload and activity probes for the newer Web providers,
- add browser-backed auth handoff only where a provider exposes a verifiable contract,
- extend Codebench handoffs while keeping Streambench a companion UI,
- continue measured Material 3 Adaptive / large-screen UX work,
- keep security and privacy as release gates across storage, WebViews, tools, exports, widgets and notifications.

## :books: Docs

- [Documentation index](docs/README.md)
- [Delivered features](docs/implemented.md)
- [Provider runtime](docs/provider-runtime.md)
- [Provider capability research](docs/provider-capability-research.md)
- [Product ideas](docs/product-ideas.md)
- [Active skills runtime](docs/skills-runtime.md)
- [Android performance](docs/android-performance.md)
- [Design](DESIGN.md)

## :hammer_and_wrench: Development

Android keeps minSdk 26; current compile/target SDK levels live in `app/build.gradle.kts`. Portable logic belongs in `shared`; platform APIs stay in platform modules/source sets. Long-chat responsiveness, reliable uploads and provider isolation are product constraints, not optional polish.

## :scroll: License

ISC, see [LICENSE](LICENSE).

## :newspaper: Mininewsy

<!--README_FEED:START-->
- [Trust but Verify: Implementing Frontier AI Policy in California and Beyond](https://carnegieendowment.org/research/2026/11/trust-but-verify-implementing-frontier-ai-policy-in-california-and-beyond)
- [Promises Made, Promises Unmet: The NPP’s Struggle to Reform Sri Lanka](https://carnegieendowment.org/research/2026/11/promises-made-promises-unmet-the-npps-struggle-to-reform-sri-lanka)
- [homeassistant-ai/ha-mcp](https://github.com/homeassistant-ai/ha-mcp)
- [garrytan/gbrain](https://github.com/garrytan/gbrain)
- [anthropics/claude-quickstarts](https://github.com/anthropics/claude-quickstarts)
- [google-deepmind/mujoco](https://github.com/google-deepmind/mujoco)
<!--README_FEED:END-->

## :speech_balloon: Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝All the passions die with the years; self-love alone never dies. — Voltaire❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->
