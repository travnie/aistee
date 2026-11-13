# Implemented · Aistee

Delivered feature index, reviewed **29 October 2026** against the documented main-branch behavior. This is not a second API specification: see [provider runtime](provider-runtime.md), [skills runtime](skills-runtime.md), [design](../DESIGN.md) and [Android performance](android-performance.md) for maintained details. Future work is in [product ideas](product-ideas.md).

## 🌐 Web and native provider runtime

- Provider-owned WebView chat sessions with favorites, last-selected provider, static provider tweaks, bounded renderer pool, selective activity tracking, safe renderer restart and LRU eviction.
- Direct Gemini, OpenAI and Claude streaming with provider-scoped history; compatible gateway catalogs and cancellable requests.
- Explicit **Sign in with ChatGPT** plan route on Android: Custom Tab authorization with callback, protected credentials, refresh/sign-out, account-specific model inventory, and chat/account binding. It never silently falls back to metered API keys.
- Shared provider capability/tool contract, bounded direct-provider tool loops, Gemini thought signatures, OpenAI `store=false` encrypted reasoning, Claude model-aware effort/thinking and resolved metadata, routed OpenRouter model and provider usage reporting.
- Provider-aware Prompt Studio effective instruction/state preview. Claude Code official-app handoff is an **external** route, not an Android execution runtime.

## 🗂️ Workspace and tools

- Project/Inbox native chat grouping and reusable Project Library Markdown, text and SVG assets; exports and QR SVGs.
- Project Library v2: stable IDs, revisions, updated time, SHA-256 identity, asset kind/origin, optimistic conflict checks and safe v1 migration.
- Markdown Workspace and Docbench preview/repair/format for Markdown fences and JSON, JSON5 and YAML, with user-reviewed save-back and revision checks.
- Local `o200k_base` token counting, Bench tools, native text-to-QR SVG generation, editable `SKILL.md` bundles and isolated active-skill execution with digest-bound opt-in trust and per-call consent. No automatic network skill execution.
- Studio MCP: up to eight named endpoint configurations, encrypted on-device and excluded from Android backup, with explicit selection/update/removal and manual unauthenticated MCP 2026-07-28 discovery. Tool summaries are searchable; schemas load on selection and tool specs/call drafts can be copied offline. Saving/selecting never connects or grants access; catalogs remain ephemeral. Authentication and native-chat integration remain planned.
- MCP authorization inspection: HTTP 401 produces a sign-in-required state. Explicit resource metadata checks support challenge URLs and path/root RFC 9728 fallback; issuer inspection is separately selected, with ordered OAuth/OIDC discovery and exact issuer binding. Shows resource, scopes (challenge scopes take priority), endpoints, PKCE S256 and registration advertisements. Read-only, ephemeral and cancellable; no browser launch, registration, credentials, grant, automatic call retry or native-chat execution. Conservative preview requires the metadata resource to match the complete MCP endpoint and public HTTPS metadata/endpoints without credentials, fragments or redirects. OAuth endpoint and metadata queries are preserved; resource and issuer queries remain unsupported. Responses are UTF-8/depth/size bounded; public-DNS checks apply to every connection.
- Manual MCP tool calls: endpoint/tool/argument review, one-use approval, explicit denial and local cancellation, bounded untrusted text/JSON results. Cancellation stops local waiting; remote effects may continue. Unknown-outcome notices survive edits and server changes until dismissed. Existing catalog and `CapabilityDecision` boundary; model requests still require a user handoff. No automatic retries, persisted grants, authentication or native-chat execution. Parameter-header tools remain blocked in this preview; server-side schema validation required.

## 💬 Native conversation ergonomics

- Locally persisted native chat history, branching/forks from finished replies and provider-scoped inherited-turn replay.
- Long-chat turn timeline with star markers, 48dp targets on dense rails and on-press/hover text previews.
- Persistent native starred turns and editable one-line notes (140 characters), branch-preserving star/notes behavior.
- Native quote selection and reviewed insertion, code-fence language/source copy, display and inline math TeX copy, and Markdown table CSV copy.
- Long native prompts collapse to an eight-line preview; explicit save to project as text and Project Library insertion into composer.
- Encrypted Aistee-owned Web Draft Vault: provider-specific unsent text, debounced autosave, bounded restore points and manual copy/insert. Does not inspect or persist provider-side form fields.
- Local Web chat bookmarks: manually named provider-bound HTTPS page references with up to three folder levels, encrypted on-device, explicit open/delete and corrupt-record reset. No page text, cookies, query strings or fragments are stored. Provider-side conversations are untouched; unstable or non-linkable pages can decline bookmarking.
- Portable Web bookmark backups: explicit SAF JSON export/import across registered providers, strict bounded validation and merge-only restoration. Local encryption remains authoritative; exported files are plaintext and disclose bookmarked conversation links.
- Web Prompt Vault reads existing Project Library prompt metadata; selected content is previewed locally and only staged into the provider-scoped, user-reviewed Web draft banner on explicit action. Oversized prompts offer explicit sensitive clipboard copy instead of silent truncation. No automatic provider send or second prompt store.
- Prompt Vault imports and exports individual prompts as versioned, unencrypted JSON. Imported prompts create new assets in Project Library Inbox.
- Experimental **Web** previous/next navigation and numbered timeline picker for loaded user messages. At most 200 ephemeral renderer-local anchors; no message-text/ID extraction or persistence. Shared provider opt-out, stale-target checks and synthetic WebView smoke coverage. Candidate selectors still need signed-in provider verification; Web stars, rich previews and quote extraction remain unshipped.

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
