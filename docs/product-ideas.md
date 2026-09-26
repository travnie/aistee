# Product ideas

This is a durable backlog of product directions worth exploring in Aistee.
The ideas are inspired by observed workflows in other AI/document apps and by tools already maintained in `travnie/twojstar`; they are not implementation copies.

## Current remaining focus

The current project-plan numbering is intentionally preserved here so finished items do not renumber the remaining work. Detailed notes stay in the existing sections below.

1. **New Web provider verification:** verify embedded sign-in, upload flows and stable provider-scoped generation/unread probes for Qwen, Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI. Treat manual/account checks as a parallel lane rather than blocking unrelated work.
6. **Widgets and conversation notifications v2:** finish richer recent/pinned chat surfaces, Android conversation metadata/shortcuts and privacy-aware status previews without scraping account WebViews.
7. **UX and security polish:** continue Material 3 Adaptive/list-detail work, measured performance polish, safe export/backup coverage, WebView isolation and regression tests for sensitive surfaces.
8. **Native/API modes and jobs:** keep the normal chat simple, but expose a small provider-aware set of useful processing choices. The first slice is per-chat OpenAI Auto/Standard/Flex/Fast. Follow with async Background/Batch job handling only where the provider has a real supported API, including status/cancel/result-to-Project-Library flows. Avoid a generic wall of sampling sliders; add controls only when they have a clear cost, latency or workflow benefit.

## Markdown workspace / prompt vault

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

### Shipped: Skills v1

- Parse and validate portable `SKILL.md` files with YAML frontmatter before they enter the local library.
- Keep imported skills in a local searchable library with preview, add/replace, view source, file export, remove and rename flows.
- Reuse the Markdown workspace for direct source editing and digest-safe save-back instead of hiding skill instructions behind a form.
- Keep imports disabled by default; enabling a skill requires an explicit trust confirmation.
- Inject only explicitly enabled skill instructions into native/API chat system context. Imported `allowed-tools`, scripts and other active content remain inert.
- Enforce per-skill and combined runtime budgets, preserve activation through edits/renames and reject stale or colliding writes.
- Keep local-skill storage recovery-safe across interrupted writes and renames, including pending/committed rename recovery and tombstone cleanup.
- Keep secrets outside `SKILL.md`; importing or previewing Markdown alone never executes scripts or grants capabilities.

### Optional follow-ups: Skills++

- Add whole-directory/bundle import and export for portable skills with optional `assets/` / `scripts/` siblings while preserving the v1 non-execution default.
- Add skill URL import with the same preview, validation and trust boundary as local imports.
- Add sample prompts/examples to the skill browsing and discovery flow.
- Add AI-assisted create/edit and document-to-skill conveniences while keeping the Markdown source directly editable and authoritative.
- Introduce richer capability/permission UI only if active skill behavior is added later; file/network/secret access must remain explicit and least-privileged.

## Projects and libraries

