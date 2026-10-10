# Product ideas

This is a durable backlog of product directions worth exploring in Aistee.
The ideas are inspired by observed workflows in other AI/document apps and by tools already maintained in `travnie/twojstar`; they are not implementation copies.

## Current focus

- Verify embedded sign-in, uploads and generation/activity probes for Qwen, Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI.
- Continue measured Material 3 Adaptive, long-chat performance and security/privacy work.
- Keep provider-aware processing modes and the existing Jobs workflow useful without turning normal chat into a settings panel.
- Add provider-sanctioned subscription/account runtimes where available, starting with Sign in with ChatGPT.
- Keep Grok Web, xAI developer API, SuperGrok and Grok Bot as separate surfaces; a direct xAI adapter uses its own API key and capability checks.

## Markdown workspace / prompt vault

- The same local text workspace also supports small manual edits of UTF-8 configuration/source files during AI-assisted coding. Android SAF may label JSON, YAML or extensionless text with non-text MIME types, so offer all files in the picker but read them only through the existing bounded, strict UTF-8 decoder.
- Preserve imported filenames and file extensions on export, including `.json`, `.json5`, `.yaml`, `.yml`, `.xml`, `.toml`, `.env` and extensionless text. Use the correct output MIME when known; do not force all text into `.md`. Exports remain explicit new files and never silently overwrite the selected source.
- Treat local `.md` files as editable source-of-truth assets, not one-shot attachments.
- Create, open, edit, save and Save As Markdown from inside Aistee.
- Keep recent/search/favorite or pinned prompt files for fast reuse.
- Let one Markdown file act as a prompt, system instructions, reusable context or a skill definition.
- Support Android document-picker access without silently replacing the selected file with a private app copy.
- Add file preview plus open externally / share flows where useful.
- Accept Android text-sharing / `PROCESS_TEXT` style entry points for quickly turning selected text into a prompt or note.

## Local-first ownership and optional cloud

- Aistee must have no account wall. First launch and normal local use must not require creating an Aistee identity, accepting cloud storage or enabling sync.
- Require authentication only where an external provider itself requires it. Signing into ChatGPT/Claude/etc. or adding an API key must not silently enroll the user in a separate Aistee account.
- Keep locally owned chats, projects, prompts, files, skills, built-in tools, settings and history usable without Aistee-hosted infrastructure.
- Local export, backup and restore are first-class features, not fallback paths. Prefer ordinary portable files/archives with a documented manifest/version so users can keep copies wherever they choose.
- Do not silently upload app data for safekeeping. Cloud sync, remote backup, cross-device restore and hosted integrations are explicit opt-ins with clear data scope and a way to turn them off again.
- Do not degrade, delay or nag-gate local features because cloud backup is disabled. If a user chooses local-only storage and loses the device without making a backup, that is an accepted consequence of the choice rather than a reason to force account creation.
- Any future cloud feature should synchronize local sources of truth rather than replace them. Signing out or discontinuing the service must leave the user's local data usable and exportable.
- Avoid cloud-only proprietary formats. A user should be able to leave with their data without asking Aistee for permission.

## Chat import / export

- Export a conversation to readable `.md`.
- Import `.md` into a new chat, composer, project context or Prompt Studio.
- Save useful chat output directly as a Markdown asset.
- Keep reusable files in a library so the same local asset can be used across chats without repeated manual recreation.
- Never export API keys, hidden reasoning, private diagnostics or other secrets as part of a chat Markdown export.

## Skills

Portable `SKILL.md` files are local, editable and trust-gated. Active skill bundles are implemented behind a disabled-by-default switch with digest-bound trust, sandboxed execution and per-call consent; network skills remain out of scope.

Keep skill source directly inspectable and editable. Importing Markdown must never execute code or grant file, network, secret or provider-session access. See [skills-runtime.md](skills-runtime.md) for the execution boundary.

## Projects and libraries

- **Shipped foundation:** group native chats and reusable local assets into projects; chats default to Inbox and can move between projects.
- **Shipped foundation:** Project Library stores bounded local Markdown/text/SVG assets separately from provider sessions; chat exports and generated QR SVGs can be reused.
- Allow adding either a local file or pasted text as reusable project context.
- Make generated/imported artifacts easy to pin, reopen, edit, export or move into a project.
- Keep the simple chat flow intact; projects/library features should be additive rather than mandatory ceremony.

### Workspace storage and Docbench integration

Treat Project Library as Aistee's canonical workspace/file layer rather than creating a second Docbench-specific store.

