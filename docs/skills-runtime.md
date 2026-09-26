# Active skills runtime (Skills++) — design

Status: **reviewed proposal, implementation pending.** Skills v1 stays inert: imported `scripts/`, `allowed-tools` and other active content never run. This document describes the only shape in which a skill may run code. The implementation ships behind a flag that is off by default.

Reference: the AI Edge Gallery pattern recorded in `product-ideas.md` (hidden WebView running `scripts/index.html`, JSON in / JSON out, a closed allowlist of named native intents, optional inline result card).

## Goals and non-goals

- Let a skill compute something locally (format, convert, compute, render a small card) from a structured request.
- Let a skill ask for a small set of named native actions, each with visible per-call consent.
- Not a plugin system: no arbitrary Android APIs, no background execution, no persistence the user cannot see, no access to provider sessions, cookies, API keys or other skills.
- Native/API chats only. Account-backed WebView providers never call skills.

## Package and trust

- An active skill is a v1 skill bundle plus `scripts/index.html` (and sibling static assets). The bundle is imported through the existing preview/validation path; the entry file and every asset are shown in the preview with sizes and digests.
- Enabling execution is a separate, explicit trust step from enabling the skill's instructions. Editing any script file clears execution trust (digest-bound, like the v1 source digest).
- Declared under the portable `metadata:` map so plain Agent Skills parsers still accept the file: `aistee-runtime: webview-v1`, `aistee-tools:` (space-separated tool names) and optional `aistee-network:` (space-separated origins). Anything undeclared is denied.

## Execution sandbox

- One hidden `WebView` per call, created on demand and destroyed after the result or a timeout (default 10 s). No reuse across skills or calls.
- Content is served through `WebViewAssetLoader` at the reserved `https://appassets.androidplatform.net/skills/<skill-digest>/`, never `file://`. URL paths do **not** make separate origins. The path handler serves only the current skill digest, with DOM storage disabled and no reuse of the WebView between calls. Do not claim per-skill origin or cookie isolation from a path.
- Settings: JavaScript on (required), DOM storage off; `addJavascriptInterface` **never** used; no file or content access; no geolocation, camera, microphone or notifications; no popups or new windows; Safe Browsing on; mixed content blocked.
- Network is **off in v1**, including redirects, service workers, WebSockets and navigation; the asset handler serves only the current bundle and denies all other resources. Do not rely on `shouldInterceptRequest` alone for every network channel: prove the block with device tests before execution can be enabled.
- Future network skills require a separately reviewed process/data-directory boundary (for example a dedicated WebView process with its own `setDataDirectorySuffix` before WebView initialization), explicit origin consent and verified cookie isolation from account-backed WebViews. `aistee-network` metadata remains inert in v1 and never grants access by itself.
- Navigation away from the virtual origin is cancelled. Renderer crash or OOM ends the call with an error result; it must not affect chat WebViews (separate renderer priority, never counted in the chat WebView LRU).

## Entry point: JSON in / JSON out

- The host loads `index.html` and uses `evaluateJavascript` only to start `window.aistee_skill_run(requestJson)`. For a synchronous result or resolved Promise, the page sends one bounded JSON result through an origin-restricted `WebViewCompat.addWebMessageListener` registered before loading; the native listener checks the main frame, exact origin and a one-use call ID before accepting it. The `evaluateJavascript` result callback itself does not await a Promise. If the required messaging API is unavailable, the skill fails closed.
- Request: `{ "version": 1, "input": <object from the model or UI>, "locale": "...", "now": "<ISO-8601>" }`. No chat history, no other skills, no secrets.

## Who can run a skill

Both the user and the model:

- **User:** an explicit "Run skill" action on an enabled, trusted skill, with a JSON or text input the user types.
- **Model:** trusted skills are exposed as function tools in native/API chats. A model call is shown inline as a chip with the skill name and the exact input, and its result is visible before it is sent back.
- V1 skills have no network. Model-initiated inputs remain visible in the transcript; any future network support needs a separate review of consent and whether chat content can leave the device.
- Incognito chats never call skills, and Quick privacy disables model-initiated calls.
- Response (validated against a strict schema, max 64 KiB):
  - `{ "result": <JSON> }` — returned to the model as the tool result;
  - `{ "card": { "title": "...", "html": "..." } }` — optional inline card, rendered later in its own sandboxed WebView with the same settings and no network;
  - `{ "tools": [ { "name": "...", "arguments": { ... } } ] }` — requests for native tools (below);
  - `{ "error": "..." }`.
- Invalid JSON, oversized output, timeout or schema mismatch produce an error result; nothing is retried automatically.

## Native tools: closed allowlist, per-call consent

- Skills request tools by name only; the host owns the implementation. Initial allowlist: `current_datetime` (no consent), `create_calendar_event`, `compose_email`, `schedule_notification`, `copy_to_clipboard`. Each maps to the existing `CapabilityDecision` policy.
- Every call that changes something outside Aistee is `REQUIRES_USER_INTERACTION`: the user sees the skill name, tool, and exact arguments, and confirms in a system-provided or Aistee-owned UI (for example the Calendar insert intent or the email composer). Nothing is sent or created silently.
- A tool not declared in `aistee-tools`, or not on the allowlist, is `DENY`. Consent is per call in v1: decisions are not remembered, not even for the current chat. An "allow for this chat" option is a later, separate change once v1 is in use.

## Result cards

- Rendered inline under the assistant message in a fixed-height sandboxed WebView, lazily, one live at a time; long chats keep the WebView budget from `android-performance.md`.
- Cards cannot request tools or network. Links open only after an explicit tap, in the system browser.

## Flag, rollout and tests

- `activeSkillsEnabled` is off by default and not in backup. With the flag off, active skills import and preview exactly like v1 and never execute.
- Required tests before enabling: sandbox settings snapshot (no `addJavascriptInterface`, no DOM/file access), network denial across fetch/redirect/service-worker/WebSocket paths, same-origin path isolation between two bundles, asynchronous Promise success and timeout, rejection of wrong-origin/frame/call-ID messages, oversized-output handling, digest change clears trust, undeclared tool denied, per-call consent and WebView disposal.

## Review decisions

1. Network in v1: disabled until an isolated data profile and complete network boundary are demonstrated on device.
2. Consent granularity: per call in v1; revisit "allow for this chat" later.
3. Invocation: both user and model for offline skills only. Future network skills need separate consent review.
4. Async completion: one-shot origin-checked WebView message; `evaluateJavascript` starts the call but does not await the Promise.

References: [Android WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader), [WebView data directories](https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String)), [WebViewCompat messages](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener)), [same-origin policy](https://developer.mozilla.org/en-US/docs/Web/Security/Same-origin_policy).
