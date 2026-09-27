# Android intelligence system - research and plan

Status: **research, nothing implemented.** Checked against developer.android.com/ai in September 2026. All three surfaces below are alpha or preview; re-check the docs before starting and update this file when something ships.

## AppFunctions (Android MCP)

AppFunctions let an app act as an on-device MCP server: it declares functions that agents and assistants can discover and call. The Gemini integration was in a private preview with trusted testers as of May 2026.

Platform facts:

- `compileSdk` 36 or higher (Aistee is on 37). The service entry point is `@RequiresApi(36)`.
- `androidx.appfunctions:appfunctions` 1.0.0-alpha10 plus `appfunctions-compiler` through KSP. alpha10 replaced `AppFunctionConfiguration.Provider` with `@AppFunctionServiceEntryPoint`; older snippets are outdated.
- The generated service is declared with `android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"` and the `android.app.appfunctions.*` properties. `AppFunctionManager.getInstance()` returns null where the feature is unsupported.
- Functions run on the main thread by default, so they are `suspend` and switch dispatchers for I/O. `isDescribedByKDoc = true` turns KDoc into the metadata agents read.
- `@AppFunction(isEnabled = false)` plus `AppFunctionManager.setAppFunctionEnabled(...)` gates functions at runtime.
- Verification: `adb shell cmd app_function list-app-functions` and `execute-app-function`, or the AppFunctions testing agent from `github.com/android/appfunctions`. The official `appfunctions` Android skill (`android skills add appfunctions`) covers setup and migrations.

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
- Alpha/beta, no backward-compatibility guarantee. Prefix caching reduces latency for repeated long prompts.

Plan for Aistee:

- A native provider "On-device (Gemini Nano)" that is only listed when `checkStatus()` is not unavailable. Downloadable shows a download action with progress instead of failing a send.
- Keep it behind the provider capability matrix so an API break disables this provider only.
- Use prefix caching for long system prompts and enabled skills.
- Token Arena and the context warning label `o200k_base` counts for this provider as estimates.
- No background jobs, no Direct Reply generation on-device in the first slice.

## Computer Control

OEM-preloaded assistants can automate supported apps through Android Computer Control without app changes.

Plan for Aistee:

- Add it to the threat model: an unlocked Aistee with signed-in provider WebViews is reachable by such automation.
- Once testable, record how App lock and Screen privacy (`FLAG_SECURE`) interact with it, and adopt an opt-out if the framework offers one. Until then App lock is the user's control.