- **Shipped local foundation:** Project Library schema v2 keeps stable asset IDs while adding editable revisions, updated time, SHA-256 content identity, asset kind/origin and optimistic revision checks. The v1 index migrates in place without moving the existing local asset directory; remote storage references and ETags remain future sync concerns.
- Keep local storage authoritative for normal use. Local projects, files, prompts, instructions, skills and generated artifacts remain usable without an Aistee account, network access or cloud sync.
- Model prompts, system instructions, reusable context, skill sources and generated artifacts as typed workspace assets over the same storage contract instead of separate silos. Keep `trvny/.ai` canonical for portable profiles/instructions/skills; Aistee may link, import or synchronize through an explicit adapter without becoming a competing upstream.
- Give the shared layer one narrow asset-store contract for list/read/write/search/delete/version operations. The current Android Project Library store becomes the local adapter; platform/cloud adapters must preserve the same ownership and provenance rules.
- Reuse Docbench as the document editor/transform engine over these assets. Typed transformations should return a small contract such as `kind`, `content`, `warnings` and `sourceIds`; preview and local validation happen before an edited/generated result is saved back to Project Library.
- Build the Docbench ChatGPT integration as Skills + MCP App/file entrypoints over Docbench's existing parsers and preservation rules. Do not fork another editor or upload host-provided files merely to make the plugin work.
- If optional cross-device sync is added, keep it on the existing Cloudflare lane: Worker for the narrow sync/auth/MCP control plane, R2 for file bytes, and D1 for metadata, revisions and search/index state. Do not add Vercel storage for the same concern.
- Defer Durable Objects until a real need for live collaboration, leases or stronger coordination appears. Ordinary optimistic revision/ETag conflict handling should be enough for initial sync.
- Cloud sync is explicit opt-in. Never silently upload local assets, never make cloud availability a prerequisite for local editing, and keep export/restore possible without the hosted service.
- Keep remote deletion semantics honest: deleting an Aistee local copy, an Aistee Cloudflare copy and a provider-hosted upload are separate operations with separate receipts.

Suggested implementation order:

1. **Shipped local foundation:** Project Library v2 editing/revision/hash/origin metadata with stable asset identity, safe v1 migration, conflict-aware Markdown save-back and existing chat source provenance.
2. Docbench typed transforms plus preview/save-back into Project Library. **Shipped local slices:** Markdown Workspace previews Docbench fence repairs and explicit JSON/JSON5/YAML formatting using shared typed transform results (kind, content, warnings, source IDs); applying requires an unchanged draft revision, and bound Project Library files still use conflict-aware Save source. Broader document transforms and unified external Docbench adapters remain open.
3. Docbench MCP App and file-entrypoint integration, using host resource reads/writes where supported.
4. Optional Cloudflare R2 + D1 synchronization behind explicit enablement and conflict handling.
5. Add richer search/indexing or collaborative coordination only after the simple revision model proves insufficient.

## Prompt/file tooling borrowed from Docbench

These are Docbench-style capabilities to bring into Aistee, not changes to Docbench itself.

- Validate the construction/structure of prompt and Markdown formats.
- Offer safe repair/normalization when the structure is malformed.
- Detect and normalize EOL conventions instead of letting mixed line endings quietly accumulate.
- Port the proven Docbench token counter into the editing/prompt workflow: use a real bundled tokenizer locally (currently `js-tiktoken` with `o200k_base`), not a character-count estimate; keep lazy loading, encoder reuse and debounced recounting.
- Keep those tools useful for manually edited prompts, imported `.md`, skills and chat exports.

## Built-in Bench tools / plugins

- Treat useful capabilities from `travnie/twojstar` Benches as first-party Aistee tools instead of requiring an external MCP or another service for capabilities Aistee should provide locally.
- Built-in tools declare inputs, outputs, permissions, local/network behavior and supported surfaces in the shared registry. Its SDK-independent decision is `ALLOW`, `DENY`, `ASK` or `REQUIRES_USER_INTERACTION`; only `ALLOW` permits execution. Unsupported/disabled/offline routes deny before requesting consent. Missing permissions on a supported user action ask; model requests for user-only tools require an explicit UI handoff and remain non-executable after a permission grant. Decisions never grant permissions or invoke an SDK automatically.
- Expose tools selectively per provider. Native/API chats can receive real tool calls where supported; account-backed WebViews should get only reliable, explicit user-approved bridges or one-tap insert/share flows rather than brittle page scraping.
- Keep one source of truth for Bench logic. Prefer extracting/reusing portable cores or a narrow typed bridge over copying implementations into Aistee and letting them diverge.
- **Docbench tool:** document/Markdown/JSON/YAML/XML validation, repair and formatting; EOL/BOM handling; the real local tokenizer; safe previews; and selected PDF operations where they fit a chat workflow.
- **Docbench Text Inspector:** reuse the existing inspector as the pre-flight view for imported/selected text before it is trusted by a model or tool. Surface exact line/column, severity and safely escaped/decoded payloads for zero-width and bidi controls, Unicode tags, variation-selector carriers, mixed-script confusables, prompt-injection-like instructions, Base64-encoded instructions and oversized encoded carriers. Detection warns and reveals; it does not silently execute, rewrite or discard the source.
- **Partly shipped:** Codebench native chat tool can generate QR from inline chat text and save SVG to Project Library. Camera/import decode stays explicit-user only for now.
- **Streambench companion:** a persistent compact radio/media player that can keep playing while chatting, with station search/favorites/recents and now-playing metadata. Treat playback primarily as app UI, not as a fake model tool; optional chat actions can be layered on later.
- Let users enable/disable built-in tools globally and, where useful, per chat/provider, with clear capability/permission indicators.