- **Shipped foundation:** group native chats and reusable local assets into projects; chats default to Inbox and can move between projects.
- **Shipped foundation:** Project Library stores bounded local Markdown/text/SVG assets separately from provider sessions; chat exports and generated QR SVGs can be reused.
- Allow adding either a local file or pasted text as reusable project context.
- Make generated/imported artifacts easy to pin, reopen, edit, export or move into a project.
- Keep the simple chat flow intact; projects/library features should be additive rather than mandatory ceremony.

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
- Distinguish measurement modes clearly: provider-exact count, local exact-for-encoding count, and reference/fallback estimate. Never blend them into one unlabeled number.
- Compare token count and percentage delta alongside provider-reported input/output/cached/reasoning usage where available, plus cost, latency, response length and Aistee quality scores.
- Record response provenance for every Arena run, including live provider responses, cached/replayed data and local `isSimulated` fallbacks.
- Exclude cached/replayed and simulated/fallback responses from live-provider efficiency rankings by default, or show them in clearly separate groups so they cannot win on replayed or synthetic latency/cost/quality data.
- Derive efficiency views such as quality per 1k input tokens, quality per cost unit and whether extra prompt structure reduces output length, retries or failure rate.
- Add a **Prompt Tournament** mode that keeps the intent fixed while testing representations such as concise vs verbose, plain text vs Markdown/JSON/YAML, or different natural languages across selected models.
- Optimize for task success and clarity, not minimum token count alone. A slightly larger structured prompt may be the winner if it improves quality, lowers output cost or avoids another round trip.
- Make experiments reproducible by recording the model/provider identity, counter backend/encoding, prompt variant and relevant pricing snapshot instead of comparing anonymous numbers that may drift over time.
- Keep the Android local baseline fully usable offline and without an Aistee account; future platform backends should preserve the same property. Network-backed provider counting is optional and must not silently upload text merely to obtain a more exact number.
- Use accumulated Arena results to reveal practical family/model tendencies without claiming that tokenization alone explains model reasoning or internal processing.

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
- Back collection widgets with `LazyColumn` and stable item IDs so list state survives updates where the platform supports it; resize by showing more or fewer rows rather than scaling text into mush.
- Treat WebView account providers honestly: if Aistee does not own their conversation history, the widget may expose provider/chat shortcuts and locally tracked status, but must not periodically scrape remote pages just to manufacture a message list.
- Make widget rows deep-link directly to the corresponding local conversation/provider. Do not use background activity-launch trampolines.
- Add privacy controls for widget/notification previews: allow hiding message bodies, model/provider details or all sensitive text while keeping a useful title/status.
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

## Research notes from the APK batch

- Obsidian: local Markdown as source of truth, with fast new/open/search workflows and a real editor rather than attachment-only handling.
- ChatGPT: reusable file library, search, per-chat file views and dedicated previews for text/code/document formats.
- Claude: broad document/text intake plus Android text-processing/share entry points.
- Gemini: multi-file share/import flows are useful reference points for Android intake behavior.
- AI Edge Gallery: `SKILL.md` + optional `assets/` / `scripts/`, local/URL skill import, built-in/custom lists, sample prompts and explicit skill management.
- Kimi: project libraries, project instructions, reusable skills, skill create/edit/download, document-to-skill workflows and direct Save as Markdown export.
- Perplexity: library/projects/artifacts separation, pinning and Markdown open-externally behavior.
- Grok: editable files, agent instructions and export-oriented artifact workflows.
- DeepSeek: useful attachment validation/error UX, but attachment-only storage is not the target architecture for Aistee.
- Meta AI: artifact/library/preset concepts reinforce keeping generated assets reusable outside one chat.

### September 2026 APK pass

Inspected manifests, resources and bundled assets of Claude 1.260923, AI Edge Gallery 1.0.19, DeepSeek 2.5.3 and the Gemini 1.0 shell app. Only patterns are recorded here; nothing is copied.

