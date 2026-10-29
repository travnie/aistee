# Product ideas · open backlog

**Open work and future ideas only.** Previously shipped behavior is indexed in [Implemented](implemented.md); detailed contracts stay in their runtime, design and security docs.

This is a durable backlog of product directions worth exploring in Aistee.
The ideas are inspired by observed workflows in other AI/document apps and by tools already maintained in `travnie/twojstar`; they are not implementation copies.

## Current focus

Prioritize the next feature wave in this order. Items 1 and 2 come first because they directly extend Aistee's multi-provider chat and tool experience; remaining tracks follow without blocking the first two.

1. **Web chat ergonomics** — deliver provider-scoped, current-page conversation navigation, local bookmarks/folders and Prompt Vault insertion on verified Web providers. Extend the [Web enhancements](#power-user-conversation-navigation-and-web-enhancements) already planned; preserve provider-owned history and sessions.
2. **Smart MCP and lazy tools** — make the existing capability/consent registry useful for configured MCP servers, tool browsing/testing and on-demand schema loading. See [Agent Lab / runtime interoperability](#agent-lab--runtime-interoperability); keep account-backed WebViews on explicit user actions rather than simulated native tool calls.
3. **Provider Health Center** — diagnose sign-in, uploads, activity probes, WebView compatibility and safe fallbacks in the existing [Web diagnostics](#power-user-conversation-navigation-and-web-enhancements).
4. **Context Lab and source-linked answers** — expose sent context, truncation and local-source references through the existing [Project Library](#projects-and-libraries) and [Context Lab](#agent-lab--runtime-interoperability).
5. **Live Agent Lab sessions** — extend existing Claude Code handoffs and portable runtime protocols into resumable, permission-aware sessions controlled from Android. See [Agent Lab](#agent-lab--runtime-interoperability).
6. **Adaptive workspace** — evolve the current [Material 3 Adaptive UI](#uiux-architecture-and-smoothness) into optional multi-pane chat, conversation and Project Library layouts.
7. **Sign-in recovery UX** — refine provider-specific browser/embedded sign-in troubleshooting under [identity-assisted onboarding](#identity-assisted-provider-onboarding); never imply that external browser login transfers a WebView session.
8. **Skill and plugin studio** — create/import/edit/validate portable `SKILL.md` bundles and multi-target plugin packages with Docbench and Project Library; deterministic scaffolding first, opt-in AI repair when needed. See [Skill and plugin studio](#skill-and-plugin-studio).
9. **Gemini account-backed enhancements** — preserve the official Google-account Web experience while adding Aistee-owned skill/tool affordances only through supported host capabilities and explicit user actions. See [Gemini account-backed enhancements](#gemini-account-backed-enhancements).

Ongoing work remains: verify Qwen, Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI Web sign-in/uploads/activity; maintain long-chat performance and privacy/security release gates; keep processing modes and Jobs unobtrusive. Sign in with ChatGPT is already shipped for eligible account-plan inference, not a new backlog feature. Keep Grok Web, xAI developer API, SuperGrok and Grok Bot separate, with independent authorization and capability checks.

## Markdown workspace / prompt vault

- The same local text workspace also supports small manual edits of UTF-8 configuration/source files during AI-assisted coding. Android SAF may label JSON, YAML or extensionless text with non-text MIME types, so offer all files in the picker but read them only through the existing bounded, strict UTF-8 decoder.
- Preserve imported filenames and file extensions on export, including `.py`, `.sh`, `.json`, `.json5`, `.yaml`, `.yml`, `.xml`, `.toml`, `.env` and extensionless text. Use the correct output MIME when known; do not force all text into `.md`. Exports remain explicit new files and never silently overwrite the selected source.
- Allow explicit Save source for a document opened with Android SAF in the current session. Re-read and compare the original UTF-8 byte digest (including BOM) before overwriting; reject external changes. After process recovery, keep the local draft but detach the original-file write binding. Android SAF may not guarantee atomic overwrites; export a copy for important scripts.
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

Active skill bundle execution already exists behind an opt-in switch. See [Implemented](implemented.md) and [skills runtime](skills-runtime.md); network execution remains out of scope.

Keep skill source directly inspectable and editable. Importing Markdown must never execute code or grant file, network, secret or provider-session access. See [skills-runtime.md](skills-runtime.md) for the execution boundary.

## Skill and plugin studio

- Build on the existing Markdown Workspace/Docbench editor and Project Library revision, asset-provenance and trust models; do not create another skill store or fork Docbench. Provide a creation wizard and a direct expert editor for skills and plugins.
- Accept a remote MCP endpoint URL, selected local or imported `SKILL.md`/reference files, scripts/assets, icon/logo, display name, descriptions, capabilities, permissions and intended destinations. Preview, safely inspect and select files before including them; importing never executes code or silently connects to a server.
- Keep one canonical portable package model, then generate validated, target-specific outputs for OpenAI ChatGPT/Codex, Claude/Anthropic and Gemini CLI/other supported hosts. Start with root `plugin.json`, `skills/<name>/SKILL.md`, optional root `mcp.json`, assets and optional OpenAI `.app.json` registration mapping / compatibility `.codex-plugin/plugin.json`; adapt each host's actual current requirements without scattering ad hoc file templates or implying all hosts accept the same manifest.
- Auto-create only necessary folders, manifests, metadata, paths, MCP connection mappings, descriptions and optional compatibility files. Validate schemas, referenced paths, icon formats, skill triggers, URLs/auth configuration and target-specific limitations; show the generated tree, diffs and warnings before export/install. Never embed credentials, invent registered IDs or silently publish/install/enable anything.
- Add lightweight **opt-in AI assistance** for ambiguous descriptions, converting hand-written guidance into a valid skill, fixing validator failures and suggesting tool boundaries. Reuse an explicitly chosen, eligible Aistee chat/runtime with visible destination/funding and user-reviewed output; prefer deterministic scaffolding for routine cases.
- First slice: offline skills-only import/create/edit/validate/export using Docbench and Project Library. Then portable skills + MCP manifest scaffolding and selected multi-host adapters. Later add test connection/discovery, permission previews and publication helpers. A successful export is not equivalent to a live or authorized plugin connection.

### Optional on-device drafting assistant

- **Keep the deterministic builder authoritative.** `AgentSkillDraftFactory`, `AgentSkillManifestParser`, Docbench transforms and the local skill library own valid `SKILL.md`, target-specific manifest scaffolds, safe storage and explicit previews. An AI model suggests field values, instructions or repairs, not files to execute or an automatic installation. Reuse these contracts and the existing skill editor rather than introducing a separate assistant-owned store.
- **Strictly optional, request-scoped assistance:** default to ordinary form editing and deterministic validation with no AI running. Provide explicit actions such as "Suggest description", "Review instructions" or "Fix validation"; only then initialize the chosen model and run one bounded operation. No continuous inference, automatic drafting on every keystroke, background warming, long-lived model session or periodic wakeups. Cancel when the user leaves or cancels; dispose of the inference session and release RAM/GPU resources after the request. Batch small related suggestions into one call where practical.
- **Download and device resource budget:** never bundle or auto-download multi-GB model weights just to unlock the editor. Prefer an already available supported system model, or a single user-selected small local model downloaded/imported only after showing expected size, storage needs and connection preference. Reuse installed weights across invocations; do not download them again for each request. Let users inspect, replace and remove cached models. Check storage, memory pressure, battery level, battery saver and thermal status where available; defer/stop gracefully when constrained, and offer the fully functional no-AI path. No silent metered-cloud fallback and no forced downloads to complete ordinary skill/plugin creation.
- **Measure rather than assume efficiency:** test single-task cold-start and warm-start latency, RAM/CPU/GPU occupancy, model cleanup, temperature/battery impact, repeated invocations without re-download, cancel/background interruption, unsupported devices and low-storage cases. The model stays unloaded between tasks whenever practicable; never spin or prefetch merely because the studio screen is open.
- **Gemini Nano via ML Kit Prompt API (preferred when present):** query the Android AICore/ML Kit feature status at runtime (`AVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`, `UNAVAILABLE`); never assume availability from `minSdk` or brand. The app already uses `minSdk 26`, matching the Prompt API floor, but Google supports only specific device/model combinations. Show an explicit model-download step only when eligible and not already downloading; treat `DOWNLOADING` as an in-progress state with progress feedback until setup completes, then re-check availability; constrain short editing prompts to the supported token/output budget, foreground-only inference and per-app quota. Gate structured output on actual device/API support and always validate locally.
- **LiteRT-LM as an optional local-model route:** allow a user-approved `.litertlm` model download/import outside the APK, with clear disk/RAM/battery/storage requirements and explicit lifecycle/cleanup. Start with a tested small text model rather than shipping multi-GB weights in the base app. Run model initialization and inference off the UI thread, support cancellation and disclose slower or unsupported devices. LiteRT-LM text generation does not itself provide MCP tool execution or package safety.
- **Existing native/API chat models remain a separate opt-in route:** if local AI is unavailable, offer an explicit model/destination/funding choice, not silent cloud upload or paid API fallback. Web account sessions never become backend credentials. The deterministic editor, validator and export remain usable offline without any model.
- **Narrow first assistant tasks:** suggest skill names, short trigger descriptions, Markdown instructions, field-level validation repairs and reviewable MCP tool metadata. Return a bounded typed proposal, show a preview/diff, and require user approval before replacing source. Imported references, URLs and model output are untrusted; do not send secrets or existing private files without scoped consent. Test malicious YAML/instructions, unsupported-device behavior, model cancellation and no-AI fallback.
- **Keep Android AppFunctions separate:** the official Android skill can later help expose selected, permission-gated Aistee actions to authorized on-device system agents on Android 16+. This is an external agent entry point, not the local language-model runtime and not a guarantee of Gemini Web integration.

References: [Android device-AI agent skills](https://github.com/android/skills/tree/main/device-ai), [ML Kit GenAI Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started), [supported devices and quotas](https://developers.google.com/ml-kit/genai), [LiteRT-LM Android](https://ai.google.dev/edge/litert-lm/android), [Android AppFunctions](https://developer.android.com/ai/appfunctions).

## Gemini account-backed enhancements

- Keep Google's signed-in `gemini.google.com/app` WebView and any Google AI plan usage entirely provider-owned, as with the other Web account surfaces. Do not reuse its cookies to call Gemini API, route consumer-plan usage into metered API requests or impersonate Google's first-party tool endpoints.
- Offer Aistee-owned custom skills, prompt presets, selected local context and approved Bench/MCP tool workflows alongside the Gemini tab. For ordinary provider Web chat, use explicit preview/copy/insert/share or a verified user-initiated handoff; never claim arbitrary native tool execution inside Gemini's page merely because it is embedded.
- **Investigate official Gemini custom connected apps:** Google's current MCP URL integration belongs to Gemini Spark and is eligibility-gated (currently US, 18+, personal Google account, Keep Activity enabled and English). Expose connection or guidance only when the actual account/surface supports it; never fake broad availability in Poland. Google is also migrating classic Gems to skills in November 2026, so discover the live capability rather than building a permanent adapter to a retiring menu.
- **Separate runtime alternatives:** the Gemini API is a separately funded/authenticated route with genuine function calling and MCP-capable Aistee orchestration where supported. Gemini CLI/Antigravity skills and MCP use their own host-specific configuration and authorization. Consumer Google AI Pro/Ultra sign-in for Gemini CLI/Code Assist was discontinued on 18 June 2026, so it is not a general-purpose subscription bridge for Aistee.
- First slice: local skill/prompt selection, user-reviewed insertion and clear capability badges in the existing Gemini Web tab. Next: official MCP-connected-app handoff for eligible accounts and deeper account-backed integration only if Google publishes a supported interface. Keep provider-owned data and capability boundaries intact.

References: [OpenAI portable plugin packaging](https://developers.openai.com/plugins/build/plugins), [Gemini custom connected apps](https://support.google.com/gemini/answer/17209137), [Gems migration](https://support.google.com/gemini/answer/15146780), [Gemini consumer CLI deprecation](https://developers.google.com/gemini-code-assist/docs/deprecations/code-assist-individuals).

## Projects and libraries

- Allow adding either a local file or pasted text as reusable project context.
- Make generated/imported artifacts easy to pin, reopen, edit, export or move into a project.
- Keep the simple chat flow intact; projects/library features should be additive rather than mandatory ceremony.

### Workspace storage and Docbench integration

Treat Project Library as Aistee's canonical workspace/file layer rather than creating a second Docbench-specific store.

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

1. Expand the existing Docbench transforms and Project Library preview/save-back to additional document types; preserve typed results and conflict-aware source edits.
2. Docbench MCP App and file-entrypoint integration, using host resource reads/writes where supported.
3. Optional Cloudflare R2 + D1 synchronization behind explicit enablement and conflict handling.
4. Add richer search/indexing or collaborative coordination only after the simple revision model proves insufficient.

## Prompt/file tooling borrowed from Docbench

These are Docbench-style capabilities to bring into Aistee, not changes to Docbench itself.

- Validate the construction/structure of prompt and Markdown formats.
- Offer safe repair/normalization when the structure is malformed.
- Detect and normalize EOL conventions instead of letting mixed line endings quietly accumulate.
- Keep those tools useful for manually edited prompts, imported `.md`, skills and chat exports.

## Built-in Bench tools / plugins

- Treat useful capabilities from `travnie/twojstar` Benches as first-party Aistee tools instead of requiring an external MCP or another service for capabilities Aistee should provide locally.
- Built-in tools declare inputs, outputs, permissions, local/network behavior and supported surfaces in the shared registry. Its SDK-independent decision is `ALLOW`, `DENY`, `ASK` or `REQUIRES_USER_INTERACTION`; only `ALLOW` permits execution. Unsupported/disabled/offline routes deny before requesting consent. Missing permissions on a supported user action ask; model requests for user-only tools require an explicit UI handoff and remain non-executable after a permission grant. Decisions never grant permissions or invoke an SDK automatically.
- Expose tools selectively per provider. Native/API chats can receive real tool calls where supported; account-backed WebViews should get only reliable, explicit user-approved bridges or one-tap insert/share flows rather than brittle page scraping.
- Keep one source of truth for Bench logic. Prefer extracting/reusing portable cores or a narrow typed bridge over copying implementations into Aistee and letting them diverge.
- **Docbench tool:** document/Markdown/JSON/YAML/XML validation, repair and formatting; EOL/BOM handling; the real local tokenizer; safe previews; and selected PDF operations where they fit a chat workflow.
- **Docbench Text Inspector:** reuse the existing inspector as the pre-flight view for imported/selected text before it is trusted by a model or tool. Surface exact line/column, severity and safely escaped/decoded payloads for zero-width and bidi controls, Unicode tags, variation-selector carriers, mixed-script confusables, prompt-injection-like instructions, Base64-encoded instructions and oversized encoded carriers. Detection warns and reveals; it does not silently execute, rewrite or discard the source.
- Extend the existing QR-generation action with explicit-user camera/import decoding when needed; never auto-submit decoded text.
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
- **Live runtime sessions (later track):** build on the shipped Claude Code mobile handoff and portable Claude Code/Codex protocol models. Let a trusted desktop/runtime host own actual Claude Agent SDK or Codex app-server execution; Android displays session/turn state, streamed progress, permission requests, diffs, interruption and resume, with reconnect/expiry handling. Remote session IDs and authority stay scoped to their runtime, and approvals go through the same existing Aistee consent model. Do not impersonate Claude's first-party Remote Control protocol or infer a general subscription-token transport from a mobile handoff. References: [Claude Agent SDK streaming](https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode), [Codex app-server](https://developers.openai.com/codex/app-server/).
- **DeepSeek Harness adapter:** treat DSH as an optional runtime, not another provider tab. Prefer its SDK/JSON-RPC or headless profiles behind a narrow Aistee adapter; keep DSH developer-preview status visible and never grant broad host access by default.
- **Meta Model API family:** treat Meta Model API as one provider family behind existing shared transports rather than another bespoke stack. Muse Spark can reuse the Responses/OpenAI-compatible/Anthropic-compatible boundaries while the capability matrix records its actual tools, multimodal support, context and server-side features.
- **Muse Code adapter:** treat Muse Code as another optional Agent Lab runtime beside DSH. Prefer its stable versioned session protocol (`muse serve`) / TypeScript SDK, or headless `muse exec` where appropriate, over brittle terminal scraping; map approvals, sandboxing, skills/hooks/MCP and workflow state into Aistee's shared runtime/permission surfaces.
- **Muse session handoffs:** use Muse Code session messaging as a reference for bounded coordination between independent live agents. Researcher/coder/reviewer sessions should keep separate context and permissions while exchanging explicit handoffs, review requests and status updates rather than sharing one giant hidden context.
- **Deferred tool loading:** Meta Model API tool search reinforces the lazy-tool direction: keep compact searchable tool metadata available and load full schemas only when selected, so large MCP/tool catalogs do not consume every request context by default.
- **Muse media toolbox:** expose Muse Image, Muse Voice Transcribe and SAM through shared image/audio/media-tool surfaces rather than top-level chat tabs. Generated/edited images, transcripts and segmentation outputs should flow through the same artifact/Project Library model used by other providers.
- **Muse Glimmer local preset:** support self-hosted Glimmer through the existing local/OpenAI-compatible model boundary when served by a compatible runtime such as vLLM or llama.cpp, with additional runtime adapters where useful. Keep capabilities declared/probed instead of assuming parity with hosted Muse Spark.
- **Local and remote runtimes:** allow an experimental local host where practical (including a future Termux-backed path), but also support a remote desktop/LAN/VPN runtime so Android can stay the cockpit while a desktop owns Node/Python/Git-heavy execution.
- **Aistee Bridge:** expose an optional local/LAN OpenAI-compatible endpoint from the device for trusted clients, starting with `/v1/chat/completions` and `/v1/responses`. Pair clients with an explicit connection card/QR, keep routing/funding choices visible, record compact route/token receipts, and never silently fail over from subscription/account usage to metered API billing. Treat Android foreground/battery constraints and server lifetime as product requirements rather than assuming an always-on daemon.
- **Universal MCP manager (second priority):** reuse the shared tool registry and the full `CapabilityDecision` contract: `ALLOW`, `ASK`, `DENY` and `REQUIRES_USER_INTERACTION`. The last outcome requires a separate explicit user-initiated handoff, not merely an `ASK` approval; model/tool output cannot satisfy it. List configured MCP servers, discover/inspect tool catalogs, show authorization/connection status, test safe calls, disconnect, and manage explicit per-tool permissions. Start with a user-configured Streamable HTTP server and one existing native direct-provider chat; route stdio through a trusted host, not an Android shell imitation. Never give a connected server blanket access to other providers, credentials, local files or WebView sessions. Account-backed WebViews keep user-reviewed insert/share/handoff actions, not hidden function execution.
- **MCP Extensions capability negotiation:** treat optional MCP extensions as versioned, explicitly negotiated capabilities rather than automatically available features. Advertise only implemented extension IDs under `capabilities.extensions` (and per-request metadata where required), inspect server support in the existing capability matrix, and gracefully fall back to core MCP. Keep official, experimental and host-specific extensions distinct; do not build a parallel MCP registry. Reference: [MCP extensions overview](https://blog.modelcontextprotocol.io/posts/2026-03-11-understanding-mcp-extensions/).
- **MCP Apps (`io.modelcontextprotocol/ui`):** later add opt-in interactive MCP tool cards (forms, charts, dashboards and approval steps) to Aistee-owned native chats/Agent Lab. Discover declared `ui://` resources and `text/html;profile=mcp-app` content. Before implementing the Android WebView host, explicitly decide whether the existing `:skills` process and separate data-directory boundary can be safely reused; preserve its cookie-disabled sandbox, no `addJavascriptInterface`, no file/content access, restrictive CSP and network/navigation policy. `appassets` paths are not separate origins. Validate exact origin, frame and one-use call ID on JSON-RPC `postMessage`; enforce the full `CapabilityDecision` contract, including a user-initiated handoff for `REQUIRES_USER_INTERACTION` actions. Never reuse a signed-in provider WebView or expose cookies, credentials, local files or another chat session; unsupported hosts get a safe non-UI tool result. Reference: [MCP Apps specification](https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx) and [skills sandbox](skills-runtime.md).
- **MCP Apps authoring and preview:** extend the existing Skill and plugin studio, Docbench editor and Project Library package model to link tool metadata to UI resources, validate resource paths/MIME types, preview the sandbox/CSP and permissions, and export compatible host-specific mappings. Reuse the portable package builders; do not start another visual-app store, auto-install packages or imply that every host accepts interactive UI. Reference: [MCP Apps SDK](https://github.com/modelcontextprotocol/ext-apps).
- **MCP Tasks (`io.modelcontextprotocol/tasks`):** integrate durable remote task handles, progress/polling, cancellation, expiration and reconnect into the **currently shipped Jobs surface**, evolving it toward planned Jobs v2 and the planned unified trace explorer. **Prerequisite:** add a persisted, non-terminal `INPUT_REQUIRED` (or equivalent) state to the existing `AsyncProviderJobState` domain and Jobs UI, with an explicit user-input prompt and authorized resume flow, before mapping MCP `input_required` tasks; never show those tasks as silently `RUNNING` or poll indefinitely. Keep server-owned task IDs scoped to their authorized endpoint, do not create a second scheduler, and require advertised support before using task-backed calls. Reference: [MCP Tasks extension](https://github.com/modelcontextprotocol/ext-tasks).
- **MCP Skills (`io.modelcontextprotocol/skills`):** where advertised, browse `skills/list`, inspect `skills/get` and read referenced resources into the existing Skill Studio/Project Library via an explicit user-reviewed import. Treat remote `SKILL.md`, scripts and assets as untrusted, preserve provenance and digest-bound trust, and never grant execution or filesystem access simply because a server published a skill. Reference: [MCP Skills extension](https://skills.extensions.modelcontextprotocol.io/).
- **MCP authorization extensions:** track [Enterprise-Managed Authorization and draft Client Credentials](https://github.com/modelcontextprotocol/ext-auth) in the universal MCP manager's existing auth/consent flow. Support only documented, negotiated variants for the selected endpoint and funding/identity route; do not leak, log, export or cross-reuse credentials, or silently elevate a connected server's capabilities.
- **Lazy tool discovery:** use compact, searchable descriptors and load full schemas only when selected; compare context-token overhead and tool success against the eager baseline. On compatible API-key OpenAI Responses models, evaluate native `tool_search` plus `defer_loading` for function namespaces/MCP servers, with hosted vs client-executed discovery chosen per trust/source needs. Respect provider/model/tool support and the narrower ChatGPT-plan route, which does not imply access to hosted MCP or tool search. Other runtimes use the same portable catalog but only documented provider-native mechanisms. Reference: [OpenAI Tool search](https://developers.openai.com/api/docs/guides/tools-tool-search).
- **Dynamic capability matrix:** maintain one source of truth for model/provider/runtime support for web search, URL context, file search, code execution, computer use, functions, MCP, image/audio and related capabilities. Disable impossible combinations before a request fails remotely.
- **Unified trace explorer:** normalize model requests, streamed output, tool calls, approvals, results, retries, compaction, model switches, subagents and failures into one inspectable timeline. Provider/runtime-specific details can remain expandable metadata.
- **Agent Lab forks and cross-provider handoff:** native forks already ship. Add runtime-specific forks and explicit cross-provider handoff only with a reviewed replay/consent contract.
- **Permission profiles:** layer reusable profiles such as read-only, workspace-write, network-off, no-shell and ask-everything over the existing ALLOW/ASK/DENY registry. The same policy surface should govern active skills, MCP and external agent runtimes instead of growing separate permission systems.
- **Subagents / teams playground:** where a runtime supports it, expose researcher/coder/reviewer-style isolated agents with separate traces and a final synthesis. Keep this an advanced Lab feature rather than adding team ceremony to ordinary chat.
- **Context Lab and source-linked answers (later track):** build from the existing bounded, provider-scoped history and Project Library source chips. Show a pre-send inspection of the actual assembled provider request (including instructions, selected Library excerpts and tool declarations), measured/estimated token counts, and why turns/excerpts were dropped or compacted. Map local source IDs to bounded line/section ranges and provider-returned citations where documented; distinguish provider citations from Aistee's local insertion provenance. Never display, export or persist opaque thinking/replay state, secrets or data from unrelated provider sessions. Host-managed retrieval remains a separate opt-in storage/retention choice.
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

**First delivery slice:** ship a current-page conversation timeline with reliable jump targets on one verified provider; expand to local starred moments, folders/references and Prompt Vault insertion only after navigation/restore tests pass. Add provider-by-provider opt-outs and test reloads, long conversations, session persistence, IME/focus, uploads and WebView eviction. Unsupported pages fall back to their unmodified provider UI. Do not scrape inactive conversations or claim local bookmarks are provider-side features.

- **Web conversation timeline:** extend the implemented native timeline to current-page Web turn anchors only after provider-specific on-device verification; never scrape inactive Web conversations.
- **Branch-aware navigation:** when a provider exposes visible response branches in the loaded conversation, represent branch points in the same timeline without pretending Aistee owns remote branch history.
- **Local folders for Web chats:** allow users to organize provider conversation references into local folders/subfolders without moving or rewriting the provider's data. Store only the minimum stable reference/title metadata needed for navigation and degrade cleanly if a provider URL changes.
- **Prompt Vault insertion everywhere:** reuse Aistee's canonical local prompt library across native chats and supported Web composers. Keep import/export format versioned and portable; compatibility adapters for common external prompt-vault JSON formats may be added without making those formats canonical.
- **Web starred moments:** build on native stars and notes, but require stable provider-specific turn IDs; never copy remote message bodies just to bookmark them.
- **Web quote reply:** stage user-reviewed excerpts only after a trustworthy provider-page text source exists. Native quote selection is implemented.
- **Reading controls:** per-provider comfortable-width setting, optional collapsible composer for long reading sessions and a prevent-auto-scroll toggle where a provider's page repeatedly yanks the viewport.
- **Default model helper:** where a provider exposes a stable, user-visible model selector, remember the user's preferred choice and offer a provider-scoped helper. Avoid brittle hidden endpoint calls or silent model switching.
- **Chat export:** for locally owned chats, keep Markdown as the readable source-of-truth export and optionally add JSON/PDF packaging. For Web chats, only export content explicitly available in the loaded page and label the export as a captured snapshot rather than a complete provider archive.
- **Additional copy formats:** native code, TeX and CSV actions already ship; add MathML copy only where reliable source extraction exists.
- **Mermaid and Markdown repair:** optionally render supported Mermaid blocks and repair clearly broken presentation artifacts in Aistee-owned previews without rewriting the provider page's underlying conversation.
- **Larger document context:** native long-prompt collapsing and Library save/insert already ship; extend safe file-based context workflows as needed.
- **Backup and recovery for Aistee-owned metadata:** use versioned local export/import for folders, bookmarks, prompt mappings and Web-chat references; validate before overwrite and preserve recoverable previous data on failed migrations.
- **Privacy-aware diagnostics:** add an opt-in bounded structured diagnostic log for provider adapter lifecycle, page detection and fallback paths. Keep it off by default and exclude chat bodies, credentials, cookies, full URLs and other sensitive content.
- **Provider Health Center (after Web chat + MCP):** reuse the provider registry and existing diagnostics UI for an opt-in, provider-by-provider compatibility checklist: embedded sign-in vs browser handoff, upload picker, active-generation/unread probes, WebView package/capability and last verified result. Separate automated metadata-only checks from manual device checks; show supported, limited and unverified honestly, with a safe browser/manual fallback and a disable switch for each brittle enhancement. Evaluate AndroidX WebKit document-start scripts only behind feature detection and exact allowed origins; keep scripts static, minimal and isolated without a broad JavaScript-to-native bridge. Reference: [WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat).
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

Adaptive navigation, native list-detail panes and existing Macrobenchmark journeys already ship; see [Implemented](implemented.md).
- **Adaptive Workspace (later track):** extend the current Material 3 Adaptive/navigation scaffolds into an optional list-detail layout for large screens: conversation/project list beside chat, with a supporting Library/tools pane when space allows. Preserve the focused single-pane phone chat, keyboard/IME state, predictive back and provider WebView lifecycle; test compact, expanded and foldable/window-resize journeys before broad rollout. Reuse existing navigation/state rather than a second UI shell. Reference: [Android adaptive list-detail](https://developer.android.com/develop/adaptive-apps/guides/list-detail).
- Use Material 3 Expressive selectively for discovery, prominent actions and transitions. Keep repeated chat/message interactions calmer and faster with standard motion instead of animating every surface.
- Prefer Material typography over hard-coded tiny essential labels; keep touch targets and Android font-scaling/accessibility behavior intact.
- Keep edge-to-edge, IME handling and predictive back coherent across chats, sheets, drawers and list-detail panes.
- Add a deterministic API-free **active streaming** benchmark. Existing startup, provider-switch and native-chat journeys plus Baseline Profiles are covered in [Android performance](android-performance.md).
- Re-check the current Android guidance at implementation time: Material 3, Material 3 Adaptive, Compose lazy-list performance and Baseline Profile/Macrobenchmark docs are the source of truth rather than version numbers frozen in this backlog.
- **Native code highlighting:** fenced code in native replies is currently unstyled. Prefer grammars as data plus a small portable interpreter in `shared` (one bounded pass per block, off the main thread, cached per message revision) over a WebView or a per-language Kotlin highlighter. Grammars converted offline from an established permissive grammar set keep coverage broad; record their source/licence in `THIRD_PARTY_NOTICES.md`. Unknown languages and oversized blocks stay plain.
- **Typeset display math:** keep the Unicode approximation for inline math, but render `$$` display blocks with a real TeX layout engine (bundled math font) when the expression is supported; fall back to the current TeX-source view. Copy keeps returning TeX source.
- **Offline Mermaid:** render supported Mermaid blocks from a bundled, pinned library inside an isolated, network-off WebView (same sandboxing stance as the `:skills` process), never from a CDN. Rendering failure shows the source block.
- **Tool/state micro-animations:** small vector or state-machine animations for running tools (web, MCP, memory, file, image) and thinking/search states can make tool activity legible without extra text. Keep them optional, reduced-motion aware and cheap enough for long chats.
- **Model and provider icons:** show brand icons next to model/provider names in pickers, gateway model lists (OpenRouter, AiHubMix) and chat headers. Port the keyword/regex mapping from lobe-icons `src/features/modelConfig.ts` (MIT; model ID such as `deepseek/deepseek-chat` or `glm-4.6` → icon) into `shared` as data, ordered most-specific first, with a neutral fallback. Bundle the needed SVGs from `@lobehub/icons-static-svg` as vector drawables instead of loading a CDN at runtime; record source and licence in `THIRD_PARTY_NOTICES.md`. Mono/colour variants follow the theme. Reference: [ModelIcon](https://icons.lobehub.com/features/model-icon).

### UI construction shortlist from the inspected APK batch

- **Google AI Edge Gallery:** strongest structural reference for native-feeling tool/skill management, import flows and capability surfaces.
- **ChatGPT / Claude:** strongest reference for keeping the main chat surface focused, with secondary capabilities discoverable without permanently crowding the composer.
- **Kimi:** strongest reference for fitting projects, files, skills and workspace actions into a feature-dense product without turning every action into a top-level tab.
- **Perplexity:** useful separation of chats/projects/library/artifacts and quick reuse of generated work.
- **Obsidian:** strongest local-file/source-of-truth ergonomics for create/open/search/edit flows.
- Treat these as interaction references, not a runtime benchmark; the APK inspection does not justify claiming one app has better frame timing than another.

## Android widgets and conversation notifications

The three conversation widgets, native Direct Reply, and preview privacy settings are already shipped. This section tracks additions only. See [Implemented](implemented.md).

- Expand the existing Glance widgets from local state changes; avoid aggressive polling.
- Treat WebView account providers honestly: if Aistee does not own their conversation history, the widget may expose provider/chat shortcuts and locally tracked status, but must not periodically scrape remote pages just to manufacture a message list.
- Make widget rows deep-link directly to the corresponding local conversation/provider. Do not use background activity-launch trampolines.
- Free-form typing does not belong inside a Glance/RemoteViews widget. A pinned-chat widget should open the composer; true inline text entry belongs to notification Direct Reply where Android provides `RemoteInput`.
- **Configurable quick-action slots:** let the Quick actions widget configuration pick which actions fill each slot (new chat per provider, Compare, camera/photo ask, dictation, Library, Studio, Agent Lab, Streambench). Only offer actions the capability matrix says work on this device/account.
- **Usage widget:** a small widget for local, Aistee-recorded token/cost receipts (Token Arena/Jobs data) per provider or funding route. Label estimates as estimates; never imply provider-side quota numbers Aistee does not receive.
- **In-app widget and shortcut pinning:** offer "Add to home screen" from settings and from a chat/provider menu via `requestPinAppWidget` / `requestPinShortcut`, with a success receiver. Fall back to instructions where the launcher does not support pinning.
- Re-check current Glance, conversation-notification and Direct Reply guidance at implementation time; these platform surfaces evolve independently from ordinary Compose UI.

## System entry points, background generation and voice

Observed in the October 2026 batch of major AI Android apps; these are platform patterns, not provider contracts.

- **Default digital assistant (opt-in):** register a `VoiceInteractionService` + `ASSIST` entry so Aistee can be chosen as the device assistant. Long-press opens a compact overlay above the current app with provider/route picker and composer, expandable to the full app. Screen content/screenshot assist data is a separate, off-by-default consent per invocation; never forwarded to account-backed WebViews without explicit user action. Keep it unavailable from the keyguard unless App lock semantics are defined.
- **Background native generation:** a native/API stream should survive leaving the app. Run it under a short-lived foreground service (`dataSync`/`shortService` as appropriate) owned by the shared native send runtime, write deltas to the local archive, and finish with the existing completion notification. WebView providers stay out of scope: Aistee does not keep a hidden page generating.
- **Live Update progress:** on Android 16+, show long native runs, Jobs and Agent Lab sessions as promoted ongoing notifications (`ProgressStyle`, short critical text chip) only when promotion is actually allowed: declare `POST_PROMOTED_NOTIFICATIONS`, request promotion, and check `NotificationManager.canPostPromotedNotifications()` plus `Notification.hasPromotableCharacteristics()`. Enabled notifications do not imply promotion; otherwise, and below Android 16, use a normal ongoing notification. Same privacy rules as other notifications: no titles or bodies unless opted in.
- **Notification channels per job type:** separate channels for native completions, Jobs, Agent Lab/remote sessions, scheduled work, downloads/exports, read-aloud playback and Streambench, so users can silence one class without losing others.
- **Agent approvals from notifications:** for Live Agent Lab sessions, permission requests arrive as notifications with Approve / Deny / Deny with comment (`RemoteInput`) actions, and agent questions accept a `RemoteInput` answer. Actions route through the existing ALLOW/ASK/DENY decision, carry only session + request IDs, use unique `PendingIntent`s with explicit components (immutable for plain Approve/Deny; `FLAG_MUTABLE` for `RemoteInput` actions so the reply text is delivered, as in the existing native Direct Reply), require device unlock (`setAuthenticationRequired`) and reject stale or mismatched requests.
- **Dictation:** composer microphone using the system `SpeechRecognizer` first (on-device where available), with provider transcription APIs as explicit, funded alternatives. No audio is stored by default.
- **Read aloud:** "Read aloud" for native replies via platform `TextToSpeech` in a `mediaPlayback` service with media-session controls, reusing the Streambench playback plumbing where sensible; provider TTS voices are an opt-in route.
- **Realtime voice later:** if a realtime voice mode ships, consider `androidx.core.telecom` call semantics so headset buttons, audio focus and screen-off behaviour work like a call; keep echo cancellation/Opus details inside the voice runtime. Ties into the existing Voice / realtime playground item.
- **Speech recognition service (later, optional):** Aistee could also expose a `RecognitionService` so other apps can use a user-chosen transcription route. Only with explicit enablement and clear funding/route labels.
- **Preset share targets:** generate extra share-sheet entries (`activity-alias` or dynamic sharing shortcuts) from Prompt Vault presets, e.g. "Compare in Aistee", "Translate", "Summarize", "Send to Docbench", each staging the shared content plus the preset into the composer without auto-sending.
- **More Quick Settings tiles:** besides the current tile, optional tiles for camera ask, photo ask and dictation-to-composer.
- **Open with / Edit with:** register `VIEW` for more code/config types (scripts, YAML, JSON, PHP, JS) into Markdown Workspace preview, and `VIEW`/`SEND image/*` into an image-ask flow. Register `EDIT image/*` only once a reviewed image edit is written back to the supplied URI, since `ACTION_EDIT` advertises an editor. Never auto-upload the file.
- **`PROCESS_TEXT` replace-in-place:** when `EXTRA_PROCESS_TEXT_READONLY` is false, offer a reviewed rewrite (fix, shorten, translate, tone) and return it with `setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, rewritten))` so the calling app replaces its selection. Read-only selections keep today's ask/stage behaviour.
- **App Actions URL template:** give the existing `CREATE_MESSAGE` capability a `url-template` / deep-link mapping so assistant-driven "message Aistee" fills the composer deterministically.
- **Accessibility shortcut (later):** an `accessibility-shortcut-target` for a camera-describe/read-text flow, only once a supported vision route exists, with honest capability labelling.

## Identity-assisted provider onboarding

- Do not introduce a mandatory Aistee account just to reduce provider login friction. Treat this as provider onboarding, not as a new identity silo.
- Let the user choose a preferred sign-in method such as Google, GitHub or Microsoft, then select which compatible providers to connect. Store the preference locally; do not require Aistee to own the upstream identity.
- Extend the provider capability registry with supported social sign-in methods and an authentication surface/handoff mode so the UI only offers combinations verified for that provider.
- Launch third-party identity-provider steps in Android Auth Tab / Custom Tabs where supported. These use the user's browser-backed session, so an already signed-in Google/GitHub/Microsoft account can often turn repeated credential entry into a short provider-specific confirmation flow.
- Keep every provider authorization independent. One Google/GitHub/Microsoft login is not a universal token for unrelated relying parties, and Aistee must never claim otherwise.
- Never copy browser cookies into WebView, extract OAuth tokens from provider pages, inject credentials, or automate hidden sign-in. Provider and identity-provider sessions remain owned by their respective origins.
- Track session handoff explicitly per provider: embedded session supported, browser-backed only, or manual/unsupported. If a provider cannot safely return an authenticated session to the integrated WebView, keep it browser-backed or require manual sign-in instead of bridging cookie stores.
- **Sign-in recovery (later track):** surface the supported auth path from the provider registry when embedded login fails, expires or loops: retry embedded only where verified, otherwise provide explicit system-browser/Custom Tab or manual handoff, with a clear explanation of which WebView-only features will be unavailable. Validate redirect success/cancel/timeout and passkey/federated browser cases per provider without copying cookies or claiming that browser sign-in authenticates the embedded session. Reference: [Microsoft browser-based federated auth](https://learn.microsoft.com/entra/identity/authentication/concept-passkey-browser-authentication-federated-identity-provider).
- A browser-backed provider may lose WebView-only tweaks, activity probes or composer bridges; surface that tradeoff in the capability UI rather than silently degrading features.
- Better Auth is only a future option if Aistee later needs its own optional account or wants to link several identities to Aistee-owned cloud/sync features. It is not required for this provider-login flow and cannot mint sessions for unrelated AI providers.
- Re-check provider login surfaces and Android authentication guidance at implementation time; both provider OAuth behavior and browser/WebView constraints can change independently of the app.
## Subscription-backed runtimes and capability launchers

Treat account/subscription access as another explicit runtime/funding source beside API keys, gateways, local runtimes and browser handoff.

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

Existing local-data deletion, screen/App/Quick privacy, Keystore storage and sensitive clipboard handling are in [Implemented](implemented.md). Below: remaining hardening requirements.

- Treat security as a release requirement for every feature that touches accounts, prompts, messages, files, tools, widgets or notifications; do threat modeling before wiring new cross-boundary data flows.
- Minimize sensitive state. Keep data local when practical, collect only what a feature needs, and make provider-owned login/session material stay provider-owned rather than copying cookies, OAuth tokens or passwords into Aistee storage.
- Keep native API secrets behind the existing Android Keystore-backed AES-GCM store. Never write raw keys, credentials, auth headers, prompts or message bodies to logs, analytics, crash breadcrumbs, exports or diagnostics.
- Audit the existing durable chat archive and Android backup/data-transfer rules: secrets, WebView sessions, private conversations and sensitive attachments must stay excluded by default; only deliberately safe settings may opt in.
- Classify local data by sensitivity and use separate stores for public preferences, private conversation content, imported files and secrets so retention, backup and deletion rules can be enforced independently.
- Keep WebViews least-privileged: HTTPS-only provider boundaries, no mixed content, file/content access disabled unless a scoped user action requires it, no arbitrary remote userscripts, and no JavaScript-to-native interface for untrusted provider pages. Preserve the existing provider/document guards around injected static scripts.
- Treat every imported `SKILL.md`, document, generated artifact and decoded QR/barcode as untrusted data. Parsing or previewing it must never execute scripts or silently grant file/network/secret access.
- Run the Docbench Text Inspector as a reusable security pre-flight wherever untrusted text can cross into model context, skill instructions, tool input or a share/import flow. Make hidden carriers visible to the user before trust decisions instead of relying only on prompt-injection heuristics.
- Built-in Bench tools need explicit capability declarations and least privilege. Tool output is untrusted input to the model/app; network/file capabilities, destructive actions and secret access require narrow scopes and user-visible consent where appropriate.
- Direct Reply and background work must carry only the minimum conversation identifier and reply payload required for that action. Use immutable, unique `PendingIntent`s and reject stale/mismatched provider or conversation targets.
- Notifications and widgets default to privacy-safe previews, with configurable redaction. Never surface hidden/system instructions, API keys, auth state or file contents on the lock screen simply because the foreground chat can see them.
- Exports are explicit data-release boundaries: preview what will leave the app, exclude secrets/internal diagnostics by construction, avoid hidden metadata, and never silently include unrelated conversation/project context.
- Keep TLS validation strict and never add trust-all certificate handling for provider compatibility. Release logging must not include request/response bodies or authorization headers.
- **Screen-share sensitivity:** on Android 15+, mark API-key fields, chat content and previews with `View.setContentSensitivity` so they are hidden during screen sharing/projection even when global Screen privacy (`FLAG_SECURE`) is off. Consider `DETECT_SCREEN_CAPTURE` callbacks to warn when a sensitive screen was captured.
- **Permission usage rationale:** once microphone/camera/location features ship, handle `VIEW_PERMISSION_USAGE` (app permission page) and `VIEW_PERMISSION_USAGE_FOR_PERIOD` (Privacy Dashboard, with period extras) in one exported rationale activity protected by `START_VIEW_PERMISSION_USAGE`.
- **Restricted backup mode:** evaluate `PROPERTY_USE_RESTRICTED_BACKUP_MODE` as a separate decision about the app's runtime mode during backup/restore. It does not choose backed-up data; the existing backup/data-extraction rules keep excluding secrets and session state.
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
