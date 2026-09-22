# Product ideas

This is a durable backlog of product directions worth exploring in Aistee.
The ideas are inspired by observed workflows in other AI/document apps and by tools already maintained in `travnie/twojstar`; they are not implementation copies.

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

- Group related chats, files and instructions into projects.
- Give projects a small local library rather than forcing every file through the current chat composer.
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
- Keep a small capability registry so each built-in tool declares its inputs, outputs, permissions, local/network behavior and which chat/provider transports can use it.
- Expose tools selectively per provider. Native/API chats can receive real tool calls where supported; account-backed WebViews should get only reliable, explicit user-approved bridges or one-tap insert/share flows rather than brittle page scraping.
- Keep one source of truth for Bench logic. Prefer extracting/reusing portable cores or a narrow typed bridge over copying implementations into Aistee and letting them diverge.
- **Docbench tool:** document/Markdown/JSON/YAML/XML validation, repair and formatting; EOL/BOM handling; the real local tokenizer; safe previews; and selected PDF operations where they fit a chat workflow.
- **Docbench Text Inspector:** reuse the existing inspector as the pre-flight view for imported/selected text before it is trusted by a model or tool. Surface exact line/column, severity and safely escaped/decoded payloads for zero-width and bidi controls, Unicode tags, variation-selector carriers, mixed-script confusables, prompt-injection-like instructions, Base64-encoded instructions and oversized encoded carriers. Detection warns and reveals; it does not silently execute, rewrite or discard the source.
- **Codebench tool:** local QR/barcode generation and scanning/decoding, including making a code from selected/chat text and returning decoded content to the composer when useful.
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
- Offer three configurable widget modes: **Chats** (recent/favorite conversations or provider shortcuts), **Messages** (latest locally known messages across chats), and **Pinned chat** (latest messages/status for one chosen conversation with a direct deep-link back into it).
- Back collection widgets with `LazyColumn` and stable item IDs so list state survives updates where the platform supports it; resize by showing more or fewer rows rather than scaling text into mush.
- Treat WebView account providers honestly: if Aistee does not own their conversation history, the widget may expose provider/chat shortcuts and locally tracked status, but must not periodically scrape remote pages just to manufacture a message list.
- Make widget rows deep-link directly to the corresponding local conversation/provider. Do not use background activity-launch trampolines.
- Add privacy controls for widget/notification previews: allow hiding message bodies, model/provider details or all sensitive text while keeping a useful title/status.
- For locally owned native/API chats, publish proper conversation notifications with `MessagingStyle`, `Person` metadata and long-lived conversation shortcuts so Android can surface them consistently in conversation UI and system widgets.
- **Shipped:** `RemoteInput` Direct Reply for locally owned native/API conversations, using an explicit mutable reply `PendingIntent`, WorkManager-backed network execution, the shared native send runtime, and same-notification sending/failure/completion updates.
- For account-backed WebView providers, do not pretend background Direct Reply is reliable. Until a provider has a safe supported transport, capture the reply as a staged draft and deep-link into that exact provider/chat for explicit send rather than automating a hidden WebView.
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
- Give users clear delete controls for individual chats/projects/files and a secure "clear local data/sign out providers" path that removes Aistee-owned sensitive state without pretending it can revoke provider-side data.
- Keep WebViews least-privileged: HTTPS-only provider boundaries, no mixed content, file/content access disabled unless a scoped user action requires it, no arbitrary remote userscripts, and no JavaScript-to-native interface for untrusted provider pages. Preserve the existing provider/document guards around injected static scripts.
- Treat every imported `SKILL.md`, document, generated artifact and decoded QR/barcode as untrusted data. Parsing or previewing it must never execute scripts or silently grant file/network/secret access.
- Run the Docbench Text Inspector as a reusable security pre-flight wherever untrusted text can cross into model context, skill instructions, tool input or a share/import flow. Make hidden carriers visible to the user before trust decisions instead of relying only on prompt-injection heuristics.
- Built-in Bench tools need explicit capability declarations and least privilege. Tool output is untrusted input to the model/app; network/file capabilities, destructive actions and secret access require narrow scopes and user-visible consent where appropriate.
- Direct Reply and background work must carry only the minimum conversation identifier and reply payload required for that action. Use immutable, unique `PendingIntent`s and reject stale/mismatched provider or conversation targets.
- Notifications and widgets default to privacy-safe previews, with configurable redaction. Never surface hidden/system instructions, API keys, auth state or file contents on the lock screen simply because the foreground chat can see them.
- Mark copied sensitive content with Android's sensitive-clipboard flag and avoid copying secrets automatically. Review ordinary prompt/message copying separately from API-key or credential handling.
- Add optional local privacy controls for users who need them: biometric/device-credential app lock, recents/screenshot protection for sensitive screens, and a quick privacy mode that redacts notification/widget content.
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