- **Claude, Direct Share:** a `share-target` in `shortcuts.xml` with a custom category lets recent conversations appear directly in the Android sharesheet. Aistee already publishes long-lived conversation shortcuts, so it only needs the share-target declaration and the category on those shortcuts. **Shipped:** native chats are published on open, send and notification; they join the sharesheet only while notification titles are on and Quick privacy is off (option b: a generic "AI chat" target would be indistinguishable), and titled shortcuts are removed when either setting hides titles. A share into a chat stages the text in its composer and never sends; unknown shortcut IDs fall back to the normal share flow.
- **Shipped: pin a chat.** "Add to home screen" pins a native chat through `requestPinShortcut` with the Direct Share shortcut ID and the same title rule (generic "AI chat" unless titles are allowed); a toast confirms the pin. Deleting the chat disables the pin ("This chat was deleted"), and hiding titles relabels pinned chats to the generic label.
- **Claude, pin from app:** "Add to home" uses `requestPinShortcut` / `requestPinAppWidget` with confirmation receivers. Shortcuts for deleted chats are disabled with an explanatory message rather than left dangling.
- **Claude, widgets:** Glance widget with a configuration activity, a preview layout, a separate three-row provider variant and `updatePeriodMillis=0` (push updates only).
- **Claude, incognito chats:** chats that stay out of history, memory and search. For Aistee: a native chat that never reaches the archive, widgets, notifications or search. **Shipped:** "New incognito chat" keeps one conversation only in memory (badge + banner); it is filtered out at the archive boundary, gets no completion notification, Direct Reply or offline queueing, cannot be exported or saved to the Library, and is discarded when you leave it or the process dies. "Save as normal chat" converts it explicitly.
- **Claude, offline handling:** queued sends ("will send when you reconnect") and resumable streaming through a dedicated SSE service ("the rest of the reply will appear when you reconnect"). **Shipped (queued sends):** offline native/API sends become a queued user turn ("Will send when you're back online", with Edit/Cancel) executed by the Direct Reply WorkManager worker under a network constraint; account-backed Web providers are never queued. Resumable streaming remains open.
- **Claude, security posture:** app lock, cleartext-free network config, a backup allowlist of two preference files and managed-config `app_restrictions`. This matches Aistee's current direction.
- **Claude, system assistant:** `VoiceInteractionService` plus an `ACTION_ASSIST` overlay activity with its own task affinity. Useful reference if Aistee ever offers a quick-composer overlay; heavy for now.
- **Gemini, action-specific share targets:** a second share target ("Remember this") is a separate activity with its own task affinity. For Aistee: "Ask in Aistee" and "Save to Project Library" as two sharesheet entries, the latter saving without opening a chat.
- **Gemini, toolbar widget:** a Glance widget that is only a row of quick actions (file, gallery, screen share, video). An Aistee variant (new chat, paste clipboard, attach file, Prompt vault) needs no conversation history, so it is honest for Web providers too. **Shipped:** a separate 2×1 "Aistee quick actions" Glance widget with New chat, Web AI, Compare and Library, deep-linking only through `AisteeQuickActionNavigation` (new `new_native_chat` and `library` destinations) and showing no chat data.
- **AI Edge Gallery, active skills:** skills may ship `scripts/index.html` exposing `window.ai_edge_gallery_get_result(data)`; a hidden WebView runs it as JSON in / JSON out (`run_js`). Native actions go through a closed `run_intent` allowlist of named operations (current date/time, create calendar event, send email, schedule notification). A result may return `{webview: {url}}` to render an inline card. This is the reference shape for Skills++ once scripts stop being inert: sandboxed WebView with no native bridge, plus named, consented native tools.
- **AI Edge Gallery, distribution:** skills from URL, a curated featured list, MCP servers by URL with header auth, and a third-party disclaimer before adding either.
- **DeepSeek, tables and selection:** a dedicated full-screen Markdown table preview with export, and an explicit "Select text" mode for messages. **Shipped (tables):** native chat responses offer "View table" per GFM table, with a pinned-header full-screen view, Copy as CSV (sensitive clip), SAF CSV export and Save to Library; every CSV path neutralizes spreadsheet formulas. "Select text" remains open.
- **DeepSeek, attachment budget:** a pre-send warning that the model can read only a percentage of the attached files. For Aistee this belongs with the local tokenizer.
- **DeepSeek, math:** native LaTeX rendering (jlatexmath). Aistee does not render LaTeX yet. **Shipped prerequisite:** completed native chat answers now render Markdown (headings, emphasis, code, lists, quotes, inline tables, http(s) links; raw HTML stays literal) through a small Compose renderer on the existing `org.jetbrains:markdown` parser, parsed off the main thread and cached per message. LaTeX (`$...$` / `$$...$$`, native chats only, plain-text fallback) is the next separate step.
- **Gemini APK note:** the 3 MB shell only carries entry points; product logic lives in the Google app, so it yields little beyond share/widget structure.

### Follow-up: Grok Bot, Microsoft Copilot and ChatGPT APKs (26 September 2026)

Statically inspected `ai.x.grok.bot` 1.12.0, `com.microsoft.office.officehubrow` 16.0.20506.20016 and `com.openai.chatgpt` 1.2026.258 (manifest, selected resources and bundled configuration). Registration and bundled strings do not prove that a feature is enabled for every account. Nothing from these apps is copied into Aistee.

