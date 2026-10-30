<div align="center">

# ⚡ Aistee

**One AI workbench, many providers. Your data stays yours.**

Kotlin Multiplatform · Android first · Local-first tools

[![Android CI](https://img.shields.io/github/actions/workflow/status/travnie/aistee/android-ci.yml?branch=main&label=Android%20%2B%20KMP&logo=android&color=818CF8&style=flat-square)](https://github.com/travnie/aistee/actions/workflows/android-ci.yml)
[![Performance](https://img.shields.io/github/actions/workflow/status/travnie/aistee/performance-ci.yml?branch=main&label=performance&color=38BDF8&style=flat-square)](https://github.com/travnie/aistee/actions/workflows/performance-ci.yml)
[![Latest APK](https://img.shields.io/badge/APK-rolling-818CF8?logo=android&logoColor=white&style=flat-square)](https://github.com/travnie/aistee/releases/download/aistee-latest/aistee.apk)
[![Last commit](https://img.shields.io/github/last-commit/travnie/aistee?color=A78BFA&logo=git&style=flat-square)](https://github.com/travnie/aistee/commits/main)
[![License](https://img.shields.io/github/license/travnie/aistee?color=818CF8&style=flat-square)](LICENSE)

![Kotlin](https://img.shields.io/badge/Kotlin%20Multiplatform-7F52FF?logo=kotlin&logoColor=white&style=flat-square)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white&style=flat-square)
![Compose](https://img.shields.io/badge/Material%203-4285F4?logo=jetpackcompose&logoColor=white&style=flat-square)

<a href="https://deepwiki.com/travnie/aistee"><img src="https://deepwiki.com/badge.svg" alt="DeepWiki"></a>
<a href="https://doi.org/10.5281/zenodo.22307997"><img src="https://zenodo.org/badge/DOI/10.5281/zenodo.22307997.svg" alt="DOI"></a>

[**📦 Install**](#-install) · [**✨ Highlights**](#-highlights) · [**🔌 Providers**](#-providers) · [**📚 Docs**](#-docs) · [**🛠️ Develop**](#-development)

</div>

> [!NOTE]
> **Early prototype.** Android is the first client; shared provider/domain logic lives in Kotlin Multiplatform. Desktop and iOS are planned, not released.

## 📦 Install

**[Download the rolling signed APK](https://github.com/travnie/aistee/releases/download/aistee-latest/aistee.apk)**, updated after successful release builds on `main`.

[Versioned releases](https://github.com/travnie/aistee/releases) use `vX.Y` tags matching `versionName`, with `aistee_X.Y.apk` / `aistee_X.Y.aab`. Minimum Android version: **8.0 (API 26)**.

## ✨ Highlights

| | Already implemented |
| --- | --- |
| 🌐 **Web chats** | Provider-owned WebView sessions, uploads, favorites and selected activity tracking |
| 💬 **Native + API** | Gemini, OpenAI, Claude, compatible gateways and eligible ChatGPT-plan sign-in; compare, streaming and provider-scoped history |
| 🗂️ **Local workspace** | Projects, editable Library assets, Markdown/Docbench previews, imports and exports |
| 🧰 **Tools & skills** | Token counting, Bench actions, QR generation, explicit skill trust and sandbox execution |
| 📲 **Android** | Adaptive Material 3 UI, conversation widgets, notifications and native Direct Reply |
| 🔐 **Privacy** | No Aistee account wall, encrypted native API keys, App lock and privacy controls |

See [implemented features](docs/implemented.md) for the delivered inventory and [product ideas](docs/product-ideas.md) for open work.

## What it is

Aistee combines:

- **account-backed WebViews** with persistent provider sessions,
- **native/API chat and compare** for direct providers and OpenAI-compatible gateways,
- **local tooling** such as profiles, prompts, skills, Project Library and Bench integrations.

Android also accepts shared text, images and application files. Web content is staged for the selected provider and file delivery requires explicit confirmation.

## 🔌 Providers

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

## 🏗️ Architecture

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

## 🧭 Current focus

- verify embedded sign-in, upload and activity probes for the newer Web providers,
- add browser-backed auth handoff only where a provider exposes a verifiable contract,
- extend Codebench handoffs while keeping Streambench a companion UI,
- continue measured Material 3 Adaptive / large-screen UX work,
- keep security and privacy as release gates across storage, WebViews, tools, exports, widgets and notifications.

## 📚 Docs

- [Documentation index](docs/README.md)
- [Delivered features](docs/implemented.md)
- [Provider runtime](docs/provider-runtime.md)
- [Provider capability research](docs/provider-capability-research.md)
- [Product ideas](docs/product-ideas.md)
- [Active skills runtime](docs/skills-runtime.md)
- [Android performance](docs/android-performance.md)
- [Design](DESIGN.md)

## 🛠️ Development

Android keeps minSdk 26; current compile/target SDK levels live in `app/build.gradle.kts`. Portable logic belongs in `shared`; platform APIs stay in platform modules/source sets. Long-chat responsiveness, reliable uploads and provider isolation are product constraints, not optional polish.

## 📜 License

ISC, see [LICENSE](LICENSE).

## 📰 Mininewsy

<!--README_FEED:START-->
- [Star History Monthly October 2026 | Execution](https://www.star-history.com/blog/execution)
- [Product of the Day — Digital Electronic Cigarette](https://anycrap.shop/product/digital-electronic-cigarette)
- [Dog Fact of the Day](https://dogapi.dog/)
- [Joke of the Day](https://www.jokeindex.com/)
- [Fortune Cookie of the Day](https://www.geckoandfly.com/16647/20-wise-chinese-fortune-cookie-sayings/)
- [Life Hack of the Day](https://www.tumblr.com/1000-life-hacks)
<!--README_FEED:END-->

## 💬 Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝Excellence is to do a common thing in an uncommon way. — Booker Washington❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->
