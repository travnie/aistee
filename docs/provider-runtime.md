# Provider runtime notes

This document tracks provider runtime constraints and technical TODOs. The WebView provider matrix stays in the main README.

## Product priority

Aistee is account-backed web chat first. Work that improves the normal ChatGPT, Claude, Gemini, DeepSeek, Kimi, Vibe and other provider web experiences takes priority over adding more API-key-only surfaces. Native/API chat remains useful for compare, diagnostics and provider experiments, but it is the secondary path when priorities compete.

For WebViews, prefer verified provider behavior over speculative DOM hooks: keep session/login handling provider-owned, preserve uploads and focused-composer insertion, and add activity selectors only after the provider surface is verified.

## Current shape

Portable provider/model/profile data lives in `shared`. Android currently owns HTTP transport in `AiChatService`, while `StudioViewModel` coordinates chat state and rendered profile instructions.

| Provider | API shape | System instructions | Streaming | Conversation state today |
| --- | --- | --- | --- | --- |
| Gemini | `generateContent` REST | `systemInstruction` | SSE | bounded provider content replay with opaque thought signatures |
| OpenAI | Responses API | `instructions` | Responses SSE | bounded provider output-item replay with encrypted reasoning, `store=false` |
| Claude | Messages API | top-level `system` | SSE | bounded provider content replay with opaque thinking blocks |
| DeepSeek | OpenAI-compatible chat completions | `system` message | SSE | bounded visible text replay |
| Kimi | OpenAI-compatible chat completions | `system` message | SSE | bounded visible text replay |
| OpenRouter | OpenAI-compatible chat completions | `system` message | SSE | bounded visible text replay |
| AIHubMix | OpenAI-compatible chat completions | `system` message | SSE | bounded visible text replay |
| Vercel AI Gateway | OpenAI-compatible chat completions | `system` message | SSE | bounded visible text replay |

Generation and gateway model-catalog requests are coroutine-cancellable: cancelling their coroutine cancels the underlying OkHttp call. SSE parsing is shared, accepts multi-line `data:` events, propagates provider error events, and stops at provider completion or `[DONE]` where applicable.

Transport, SSE and provider-history regressions stay in local JVM/shared tests. CI runs `:app:testDebugUnitTest` and `:shared:desktopTest`; device-dependent coverage is reserved for Android/WebView behavior rather than provider transport semantics.

## History invariants

`buildBoundedProviderTextTurns` is the source of truth for replaying text history. Keep these properties intact:

- append the current user prompt exactly once;
- replay only successful, complete, non-simulated assistant responses from the selected provider;
- replay a prior user prompt only when that provider produced a replayable response to that turn;
- bound history by both turn count and approximate character budget;
- reserve history budget for the current prompt and system instruction;
- keep provider-owned replay state opaque, provider/model-scoped, budgeted only when replayable, and out of serialization/UI/export/log output.

This isolation matters in compare/switch-provider flows: a provider should not receive user turns it never answered unless that behavior is explicitly redesigned.

## Prompt Studio boundary

Rendered Studio instructions can be attached to native chat as the provider's native system/developer instruction. They are not duplicated into normal user history.

For account-backed WebViews, `StudioPromptBridge` applies the rendered prompt only to a recently focused, empty eligible composer. It refuses non-empty editors and off-provider pages. Clipboard fallback remains a UI escape hatch rather than a second injection implementation.

Do not add one universal set of sampling/reasoning parameters just because the UI can expose sliders. Provider defaults and model families differ. Controls should be capability-driven and omitted when the provider recommends its default.

The native/API chat may expose a small set of practical processing modes when a provider has a clean typed contract. OpenAI currently offers per-conversation **Auto** and **Flex** in Aistee; Flex maps only to Responses API `service_tier: "flex"`. Unsupported providers normalize back to Auto, and the setting follows the locally owned conversation so notification Direct Reply uses the same mode. Background and batch execution belong in explicit job surfaces later rather than masquerading as ordinary streaming chat toggles.

Example: Gemini 3 documentation recommends keeping temperature at the default `1.0`; lowering it can degrade reasoning behavior. A global temperature override would therefore be actively harmful for some current models.

## Reasoning/state gaps

### Gemini