- **Grok Bot is a separate surface:** this APK identifies itself as `SandMobile` in `assets/app.config`, registers `x.ai/bot/...` and `x.ai/bot/plugin/...` links, and accepts `ACTION_SEND` for `text/plain`. It has no app-widget receiver. Its Hermes bundle contains agent/plugin/skill/computer-related identifiers, but static strings do not establish a usable integration contract or what is enabled. Do not treat this bot's deep links, Grok Web account pages and the xAI API as one interchangeable provider.
- **Copilot uses task-specific Android entry points:** separate aliases register “Ask Copilot” for selected text (`PROCESS_TEXT`), shared text, Office files/PDF and images; other PDF aliases advertise PDF-to-Word, signing and editing. There are separate Quick Settings tiles for Researcher, voice and camera, plus widget receivers. For Aistee, a dedicated share action should be considered only when a corresponding local Docbench operation actually works for that MIME type; the manifest alone does not prove conversion quality or entitlement.
- **ChatGPT separates viewing from asking:** `FilePreviewActivity` handles `ACTION_VIEW` on text, JSON/YAML, code-like types, images and PDF; `ImageEditActivity` handles `ACTION_EDIT` for images; `TextProcessorActivity` handles selected text. A separate Codex Remote widget declares `updatePeriodMillis=0`. Its Library strings describe reuse of previously uploaded files, but that is an account-backed feature, not a reason to silently upload Aistee assets.
- **Current Aistee coverage:** the main activity already supports text `PROCESS_TEXT`, content-URI `VIEW` for selected document types and `SEND`/`SEND_MULTIPLE`; the app also has Save to Project Library, widgets and a Quick Settings tile. PRs #292 (Direct Share) and #293 (quick-actions widget) already handle adjacent work. The useful later experiment is a privacy-safe *local Jobs status* widget if #285's durable job store lands, updated on state changes and showing no prompt text by default.

### Follow-up: official Grok, Mistral, Meta AI and Manus APKs (26 September 2026)

Statically inspected `ai.x.grok` 1.2.39-release.02, `ai.mistral.chat` 2.12.0 (app label “Vibe”), `com.facebook.stella` 290.1.0.42.163 (the supplied `base.apk`, Meta AI) and `tech.butterfly.app` 26.15.5 (Manus). Evidence below comes from Android manifests, selected bundled XML and resources; it does not establish account entitlements, runtime reliability or an integration API. APK binaries and proprietary assets are not imported.

| App and static evidence | Candidate for Aistee | Boundary / priority |
| --- | --- | --- |
| **Official Grok:** `GrokActivity` receives `SEND`/`SEND_MULTIPLE` for text, images, audio, video and application files; `TextSelectionIntegrationActivity` handles `PROCESS_TEXT`. Four widget receivers cover general, chat, Imagine and voice entry points; their XML sets `updatePeriodMillis=0`. Voice interaction, recognition and foreground playback services are registered. Resources include custom-agent, project, scheduled-task and Grok Bot labels. | Compare a small set of explicit launcher/widget shortcuts (chat, voice and a real locally supported tool) against Aistee's planned quick-actions widget; keep the current multi-file intake and selected-text entry points. | This is `ai.x.grok`, unlike the earlier `ai.x.grok.bot` package. Bot-related strings inside the official app show product adjacency, not a public third-party Bot API or proof that these features are enabled. Do not add a Grok-native adapter based on APK internals. |
| **Mistral Vibe / Le Chat:** `MainActivity` accepts shared text/images/application files; two Quick Settings services expose general and voice tiles. A horizontally resizable quick-actions widget uses a preview layout and `updatePeriodMillis=0`. | Consider voice as an optional action in Aistee's existing quick-actions widget or tile after the native voice flow is complete. | Aistee already has a Quick Settings tile and a widget PR (#293); avoid a duplicate surface and do not infer Mistral API support from this client. |
| **Meta AI:** `com.facebook.stella` registers a general share flow, `PROCESS_TEXT` aliases and separate image-share aliases for background removal and image extension; it also has quick-action widget and image/presets/creations Quick Settings tiles. | An action-specific Android share target can launch an *implemented* local tool directly, for example future image processing or Save to Library, with an input preview. | Only advertise MIME types and transformations that Aistee can actually perform. The image aliases are client entry points, not evidence of freely available Meta model endpoints. |
| **Manus:** a dedicated `ShareActivity` accepts `SEND`, `SEND_MULTIPLE` and `VIEW`, including PDF, Word and spreadsheet MIME types. A configurable, resizable QuickActionsWidget and `UploadKeepAliveService` are registered; resources name projects, scheduled tasks and voice-to-text. | Keep the source URI and MIME type visible in Aistee's import preview; reuse the local Project Library for an explicit task input and, when a durable job exists, status. | Registration does not prove that upload survives process death or a task finishes in the background. Do not treat remote agent execution as local Aistee functionality. |

