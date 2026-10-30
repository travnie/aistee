# Android intelligence system - research and plan

Status: **debug-only AppFunctions prototype; release integration remains proposed**. Debug builds include a generated, permission-protected token-count service and default-off Privacy consent. Release builds contain no AppFunctions service, metadata, control or runtime dependency. Reviewed on 30 October 2026; compileSdk 37 / targetSdk 36.

## AppFunctions (Android MCP)

AppFunctions expose typed app operations to supported Android system agents. It is not a general-purpose network MCP server and should not be advertised as one.

Platform facts:

- `compileSdk` 36 or higher (Aistee is on 37). The service entry point is `@RequiresApi(36)`.
- The latest reviewed official migration path uses `androidx.appfunctions:appfunctions` **1.0.0-beta01** and the KSP compiler. Use an abstract `AppFunctionService` annotated `@AppFunctionServiceEntryPoint`; KSP generates the concrete service and metadata. Do not use the removed `AppFunctionConfiguration.Provider` or standalone service dependency.
- The generated service is declared with `android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"` and the `android.app.appfunctions.*` properties. `AppFunctionManager.getInstance()` returns null where the feature is unsupported.
- Use `@AppFunctionDeclaration` (replacing `@AppFunction`), explicit `isDescribedByKDoc` flags and `appfn:instructions` metadata. Suspend functions still need an I/O dispatcher for blocking work. Keep KDoc accurate for each function and public serializable property.
- Ship functions **disabled by default** with explicit user opt-in; check the beta01 enablement and declaration signatures against the actual resolved library before coding.
- Verify generated service/manifest/metadata, discovery, opt-in state, cancellation and side-effect permissions on a compatible emulator or device. Discover supported `adb shell cmd app_function` arguments from device help. Official docs: [AppFunctions release notes](https://developer.android.com/jetpack/androidx/releases/appfunctions) and [beta01 migration guide](https://developer.android.com/agents/skills/device-ai/appfunctions/references/migrate-to-service-entry-point).

Google's guidance: system agents may process the user's request on a server; expose narrow, non-sensitive capabilities and confirm destructive actions in the app.

Plan for Aistee:

- Expose only pure transforms and write-only actions that already exist as shared cores:
  - `countTokens(text)` via the local `o200k_base` counter, labelled with its encoding;
  - `validateMarkdown(text)` / `repairMarkdown(text)` via the Docbench actions;
  - `markdownTablesToCsv(text)` via `extractMarkdownTables` + `toCsv()` (formula neutralization on);
  - `saveTextToLibrary(title, text)` into the Project Library Inbox, same rules as the Save to Aistee Library share target.
- Never expose: reading chats, library assets, skills or drafts; API keys; provider sessions; anything destructive; anything that needs network.
- Every function goes through the shared Bench registry decision and qualifies only if it would be `ALLOW` for an explicit user action on a network-free route.
- All functions ship with `isEnabled = false`. An opt-in Privacy toggle, "Allow system assistants to use Aistee tools", enables them with `setAppFunctionEnabled`; turning it off or Delete all local data disables them.
- Tests: JVM tests for the exposure allowlist (the registry decision per function), instrumentation test that functions are disabled until the toggle is on, and an `execute-app-function` smoke script in CI once an emulator image supports it.


### Safe local first slice

`SystemAssistantTokenCountAction` is the first device-independent implementation slice. It accepts **inline text provided in a specific user request** only after the separate system-assistant opt-in, explicit user initiation, first-party Docbench enablement and `READ_USER_SELECTED_CONTENT` grant are all present. It re-evaluates the existing `BuiltInBenchTool.DOCBENCH_DOCUMENT.availability` decision for a network-free explicit action, and calls the existing `LocalTokenCounter` off the caller thread. It has no network, storage, UI, cookies, conversation reads or exported Android component.

The first JVM tests are in `SystemAssistantTokenCountActionTest`. A release Android system-agent adapter **must not** wire this to discovery until the platform service, Android 16+ checks, Privacy opt-in, per-request consent, cancellation and enabled/disabled instrumentation tests in phase 2 are implemented. Do not advertise AppFunctions support in the README or the shipped-features index before then.

## On-device model provider (Gemini Nano)

The ML Kit GenAI Prompt API sends text or image+text requests to Gemini Nano through AICore and returns text or structured output. It runs locally, so it works offline and without an API key.

Platform facts:

- `checkStatus()` reports unavailable, downloadable, downloading or available; AICore downloads the model on demand. Right after device setup or an AICore reset the feature can be temporarily unavailable.
- Supported only on specific recent devices (Pixel 9/10 series and a list of Samsung, Xiaomi, OnePlus, Honor, vivo, Motorola and other models). Best quality on Pixel 10 (nano-v3).
- Current documented Prompt API dependency is `com.google.mlkit:genai-prompt:1.0.0-beta4`. Support is device/model-dependent. Check structured-output availability, close clients on teardown, and measure prefix caching rather than assuming it helps.

Plan for Aistee:

- A native provider "On-device (Gemini Nano)" that is only listed when `checkStatus()` is not unavailable. Downloadable shows a download action with progress instead of failing a send.
- Keep it behind the provider capability matrix so an API break disables this provider only.
- Evaluate prefix caching only for long repeated stable instructions when benchmarks justify it; never warm a model automatically just to edit a skill.
- Token Arena and the context warning label `o200k_base` counts for this provider as estimates.
- No background jobs, Direct Reply generation or silent cloud fallback in the first slice. Unsupported devices keep the deterministic offline editor.

Reference: [ML Kit GenAI Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started).

## Computer Control

OEM-preloaded assistants can automate supported apps through Android Computer Control without app changes.

Plan for Aistee:

- Add it to the threat model: an unlocked Aistee with signed-in provider WebViews is reachable by such automation.
- Once testable, record how App lock and Screen privacy (`FLAG_SECURE`) interact with it, and adopt an opt-out if the framework offers one. Until then App lock is the user's control.
## Delivery order and acceptance gates

The [open product backlog](product-ideas.md) owns feature priority. This file owns the Android-specific approach; do not add a second tool registry, skill store, or Android-only domain core.

| Phase | Smallest deliverable | Acceptance / blocker |
| --- | --- | --- |
| 0 · Done | Fix CodeQL Kotlin build on JDK 21 in the existing shared Android CI toolchain. | Advanced CodeQL and central setup policy shipped in [Aistee PR #438](https://github.com/travnie/aistee/pull/438), [central PR #66](https://github.com/trvny/.github/pull/66), with compile-only extraction follow-up in [Aistee PR #440](https://github.com/travnie/aistee/pull/440). |
| 1 · Done, device-free | Add a **non-exported** local `countTokens(text)` action using the existing Docbench capability decision, first-party enablement and o200k tokenizer. No new tool registry. | JVM tests cover default-deny, user initiation, explicit content grant, revoked opt-in, invalid input and tokenizer parity. This is a preparation boundary only: **release has no AppFunctions service, OS exposure, manifest entry or Privacy toggle**. |
| 2 · Debug prototype; release device-gated | AppFunctions service adapter behind an explicit, default-off Privacy toggle. Reuse existing app dependency wiring; generated service and XML match the merged manifest. | JDK/KSP/AGP-compatible build; OS discovery and invocation on a supported Android 16+ device; opt-out, cancellation, background thread, error paths and Delete all local data tested. Never expose chat reads, account state, network or arbitrary filesystem. |
| 3 · Device-gated, after Skill Studio | Optional Gemini Nano drafting **once the planned Skill and plugin studio has shipped**. Until then, prototype only through the existing Markdown Workspace/skill editor with explicit preview and approval. | FeatureStatus checks, user-approved download, measured memory/latency, cancellation, client `close()`, unsupported-device fallback, structured-output feature detection and a reviewed diff before saving. No silent cloud/API fallback. |
| 4 · Device-gated | Test Android Computer Control with App lock and Screen privacy while a provider WebView is signed in. | Document real OEM/platform behavior and any available opt-out. No assumption that `FLAG_SECURE` blocks every form of UI automation. |

**Implementation notes from the Android development skill:**

- AppFunctions beta APIs require fresh verification before compiling: `@AppFunctionDeclaration`, explicit `isDescribedByKDoc`, `appfn:instructions`, `AppFunctionName` from `androidx.appfunctions`, and a KSP-generated service, with `BIND_APP_FUNCTION_SERVICE` manifest protection. Aistee's debug build uses AppFunctions beta01 with KSP 2.3.13; release has no AppFunctions integration. Do not add Hilt, Koin or a second DI layer just for this.
- Unsupported Android devices still use all existing local tools. Handle missing/omitted typed arguments and explicit nulls deliberately, preserve coroutine cancellation, and check actual `adb shell cmd app_function help` before writing invocation scripts.
- For Gemini Nano, separate library minSdk from device/AICore availability; only infer when `FeatureStatus.AVAILABLE`, close the client on teardown, reject untrusted output as tool instructions, and guard structured output behind `isStructuredOutputFeatureAvailable()`.
- Favor JVM fakes for pure decisions and Robolectric for Android host glue. Only the generated service, manifest integration, OS discovery and user authentication boundaries require instrumented/device tests. See [Android performance](android-performance.md) for the existing test stack and Baseline Profile rules.


## Debug AppFunctions verification

- Debug only: `BaseAisteeAppFunctionService.countTokens`, generated service and XML; `BIND_APP_FUNCTION_SERVICE` protection. `isEnabled = false` in schema.
- Privacy opt-in grants only caller-supplied inline text processing. Docbench remains independently required. App lock blocks this prototype entirely. Caller `userRequested` is an intent assertion, never authorization or proof of user presence.
- Consent stays outside backup/transfer allowlists. Local denial is written before OS enablement changes; failed indexing/cancellation cannot grant access. Service rechecks consent before returning a count. Delete all local data clears consent with app storage.
- JVM coverage: consent/tool denial, omitted request, App lock, revocation, typed input errors, tokenizer parity, and unsupported Android. Existing action tests remain canonical policy coverage.
- Android 16 smoke coverage: generated manifest protection, schema ID/default denial and bounded polling for OS discovery. Full assistant-to-service invocation, cancellation, opt-out and clear-data behavior still require a supported-device check before release promotion.
- No startup hook, release/R8 changes or profiled journey changes. Baseline Profile regeneration is not applicable; existing performance CI still validates release coverage.
- Promotion gate: move dependencies/service/control into production only after real invocation and user-consent semantics are verified. No claim of production assistant support yet.