The REST path remains stateless, but Aistee now retains the full model `Content` chunks returned by Gemini alongside visible text. Subsequent Gemini turns replay those model-owned chunks unchanged, including an empty-text final part when it carries `thoughtSignature`. This mirrors the GenerateContent SDK behavior while preserving the existing local/provider-scoped history boundary.

`providerReplayState` is ephemeral opaque transport state: it is excluded from `ModelChatMessage` serialization and is never rendered as chat text, exported to Markdown, logged, or rewritten. It is replayed and charged against the history budget only for the exact model that produced it; model switches and invalid/legacy state fall back to the existing visible-text reconstruction instead of making the chat unusable. Streaming capture runs through the terminal `STOP` event so signature-only final chunks are not dropped.

A future move to Gemini Interactions can still be evaluated, but only with an explicit privacy/storage decision because that would change the current client-managed stateless model.

### OpenAI

The Responses API remains client-managed and stateless with `store=false`. Aistee requests `reasoning.encrypted_content`, retains the complete ordered `response.output` array as ephemeral provider replay state, and sends those output items back between the matching user turns on the next request. This preserves encrypted reasoning items without exposing or rewriting their contents.

OpenAI replay state follows the same privacy and bounding rules as Gemini state: it is transient, provider/model-scoped, charged against the history budget only when valid and replayable, and falls back to visible assistant text after a model switch or malformed state. Streaming captures the final output array from `response.completed`, where the complete Response object is available.

A stateful alternative remains `previous_response_id`. If stateful Responses are evaluated later, remember that `instructions` are not automatically inherited through `previous_response_id`; the active Studio instruction still needs to be supplied deliberately.

### Claude

Claude reasoning is model-aware through the Anthropic Models API. Aistee reads `capabilities.thinking.types` and effort support from the same short model-metadata lookup used for `max_tokens`: adaptive-capable models receive `thinking: {type: "adaptive"}` and high effort only when reported, while legacy extended-thinking models receive a conservative budget only when `budget_tokens < max_tokens` can be satisfied. If metadata is unavailable or incomplete, generation uses the 2048 compatibility output limit and omits explicit thinking/effort controls rather than guessing capability support from the model name. That conservative outage entry is cached only briefly before metadata is retried.

Models API aliases are resolved to the returned concrete `id`. Successful alias metadata is reused briefly, while requests and replay scoping are pinned to that concrete model so an alias retarget cannot mix opaque thinking state across models. Buffered and streaming Messages responses can still report the actual serving model; a mismatch invalidates the linked requested/concrete cache entries.

When thinking is active, buffered responses retain the complete ordered assistant `content` array as ephemeral provider replay state. Streaming responses reconstruct the provider blocks from `content_block_start` plus `thinking_delta`, `signature_delta`, and `text_delta` events, delaying strict thinking-block validation until the signature delta has arrived. `thinking`, `signature`, and `redacted_thinking` data are never rendered, exported, logged, or rewritten; they are replayed only for the exact model that produced them. Responses stopped by `max_tokens` are marked partial and excluded from later history entirely. A model switch or malformed state falls back to visible assistant text.

The native path now has a typed client-tool boundary for verified direct providers. Tool-enabled turns use a bounded buffered loop so provider tool-call blocks and results can be replayed exactly within the current turn; ordinary tool-free Claude chat keeps the existing streaming path. Provider-returned thinking/redacted-thinking blocks remain opaque and are never exposed as tool input or UI text.

### Gateways

OpenRouter and other OpenAI-compatible gateways may return the model actually used. Aistee captures that response metadata when present and falls back to the requested model/route otherwise, so aliases such as `openrouter/free` can show the actual responder. Vercel AI Gateway uses the same transport path and refreshes its `/v1/models` catalog, retaining text-capable language models ordered by listed input/output token price; Vercel account and API-key budgets remain the authoritative spend controls.

## Usage and comparison metadata

Native/API responses retain provider-reported usage next to the existing local wall-clock latency. The portable message metadata normalizes input, output and total tokens while preserving optional cached-input and reasoning-token counts. Claude input includes direct, cache-creation and cache-read tokens so its normalized input matches Anthropic's billing/accounting semantics; Gemini keeps `thoughtsTokenCount` separate while preserving the provider's `totalTokenCount`.

Costs are recorded only when the response reports them. Aistee does not estimate provider prices in this path. OpenRouter requests usage accounting explicitly and may therefore supply a reported USD cost; other OpenAI-compatible gateways are parsed opportunistically when they return compatible usage fields. Hidden reasoning content remains opaque and is never exposed by these counters.