**Smallest useful follow-up:** evaluate the combined entry-point matrix (sharesheet, selected text, widget, tile) against shipped Aistee surfaces and PRs #292/#293. Add a new shortcut only for a working action with clear input scope and privacy behavior. The official Grok package makes the earlier Grok Bot distinction more important, not less.

### Follow-up: Edge Gallery, OpenClaw Node, Perplexity and Squid Chat APKs (26 September 2026)

Statically inspected `com.google.ai.edge.gallery` 1.0.19, `ai.openclaw.app` 2026.7.4, `ai.perplexity.app.android` 2.99.0 and `co.squidapp.squidai` 1.4. The manifests, selected XML, strings and bundled assets establish packaged surfaces, not that each feature is enabled, works offline or is available to a particular account. No APK or proprietary code is committed.

| APK evidence | Useful Aistee interpretation |
| --- | --- |
| **Edge Gallery** is the same 1.0.19 build family documented above. It bundles `SKILL.md` directories with optional scripts/assets, curated/community skill entry points and local/on-device model import strings. The `schedule-notification` skill explicitly asks for a named `run_intent` with structured parameters. | Existing Skills++ and explicit built-in-tool permissions already cover the useful direction. A skill description alone must never grant calendar, notification, network or file permissions; keep imported scripts inert until a separately reviewed execution boundary exists. No duplicate feature ticket from this repeat APK. |
| **OpenClaw Node** accepts `ACTION_ASSIST` and shared text, images, audio, video, PDF, Office files, CSV and Markdown. Its `shortcuts.xml` declares an `ASK_OPENCLAW` capability with a required `prompt` parameter. Bundled Canvas/A2UI and KaTeX assets support rich presentation; `tool-display.json` maps tool names to titles/detail keys for UI display. Strings explicitly put agent/skill availability, allowlists, setup and some admin actions on the connected Gateway. | For Aistee, use typed, readable tool-call receipts and clearly show whether an action is local or needs a separately configured remote service. A display mapping is **not** a permission system, and OpenClaw's Gateway contract should not be assumed to exist inside Aistee's local-first chat. Consider a prompt shortcut only if its target and input are visible before send. |
| **Perplexity** 2.99.0 registers `PROCESS_TEXT`, `TEXT_SEARCH`, `VOICE_SEARCH`, two resizable widgets, three static shortcuts (ask, photo, computer), an assistant service, a Samsung AI-key alias and wake-word components. Its resources describe permissions for contacts, messages, alarms and actions in external apps, including a lock-screen setting. | Keep Aistee's selected-text and quick-action surfaces small and provider-aware. If future assistant/device actions are added, make every permission and lock-screen behavior explicit; do not inherit those broad capabilities from a WebView provider. Existing Projects/Library and the quick-actions widget PR cover much of the navigation lesson. |
| **Squid Chat** exposes an in-app Auto model choice, “Answer with another model”, web search, sources and chat-history search strings. It bundles Markdown, code highlighting and KaTeX rendering for its transcript. Its file UI strings specify a 5 MB cap and TXT/CSV/PDF/DOC/DOCX/XLS/XLSX types; the manifest has no public share target or widget receiver. | Compare the simple answer-with-another-model action with Aistee's Token Arena, but label each real provider, request cost and provenance. Surface attachment limits before sending instead of silently dropping files. Do not infer a reusable provider API or system-share flow from these UI strings. |

**Next concrete slice:** when the local tool registry gains per-call UI, expose the selected input scope, destination and result in one receipt; reuse the existing `ALLOW / DENY / ASK / REQUIRES_USER_INTERACTION` decision. The OpenClaw display file is a presentation reference only. Keep device assistant privileges and remote Gateway configuration out of the default chat path.
