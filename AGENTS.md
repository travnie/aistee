# AGENTS.md

Aistee: Kotlin Multiplatform; Android is the first shipping client.

## Boundaries

- If Aistee consumes `.ai`, use an explicit adapter/import boundary; keep `.ai` canonical upstream.
- Prefer extending existing module structure over parallel implementations.
- Improve account-backed WebView chats first; native/API-key chat is secondary unless the task explicitly targets it.

## Multiplatform

- Put portable provider/model/profile/domain logic in `shared`; platform APIs in platform modules/source sets.
- Prefer Compose Multiplatform for reusable UI; do not abstract away native WebView, file picker, authentication, secure storage or lifecycle behavior.
- Keep provider-specific behavior behind adapters/registries, not hostname checks scattered through UI code.
- Keep WebView tweaks small, reviewable and provider-scoped.
- Long-chat mobile performance is core: bound live WebViews, pause inactive views, evict under memory pressure without clearing provider sessions.
- File upload is core provider capability: preserve accept types, multiple selection, cancellation and platform picker lifecycle.
- Never intercept, persist, export or log provider passwords, session cookies, OAuth tokens or equivalent account credentials.
- Treat embedded account pages as untrusted web content; minimize WebView privileges needed for compatibility.

## Workflow

- Inspect current main, open PRs and recent changes before overlapping work.
- Keep one logical change per PR.
- Use project name `Aistee` and canonical Android application ID `ais.tee`; shared modules use distinct namespaces under that root.
- Keep docs short; update when provider support or security assumptions change.
