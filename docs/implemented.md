# Implemented · Aistee

Delivered feature index, reviewed **29 October 2026** against the documented main-branch behavior. This is not a second API specification: see [provider runtime](provider-runtime.md), [skills runtime](skills-runtime.md), [design](../DESIGN.md) and [Android performance](android-performance.md) for maintained details. Future work is in [product ideas](product-ideas.md).

## 🌐 Web and native provider runtime

- Provider-owned WebView chat sessions with favorites, last-selected provider, static provider tweaks, bounded renderer pool, selective activity tracking, safe renderer restart and LRU eviction.
- Direct Gemini, OpenAI and Claude streaming with provider-scoped history; compatible gateway catalogs and cancellable requests.
- Shared provider capability/tool contract, bounded direct-provider tool loops, Gemini thought signatures, OpenAI `store=false` encrypted reasoning, Claude model-aware effort/thinking and resolved metadata, routed OpenRouter model and provider usage reporting.
- Provider-aware Prompt Studio effective instruction/state preview. Claude Code official-app handoff is an **external** route, not an Android execution runtime.

## 🗂️ Workspace and tools

- Project/Inbox native chat grouping and reusable Project Library Markdown, text and SVG assets; exports and QR SVGs.
- Project Library v2: stable IDs, revisions, updated time, SHA-256 identity, asset kind/origin, optimistic conflict checks and safe v1 migration.
- Markdown Workspace and Docbench preview/repair/format for Markdown fences and JSON, JSON5 and YAML, with user-reviewed save-back and revision checks.
- Local `o200k_base` token counting, Bench tools, native text-to-QR SVG generation, editable `SKILL.md` bundles and isolated active-skill execution with digest-bound opt-in trust and per-call consent. No automatic network skill execution.

## 💬 Native conversation ergonomics

- Locally persisted native chat history, branching/forks from finished replies and provider-scoped inherited-turn replay.
- Long-chat turn timeline with star markers, 48dp targets on dense rails and on-press/hover text previews.
- Persistent native starred turns and editable one-line notes (140 characters), branch-preserving star/notes behavior.
- Native quote selection and reviewed insertion, code-fence language/source copy, display and inline math TeX copy, and Markdown table CSV copy.
- Long native prompts collapse to an eight-line preview; explicit save to project as text and Project Library insertion into composer.
- Corresponding **Web** timeline, stars and quote extraction are **not shipped** and require per-provider testing.

## 📲 Android UI and system integration

- Material 3 Adaptive compact/bottom and wide/rail shell, responsive message width, stable Lazy item IDs, reader-aware autoscroll and list/detail panes on larger windows.
- Startup/provider-switch/native-list-detail/long-scroll Macrobenchmarks and Baseline Profiles. Deterministic streaming benchmark still planned.
- **Chats**, **Messages** and **Pinned chat** Glance widgets with stable rows and up to eight visible rows on resize; data stays based on local chats, not remote Web scraping.
- Privacy-safe MessagingStyle notifications, conversation shortcuts, `RemoteInput` native Direct Reply with WorkManager. Provider Web reply is a reviewed, explicit composer handoff, never hidden send.

## 🔐 Privacy

- Confirmed **Delete all local data** clears Aistee-owned data without claiming to delete provider-owned data or externally exported files.
- App lock, Screen privacy (`FLAG_SECURE`) and reversible Quick privacy; separate opt-in widget/notification title/message previews, off by default.
- Sensitive clipboard treatment for chat/instructions/skills; Android Keystore AES-GCM native key storage and provider-owned browser sessions.

For details and release gates, follow [Android performance](android-performance.md), [provider runtime](provider-runtime.md) and [skills runtime](skills-runtime.md).