## Token Arena / prompt efficiency lab

Treat Token Arena as the overlap between Bench tooling and a small experimental Lab: a place to compare how the same intent is represented, tokenized, priced and answered across model/provider families.

- Keep the bundled local `o200k_base` counter as the always-available Android reference baseline today. Keep the shared `TokenCounter` contract portable so other clients can add equivalent local backends later. Label every result by encoding and never present `o200k_base` as a universal token count for unrelated model families.
- Add model/provider-specific counters only when they provide useful signal through an official count API or a lightweight, trustworthy tokenizer. Do not bundle a tokenizer zoo merely to make the comparison table look complete.
- Distinguish measurement modes clearly: provider-exact count, provider-reported estimate, local exact-for-encoding count, and reference/fallback estimate. Never blend them into one unlabeled number; two counts from the same provider/model still belong to different series when one is exact and the other is only an estimate.
- Compare token count and percentage delta alongside provider-reported input/output/cached/reasoning usage where available, plus cost, latency, response length and Aistee quality scores.
- Record response provenance for every Arena run, including live provider responses, cached/replayed data and local `isSimulated` fallbacks.
- Exclude cached/replayed and simulated/fallback responses from live-provider efficiency rankings by default, or show them in clearly separate groups so they cannot win on replayed or synthetic latency/cost/quality data.
- Derive efficiency views such as quality per 1k input tokens, quality per cost unit and whether extra prompt structure reduces output length, retries or failure rate.
- Add a **Prompt Tournament** mode that keeps the intent fixed while testing representations such as concise vs verbose, plain text vs Markdown/JSON/YAML, or different natural languages across selected models.
- Optimize for task success and clarity, not minimum token count alone. A slightly larger structured prompt may be the winner if it improves quality, lowers output cost or avoids another round trip.
- Make experiments reproducible by recording the model/provider identity, counter backend/encoding, prompt variant and relevant pricing snapshot instead of comparing anonymous numbers that may drift over time.
- Keep the Android local baseline fully usable offline and without an Aistee account; future platform backends should preserve the same property. Network-backed provider counting is optional and must not silently upload text merely to obtain a more exact number.
- Use accumulated Arena results to reveal practical family/model tendencies without claiming that tokenization alone explains model reasoning or internal processing.

## Agent Lab / runtime interoperability

Treat Aistee's next layer as an LLM and agent workbench rather than another pile of provider-specific chat screens. Keep the normal chat simple; experimental orchestration belongs in an explicit Lab surface.

