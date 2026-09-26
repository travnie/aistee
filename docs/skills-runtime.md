# Active skills runtime (Skills++) — design

Status: **proposal, not implemented.** Skills v1 stays inert: imported `scripts/`, `allowed-tools` and other active content never run. This document describes the only shape in which a skill may run code. Implementation waits for review and then ships behind a flag that is off by default.

Reference: the AI Edge Gallery pattern recorded in `product-ideas.md` (hidden WebView running `scripts/index.html`, JSON in / JSON out, a closed allowlist of named native intents, optional inline result card).

## Goals and non-goals

- Let a skill compute something locally (format, convert, compute, render a small card) from a structured request.
- Let a skill ask for a small set of named native actions, each with visible per-call consent.
- Not a plugin system: no arbitrary Android APIs, no background execution, no persistence the user cannot see, no access to provider sessions, cookies, API keys or other skills.
- Native/API chats only. Account-backed WebView providers never call skills.

## Package and trust

- An active skill is a v1 skill bundle plus `scripts/index.html` (and sibling static assets). The bundle is imported through the existing preview/validation path; the entry file and every asset are shown in the preview with sizes and digests.
- Enabling execution is a separate, explicit trust step from enabling the skill's instructions. Editing any script file clears execution trust (digest-bound, like the v1 source digest).
- Declared metadata in frontmatter: `runtime: webview-v1`, the named native tools it may request, and an optional `network:` list of origins. Anything undeclared is denied.

## Execution sandbox

- One hidden `WebView` per call, created on demand and destroyed after the result or a timeout (default 10 s). No reuse across skills or calls.
- Content is served from an in-app virtual origin via `WebViewAssetLoader` (for example `https://skills.aistee.invalid/<skill-digest>/`), never `file://`. Each skill digest gets its own origin, so storage is isolated per skill version; storage is cleared after the call.
- Settings: JavaScript on (required); `addJavascriptInterface` **never** used; no file or content access; no geolocation, camera, microphone or notifications; no popups or new windows; Safe Browsing on; mixed content blocked.
- Network is blocked by default: `shouldInterceptRequest` answers only the skill's own virtual origin plus origins listed in `network:` (HTTPS only, shown in the trust dialog). Redirects to undeclared origins fail.
- Navigation away from the virtual origin is cancelled. Renderer crash or OOM ends the call with an error result; it must not affect chat WebViews (separate renderer priority, never counted in the chat WebView LRU).

## Entry point: JSON in / JSON out

- The host loads `index.html`, then calls `window.aistee_skill_run(requestJson)` with `evaluateJavascript` and awaits a string result (a Promise is allowed).
- Request: `{ "version": 1, "input": <object from the model or UI>, "locale": "...", "now": "<ISO-8601>" }`. No chat history, no other skills, no secrets.
- Response (validated against a strict schema, max 64 KiB):
  - `{ "result": <JSON> }` — returned to the model as the tool result;
  - `{ "card": { "title": "...", "html": "..." } }` — optional inline card, rendered later in its own sandboxed WebView with the same settings and no network;
  - `{ "tools": [ { "name": "...", "arguments": { ... } } ] }` — requests for native tools (below);
  - `{ "error": "..." }`.
- Invalid JSON, oversized output, timeout or schema mismatch produce an error result; nothing is retried automatically.

## Native tools: closed allowlist, per-call consent

- Skills request tools by name only; the host owns the implementation. Initial allowlist: `current_datetime` (no consent), `create_calendar_event`, `compose_email`, `schedule_notification`, `copy_to_clipboard`. Each maps to the existing `CapabilityDecision` policy.
- Every call that changes something outside Aistee is `REQUIRES_USER_INTERACTION`: the user sees the skill name, tool, and exact arguments, and confirms in a system-provided or Aistee-owned UI (for example the Calendar insert intent or the email composer). Nothing is sent or created silently.
- A tool not declared in frontmatter, or not on the allowlist, is `DENY`. Decisions are not remembered across calls in v1.

## Result cards

- Rendered inline under the assistant message in a fixed-height sandboxed WebView, lazily, one live at a time; long chats keep the WebView budget from `android-performance.md`.
- Cards cannot request tools or network. Links open only after an explicit tap, in the system browser.

## Flag, rollout and tests

- `activeSkillsEnabled` is off by default and not in backup. With the flag off, active skills import and preview exactly like v1 and never execute.
- Required tests before enabling: sandbox settings snapshot (no JS interface, no file access), network denial for undeclared origins, timeout and oversized-output handling, digest change clears trust, undeclared tool denied, consent required for each side-effecting tool, WebView destroyed after each call.

## Open questions for review

1. Should `network:` exist in v1 at all, or ship offline-only first?
2. Per-call consent for every side-effecting tool vs. "allow for this chat" — v1 proposes per call only.
3. Should the model be able to call a skill on its own, or only the user via an explicit action?
