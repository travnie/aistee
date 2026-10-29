# Android intelligence system - research and plan

Status: **proposal, not implemented**. Reviewed against current Android developer guidance on 29 October 2026. Aistee already uses compileSdk 37 / targetSdk 36, but AppFunctions and ML Kit Prompt API are not yet dependencies.

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