- **Agent Lab:** run one task through selected runtimes/models and compare result, latency, token/cost usage, tool calls, retries, steps and failures. Reuse Token Arena concepts, but compare whole agent runs rather than prompt tokenization alone.
- **DeepSeek Harness adapter:** treat DSH as an optional runtime, not another provider tab. Prefer its SDK/JSON-RPC or headless profiles behind a narrow Aistee adapter; keep DSH developer-preview status visible and never grant broad host access by default.
- **Meta Model API family:** treat Meta Model API as one provider family behind existing shared transports rather than another bespoke stack. Muse Spark can reuse the Responses/OpenAI-compatible/Anthropic-compatible boundaries while the capability matrix records its actual tools, multimodal support, context and server-side features.
- **Muse Code adapter:** treat Muse Code as another optional Agent Lab runtime beside DSH. Prefer its stable versioned session protocol (`muse serve`) / TypeScript SDK, or headless `muse exec` where appropriate, over brittle terminal scraping; map approvals, sandboxing, skills/hooks/MCP and workflow state into Aistee's shared runtime/permission surfaces.
- **Muse session handoffs:** use Muse Code session messaging as a reference for bounded coordination between independent live agents. Researcher/coder/reviewer sessions should keep separate context and permissions while exchanging explicit handoffs, review requests and status updates rather than sharing one giant hidden context.
- **Deferred tool loading:** Meta Model API tool search reinforces the lazy-tool direction: keep compact searchable tool metadata available and load full schemas only when selected, so large MCP/tool catalogs do not consume every request context by default.
- **Muse media toolbox:** expose Muse Image, Muse Voice Transcribe and SAM through shared image/audio/media-tool surfaces rather than top-level chat tabs. Generated/edited images, transcripts and segmentation outputs should flow through the same artifact/Project Library model used by other providers.
- **Muse Glimmer local preset:** support self-hosted Glimmer through the existing local/OpenAI-compatible model boundary when served by a compatible runtime such as vLLM or llama.cpp, with additional runtime adapters where useful. Keep capabilities declared/probed instead of assuming parity with hosted Muse Spark.
- **Local and remote runtimes:** allow an experimental local host where practical (including a future Termux-backed path), but also support a remote desktop/LAN/VPN runtime so Android can stay the cockpit while a desktop owns Node/Python/Git-heavy execution.
- **Aistee Bridge:** expose an optional local/LAN OpenAI-compatible endpoint from the device for trusted clients, starting with `/v1/chat/completions` and `/v1/responses`. Pair clients with an explicit connection card/QR, keep routing/funding choices visible, record compact route/token receipts, and never silently fail over from subscription/account usage to metered API billing. Treat Android foreground/battery constraints and server lifetime as product requirements rather than assuming an always-on daemon.
- **Universal MCP manager:** browse configured servers, connect/disconnect, inspect live status and tools, test calls and expose explicit permissions. Support Streamable HTTP directly where possible and stdio through a capable runtime host.
- **Lazy tool discovery:** do not dump hundreds of MCP/function schemas into every model context. Add searchable/deferred tool catalogs inspired by DSH MCP-lens-style progressive disclosure and provider-native tool-search mechanisms where available.
- **Dynamic capability matrix:** maintain one source of truth for model/provider/runtime support for web search, URL context, file search, code execution, computer use, functions, MCP, image/audio and related capabilities. Disable impossible combinations before a request fails remotely.
- **Unified trace explorer:** normalize model requests, streamed output, tool calls, approvals, results, retries, compaction, model switches, subagents and failures into one inspectable timeline. Provider/runtime-specific details can remain expandable metadata.
- **Conversation forks:** **shipped for native chats:** branch from a finished reply into a new local chat that keeps the earlier turns plus that reply (compare-mode siblings dropped), continues with the reply's provider and records its source. The original is untouched. History replay stays provider-scoped: each provider replays only inherited turns it answered, so branching discloses nothing new; an explicit cross-provider handoff is future work. The Messages widget shows each inherited turn once, including after the source chat is deleted. Runtime/Agent Lab forks remain open.
- **Permission profiles:** layer reusable profiles such as read-only, workspace-write, network-off, no-shell and ask-everything over the existing ALLOW/ASK/DENY registry. The same policy surface should govern active skills, MCP and external agent runtimes instead of growing separate permission systems.
- **Subagents / teams playground:** where a runtime supports it, expose researcher/coder/reviewer-style isolated agents with separate traces and a final synthesis. Keep this an advanced Lab feature rather than adding team ceremony to ordinary chat.
- **Context Lab:** show what history/context is actually sent, measured token usage, compaction events, cache usage and dropped/truncated material. Make provider-exact facts distinct from local estimates.
- **Provider-agnostic routing:** support policies such as free-first, fast-first, local-first, privacy-first and fallback chains. Do not duplicate an upstream router's internals; let Aistee route between configured endpoints/runtimes and record why a route was chosen.
- **Jobs v2:** extend the existing durable Jobs surface to Gemini Batch and, later, background jobs exposed by local/external runtimes. Keep one queue for status, cancellation, retry and result capture into Project Library.
- **Local/OpenAI-compatible models:** add a first-class custom OpenAI-compatible endpoint boundary with convenient presets for local servers such as Ollama, LM Studio, llama.cpp/vLLM-style deployments and compatible gateways. Capabilities must be declared/probed rather than assumed from the protocol label.
- **Memory backend seam:** keep one Aistee memory contract with local-first storage, scopes, deduplication and provenance/citations. Optional external backends may implement the contract, but should not create competing hidden memory stores.
- **Skill portability scanner:** inspect imported skill bundles and report portable instructions/assets versus runtime-specific tools/scripts. Offer adapters only where the semantics are clear; never silently broaden permissions.
- **Mutation / diff cards:** render file writes and edits from agent/tool runs as compact diffs with provenance and approval state instead of burying mutations in raw tool logs.
- **Eval Bench:** run saved datasets/cases through selected models or runtimes and track regressions over time. Preserve model IDs, runtime versions, tool sets, pricing snapshot and whether a result was live, replayed or simulated.
- **Reproducible run bundle:** export a scrubbed bundle containing prompts, model/runtime IDs, tool definitions, settings, results, usage and trace metadata without credentials or hidden reasoning, so an experiment can be inspected or rerun elsewhere.
- **Voice / realtime playground:** provide one experimental surface for realtime speech APIs with comparable latency, turn-detection and transcription metadata rather than provider-specific one-off demos.
- **Provider-native tools mode:** expose provider-hosted search/file/code/computer/MCP capabilities through the shared capability model instead of reimplementing every tool locally.
- **LLM changelog radar:** optionally surface concise official provider/runtime capability changes relevant to configured adapters so Aistee can flag newly available or deprecated features without turning the app into a news reader.

DeepSeek Harness is especially interesting because its model adapters, tool registry, session log, agent loop, jobs, sandbox and UI are plugin-composed. Its own safety notice says the project is experimental, unaudited developer-preview software, so any integration should stay opt-in, least-privileged and visibly separated from Aistee's trusted local core.