## Web/account-chat TODO

- [x] Never invent a fallback Studio prompt when no rendered instructions are active.
- [x] Keep WebView memory/LRU behavior responsive as the provider list grows without clearing provider-owned sessions; use a fresh non-consuming in-page generation probe before destructive LRU eviction so a response that started between native polls stays live when possible.
- [x] Throttle native activity polling for inactive live WebViews while retaining in-page completion latches.
- [x] Skip periodic native activity probes entirely for web providers without verified tracking selectors.
- [x] Keep the six mature account-backed chat integrations first in provider navigation while retaining every web provider under More chats.
- [x] Persist the last selected web chat and favorite chats; starred providers move to a Favorites section in provider pickers.
- [x] Recover individual WebViews after renderer-process termination without clearing provider-owned sessions; selected low-memory loss recreates the last URL, inactive loss stays evicted until selected, and a renderer crash requires an explicit retry from the provider home page.
- [x] Keep selected, untracked, or activity-uncertain WebView renderers at IMPORTANT priority; only hidden tracked MRU WebViews with freshly confirmed inactivity may waive renderer priority under memory pressure now that renderer termination recovery is in place.
- [ ] Continue provider-specific mobile performance tweaks where they are measurable and safely scoped.

### Manual/account verification (later)

- [ ] Verify embedded sign-in and file upload for Qwen, Copilot, Z.ai, Grok, Character.AI, Venice and Meta AI.
- [ ] Add generation/unread tracking for newer web providers only after stable provider-scoped controls are verified.

## API/runtime TODO

- [x] Add a lightweight provider-aware request-mode contract and OpenAI Auto/Flex UI without adding generic sampling sliders.
- [ ] Add explicit background/batch job surfaces where provider semantics and status/result retrieval can be represented honestly.
- [x] Make gateway model-catalog refresh cancellable through the same OkHttp coroutine bridge used by generation.
- [x] Add a provider capability model for transport, instruction placement, response metadata and state strategy; extend it as reasoning controls land.
- [x] Add a provider-neutral client-tool contract with bounded execution loops for Gemini GenerateContent, OpenAI Responses and Anthropic Messages; keep OpenAI-compatible gateways disabled until their routed-model tool guarantees are explicit.
- [x] Preserve Gemini thought signatures in stateless `generateContent` by replaying full model `Content` chunks unchanged.
- [x] Preserve OpenAI stateless reasoning items with `reasoning.encrypted_content` while keeping `store=false`.
- [x] Add Claude thinking/effort only through model-aware capabilities; preserve opaque thinking blocks when enabled.
- [x] Record and display the actual routed model returned by OpenRouter when available.
- [x] Resolve Claude runtime metadata from the Anthropic Models API with a short independent lookup budget; cache verified model data, cache alias mappings briefly, and use a short-lived 2048/no-reasoning outage entry so repeated failures do not block every generation while recovery remains automatic.
- [x] Add a provider-aware Prompt Studio preview showing effective instruction placement, history strategy and transport metadata without exposing API keys or hidden reasoning state.
- [x] Parse provider usage/token metadata so comparisons can include latency and provider-reported token/cost information when the API returns it.
- [x] Keep transport/SSE/history tests JVM-testable; device testing should be required only for Android/WebView behavior.

## References

- Google Gemini thought signatures: https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures
- Google Gemini 3 guidance: https://ai.google.dev/gemini-api/docs/generate-content/gemini-3
- OpenAI Responses API: https://developers.openai.com/api/reference/resources/responses/methods/create
- OpenAI model guidance: https://developers.openai.com/api/docs/guides/latest-model
- Anthropic prompting/thinking guidance: https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/prompt-templates-and-variables
- Anthropic Models API: https://platform.claude.com/docs/en/api/models/retrieve
- OpenRouter routing/fallbacks: https://openrouter.ai/docs/guides/routing/model-fallbacks
- Vercel AI Gateway OpenAI-compatible API: https://vercel.com/docs/ai-gateway/sdks-and-apis/openai-chat-completions

Keep this file focused on provider/runtime behavior. Product-facing provider status belongs in `README.md`; implementation details should live next to code when they become stable enough to stop being TODOs.