Meta references inspected in September 2026: [Meta Model API / Muse overview](https://dev.meta.ai/docs/overview), [Muse Code](https://dev.meta.ai/docs/muse-code), [tool search](https://dev.meta.ai/docs/tool-search) and [Muse Glimmer](https://dev.meta.ai/models/muse-glimmer).

## Power-user conversation navigation and Web enhancements

Recent browser-extension projects around Gemini, Claude, ChatGPT and DeepSeek reinforce a set of useful Web-chat ergonomics. Treat them as interaction references, not code sources; Aistee should implement provider-scoped adapters that preserve its existing WebView isolation rules.

- **Conversation timeline:** add a compact rail for long conversations with markers for user turns, one-tap jump, optional preview and locally stored starred/key moments. For locally owned native chats, derive it from the archive. For account-backed Web chats, operate only on the currently loaded page through a small provider adapter; never background-scrape history. **Shipped for native chats:** chats with at least four user turns show a thin rail of jump targets, one per user turn plus starred replies (stars highlighted), thinned on very long chats to what fits the rail at full 48dp tap targets, keeping both ends and stars first. Long-pressing (or hovering) a marker previews the turn's first 80 characters without jumping. Web-chat timelines remain open.
- **Branch-aware navigation:** when a provider exposes visible response branches in the loaded conversation, represent branch points in the same timeline without pretending Aistee owns remote branch history.
- **Local folders for Web chats:** allow users to organize provider conversation references into local folders/subfolders without moving or rewriting the provider's data. Store only the minimum stable reference/title metadata needed for navigation and degrade cleanly if a provider URL changes.
- **Prompt Vault insertion everywhere:** reuse Aistee's canonical local prompt library across native chats and supported Web composers. Keep import/export format versioned and portable; compatibility adapters for common external prompt-vault JSON formats may be added without making those formats canonical.
- **Starred messages / bookmarks:** local bookmarks should survive app restarts and can optionally carry a short user note. Do not copy whole remote conversations merely to implement a star. **Shipped for native chats:** star any turn from its header; stars persist locally with the chat, follow copied turns into branches, clear with the chat, and a Starred messages list in the chat menu jumps to each one and adds or edits a one-line local note (140 characters) per star; notes follow stars into branches and clear with them. Web-chat stars remain open.
- **Quote reply:** selecting text in a supported loaded chat can stage a quoted excerpt in the composer with an explicit user action. Do not auto-send. **Shipped for native chats:** a reply's "Select text or quote" action opens its Markdown source for precise selection; Quote appends the selection (or the whole reply) to the draft as a blockquote and never sends. Web chats remain open: page JavaScript and DOM copy handlers can substitute clipboard text, and native Copy menus alone do not distinguish read-only credential fields. A Web quote design must establish a trustworthy excerpt source or require explicit review of user-supplied text before formatting.
- **Reading controls:** per-provider comfortable-width setting, optional collapsible composer for long reading sessions and a prevent-auto-scroll toggle where a provider's page repeatedly yanks the viewport.
- **Default model helper:** where a provider exposes a stable, user-visible model selector, remember the user's preferred choice and offer a provider-scoped helper. Avoid brittle hidden endpoint calls or silent model switching.
- **Chat export:** for locally owned chats, keep Markdown as the readable source-of-truth export and optionally add JSON/PDF packaging. For Web chats, only export content explicitly available in the loaded page and label the export as a captured snapshot rather than a complete provider archive.
- **Rich-content copy tools:** one-tap copy for source forms such as TeX/MathML and, later, tables/code blocks where extraction is reliable. Prefer preserving semantic source over screenshot-style copying. **Shipped for native chats:** fenced code blocks in rendered replies show their language and a one-tap copy of the block's source (sensitive clip); display math blocks copy their TeX source; tables already copy as CSV. Tapping inline math copies its TeX source. MathML copy remains open.
- **Mermaid and Markdown repair:** optionally render supported Mermaid blocks and repair clearly broken presentation artifacts in Aistee-owned previews without rewriting the provider page's underlying conversation.
- **Long-text helpers:** turn oversized text into a local file/library asset, attach a text asset to a prompt and collapse long instruction blocks in Aistee UI so large prompts remain manageable. **Shipped (collapse) for native chats:** long user prompts show an 8-line preview with Show more/Show less; the full text stays in the archive, copy and export. **Shipped (Library save) for native chats:** a long prompt can be saved as a plain-text asset in the chat's project with one tap (not in incognito chats). **Shipped (insert) for native chats:** text assets in the Project Library can be appended to the composer draft (unquoted, never auto-sent).
- **Backup and recovery for Aistee-owned metadata:** use versioned local export/import for folders, bookmarks, prompt mappings and Web-chat references; validate before overwrite and preserve recoverable previous data on failed migrations.
- **Privacy-aware diagnostics:** add an opt-in bounded structured diagnostic log for provider adapter lifecycle, page detection and fallback paths. Keep it off by default and exclude chat bodies, credentials, cookies, full URLs and other sensitive content.
- **Per-provider feature switches:** timeline, width tweaks, quote helper and similar DOM-facing enhancements should be independently disableable so one provider redesign cannot destabilize unrelated Web chats.

Interaction references inspected in September 2026:
- [Voyager](https://github.com/Nagi-ovo/voyager): cross-provider timeline, starred moments, prompt vault, folders, export and small reading/composer helpers.
- [claude-nexus](https://github.com/Qiuner/claude-nexus): Claude-focused folders, timeline previews, prompt-library portability, export and chat-width controls.
- [DeepSeek Enhancer](https://github.com/dlshuangchenyue1210/DeepSeek-Enhancer): provider-local folders, selective Markdown export, rich formula copy, versioned backup/recovery and privacy-conscious diagnostic logging.
- [ChatGPT Conversation Timeline](https://github.com/Reborn14/chatgpt-conversation-timeline): minimal multi-provider timeline with local starred-message persistence and per-site enable/disable controls.

## UI/UX architecture and smoothness

### Shipped foundations

- The September 2026 dependency pass aligns the Compose BOM, Material 3 and Material 3 Adaptive baseline with their stable release lines.
- The main shell adapts through Material 3 Adaptive: compact layouts use bottom navigation and wider layouts use rail-class navigation.
- Account-backed Web chats stay immersive on compact screens and keep provider navigation persistently visible on rail-class layouts.
- Native chat rows use stable message IDs and `contentType` so Lazy layouts can reuse compatible compositions.
- Native chat auto-scroll follows the reader only while they stay near the latest message; scrolling upward exposes a jump-to-latest control instead of yanking the list during streaming.
- Streaming text is coalesced to a UI-friendly cadence, and the generating pulse updates alpha in the graphics layer instead of driving color recomposition.
- Native message bubbles use an adaptive readable width with phone gutters and an expanded-screen cap.
- Locally owned conversations use Material 3 Adaptive list-detail navigation: conversation history stays beside the active chat on wide windows, while compact layouts navigate between list and detail panes with normal back behavior.

### Next
- Use Material 3 Expressive selectively for discovery, prominent actions and transitions. Keep repeated chat/message interactions calmer and faster with standard motion instead of animating every surface.
- Prefer Material typography over hard-coded tiny essential labels; keep touch targets and Android font-scaling/accessibility behavior intact.
- Keep edge-to-edge, IME handling and predictive back coherent across chats, sheets, drawers and list-detail panes.
- Add Macrobenchmark journeys and Baseline Profiles for cold/warm start, opening a chat, provider switching, long-message-list scrolling, returning from a detail pane and active streaming. Judge smoothness from release builds and frame timing, not debug feel.
- **Shipped:** Macrobenchmark journeys for cold/warm start, provider switching, opening a new or long existing native chat, returning to the list and long-conversation scrolling, with the native chat journeys also feeding the Baseline Profile. Active streaming remains open because it needs a deterministic local stream source instead of a provider call.
- Re-check the current Android guidance at implementation time: Material 3, Material 3 Adaptive, Compose lazy-list performance and Baseline Profile/Macrobenchmark docs are the source of truth rather than version numbers frozen in this backlog.

### UI construction shortlist from the inspected APK batch

- **Google AI Edge Gallery:** strongest structural reference for native-feeling tool/skill management, import flows and capability surfaces.
- **ChatGPT / Claude:** strongest reference for keeping the main chat surface focused, with secondary capabilities discoverable without permanently crowding the composer.
- **Kimi:** strongest reference for fitting projects, files, skills and workspace actions into a feature-dense product without turning every action into a top-level tab.
- **Perplexity:** useful separation of chats/projects/library/artifacts and quick reuse of generated work.
- **Obsidian:** strongest local-file/source-of-truth ergonomics for create/open/search/edit flows.
- Treat these as interaction references, not a runtime benchmark; the APK inspection does not justify claiming one app has better frame timing than another.

## Android widgets and conversation notifications

- Build first-party home-screen widgets with Jetpack Glance and responsive layouts; update them from local state changes rather than aggressive polling.
- Native/API conversations now persist locally with stable conversation IDs. Reuse that durable archive as the source for message widgets and conversation notifications instead of introducing a second history store.
- **Shipped:** three configurable widget modes: **Chats** (recent local conversations with independently opt-in titles and latest-message previews), **Messages** (latest locally known messages across chats), and **Pinned chat** (latest messages for one chosen conversation with a direct deep-link back into it).
- **Shipped:** collection widgets use `LazyColumn` with stable item IDs plus Glance `SizeMode.Exact` / `LocalSize`; vertical resize now exposes 1–8 rows as space grows instead of hard-capping every widget at three or scaling text into mush.
- Treat WebView account providers honestly: if Aistee does not own their conversation history, the widget may expose provider/chat shortcuts and locally tracked status, but must not periodically scrape remote pages just to manufacture a message list.
- Make widget rows deep-link directly to the corresponding local conversation/provider. Do not use background activity-launch trampolines.
- **Shipped:** privacy controls for widget/notification previews. Conversation titles and message bodies are separate opt-in toggles per widget and for notifications (both off by default), Quick privacy redacts all of them at once without touching saved choices, and neither surface shows model or provider details.
- **Shipped:** locally owned native/API chats publish `MessagingStyle` notifications with `Person` metadata and privacy-safe long-lived conversation shortcuts, so Android can associate notifications with the exact local conversation without exposing the chat title through shortcut metadata.
- **Shipped:** `RemoteInput` Direct Reply for locally owned native/API conversations, using an explicit mutable reply `PendingIntent`, WorkManager-backed network execution, the shared native send runtime, and same-notification sending/failure/completion updates.
- **Shipped:** for account-backed WebView providers, background Direct Reply remains disabled; notification-style reply input can be staged locally, deep-linked to the exact provider and explicitly inserted into an empty focused composer for the user to review and send. Aistee does not automate a hidden WebView send.
- **Shipped foundation:** keep the shared provider capability matrix for notifications/widgets (`messageHistory`, `completionNotification`, `directReply`, `draftReply`, `deepLink`) as the source of truth so UI only promises actions that actually work.
- Free-form typing does not belong inside a Glance/RemoteViews widget. A pinned-chat widget should open the composer; true inline text entry belongs to notification Direct Reply where Android provides `RemoteInput`.
- Re-check current Glance, conversation-notification and Direct Reply guidance at implementation time; these platform surfaces evolve independently from ordinary Compose UI.

## Identity-assisted provider onboarding

- Do not introduce a mandatory Aistee account just to reduce provider login friction. Treat this as provider onboarding, not as a new identity silo.
- Let the user choose a preferred sign-in method such as Google, GitHub or Microsoft, then select which compatible providers to connect. Store the preference locally; do not require Aistee to own the upstream identity.
- Extend the provider capability registry with supported social sign-in methods and an authentication surface/handoff mode so the UI only offers combinations verified for that provider.
- Launch third-party identity-provider steps in Android Auth Tab / Custom Tabs where supported. These use the user's browser-backed session, so an already signed-in Google/GitHub/Microsoft account can often turn repeated credential entry into a short provider-specific confirmation flow.
- Keep every provider authorization independent. One Google/GitHub/Microsoft login is not a universal token for unrelated relying parties, and Aistee must never claim otherwise.
- Never copy browser cookies into WebView, extract OAuth tokens from provider pages, inject credentials, or automate hidden sign-in. Provider and identity-provider sessions remain owned by their respective origins.
- Track session handoff explicitly per provider: embedded session supported, browser-backed only, or manual/unsupported. If a provider cannot safely return an authenticated session to the integrated WebView, keep it browser-backed or require manual sign-in instead of bridging cookie stores.
- A browser-backed provider may lose WebView-only tweaks, activity probes or composer bridges; surface that tradeoff in the capability UI rather than silently degrading features.
- Better Auth is only a future option if Aistee later needs its own optional account or wants to link several identities to Aistee-owned cloud/sync features. It is not required for this provider-login flow and cannot mint sessions for unrelated AI providers.
- Re-check provider login surfaces and Android authentication guidance at implementation time; both provider OAuth behavior and browser/WebView constraints can change independently of the app.
## Subscription-backed runtimes and capability launchers

Treat account/subscription access as another explicit runtime/funding source beside API keys, gateways, local runtimes and browser handoff.

- Start with OpenAI Sign in with ChatGPT using the documented OSS dynamic registration and protected local credential storage.
- Keep account-plan, API-key and WebView sessions separate; never turn consumer cookies into an API.
- Discover account-scoped models from the authorized runtime rather than reusing an unrelated static/API-key catalog.
- Resolve presets, tools and launchers against the selected runtime's capability matrix before send.
- Never silently fall from subscription allowance into metered API billing or weaken a requested capability.
- Specialized launchers such as image work, coding, research and file transforms should appear only when the active runtime actually supports them.

Detailed auth, routing and account-plan mechanics belong in [provider-runtime.md](provider-runtime.md).

## Grok / xAI direct provider plan

Keep Grok Web, the xAI developer API, the official Grok app/Bot and SuperGrok billing separate. A native xAI route should use its own API key, live model discovery and provider capability checks, preferring Responses-style capabilities where supported.

Do not reuse consumer cookies, infer API entitlement from a Web login, or treat Grok Bot deep links as a public integration contract. File uploads need explicit provider-side lifecycle/retention handling.

Current provider research lives in [provider-capability-research.md](provider-capability-research.md).

## Security and privacy architecture

- Treat security as a release requirement for every feature that touches accounts, prompts, messages, files, tools, widgets or notifications; do threat modeling before wiring new cross-boundary data flows.
- Minimize sensitive state. Keep data local when practical, collect only what a feature needs, and make provider-owned login/session material stay provider-owned rather than copying cookies, OAuth tokens or passwords into Aistee storage.
- Keep native API secrets behind the existing Android Keystore-backed AES-GCM store. Never write raw keys, credentials, auth headers, prompts or message bodies to logs, analytics, crash breadcrumbs, exports or diagnostics.
- Define explicit backup/transfer rules before durable chat storage ships. The current manifest allows backup; secrets, WebView/session state, private conversations and sensitive attachments must be excluded by default, with only deliberately safe settings opted into backup or device transfer.
- Classify local data by sensitivity and use separate stores for public preferences, private conversation content, imported files and secrets so retention, backup and deletion rules can be enforced independently.
- **Shipped:** Privacy includes a confirmed **Delete all local data** action backed by Android's platform clear-storage path. It removes Aistee-owned local state, including chats, projects/library, skills, API keys, drafts, embedded provider session data and settings, without pretending to revoke provider-side data or browser-owned sessions; files already exported outside Aistee remain user-owned.
- Keep WebViews least-privileged: HTTPS-only provider boundaries, no mixed content, file/content access disabled unless a scoped user action requires it, no arbitrary remote userscripts, and no JavaScript-to-native interface for untrusted provider pages. Preserve the existing provider/document guards around injected static scripts.
- Treat every imported `SKILL.md`, document, generated artifact and decoded QR/barcode as untrusted data. Parsing or previewing it must never execute scripts or silently grant file/network/secret access.
- Run the Docbench Text Inspector as a reusable security pre-flight wherever untrusted text can cross into model context, skill instructions, tool input or a share/import flow. Make hidden carriers visible to the user before trust decisions instead of relying only on prompt-injection heuristics.
- Built-in Bench tools need explicit capability declarations and least privilege. Tool output is untrusted input to the model/app; network/file capabilities, destructive actions and secret access require narrow scopes and user-visible consent where appropriate.
- Direct Reply and background work must carry only the minimum conversation identifier and reply payload required for that action. Use immutable, unique `PendingIntent`s and reject stale/mismatched provider or conversation targets.
- Notifications and widgets default to privacy-safe previews, with configurable redaction. Never surface hidden/system instructions, API keys, auth state or file contents on the lock screen simply because the foreground chat can see them.
- **Shipped:** route clipboard writes through one Android helper; private chat/draft/share, Studio/system/profile and skill content uses Android's sensitive clipboard hint, while ordinary URLs and explicitly privacy-safe diagnostics stay unmarked. API keys are never copied automatically.
- **Shipped:** optional local privacy controls include device-local Screen privacy, App lock and Quick privacy. Quick privacy temporarily redacts conversation titles and message previews in Aistee home-screen widgets and native-chat notifications without overwriting each surface's saved preview choices.
- **Shipped:** Studio tools > Privacy holds all three controls. Screen privacy applies `FLAG_SECURE` to every Aistee activity through application-level lifecycle callbacks. App lock covers every activity with a biometric/device-credential check on a fresh process or after a minute away; short trips such as file pickers keep the unlock, and it stays inactive without a secure lock screen. Quick privacy is a reversible redaction override for widgets and native-chat notifications.
- Exports are explicit data-release boundaries: preview what will leave the app, exclude secrets/internal diagnostics by construction, avoid hidden metadata, and never silently include unrelated conversation/project context.
- Keep TLS validation strict and never add trust-all certificate handling for provider compatibility. Release logging must not include request/response bodies or authorization headers.
- Add security regression tests alongside feature tests: provider host/navigation boundaries, intent/deep-link validation, backup exclusions, secret/log redaction, tool capability gating, imported-skill non-execution, notification reply target isolation and export sanitization.
- Re-check current Android security, WebView, backup, clipboard, notification and storage guidance at implementation time; security-sensitive platform behavior changes independently from ordinary UI APIs.
## UX patterns worth keeping in mind

- File/library layer: reusable assets should outlive one attachment action.
- Markdown preview: generated or imported Markdown should be viewable before reuse/export.
- Quick actions: new prompt/note, open recent asset, search library and use in chat should stay close at hand.
- Skill management: create, edit, try, import, export and enable/disable from one obvious place.
- Project context: chats + files + instructions belong together when the user chooses to group them.
- Local-first editing: portable files remain understandable and editable outside Aistee.

## Research-derived UX rules

Static inspection of other AI/document apps is useful as design input, not as proof of provider contracts or runtime guarantees. The durable lessons are:

- keep chat focused and move dense tools/projects/library actions into secondary surfaces,
- expose task-specific Android entry points only for actions Aistee actually supports,
- prefer explicit local file/project provenance over silent uploads,
- keep widgets/shortcuts privacy-safe and push-driven,
- use typed tool-call receipts and separate presentation metadata from permission policy,
- treat broad assistant/device permissions as separate opt-in capabilities,
- use format-specific previews and clear local/cloud labels for documents and media,
- keep remote agent/runtime integrations visibly distinct from local Aistee execution.

Historical APK-specific observations are intentionally not maintained here; current product decisions belong in the sections above and provider/runtime facts in the dedicated research docs.
