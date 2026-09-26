# Terminal agent in Aistee: proposal

## User flow

From **Studio Tools → Terminal agent**, choose a native/API provider and a verified model, select a project workspace, write a task, and start a session. The screen shows the prompt, the selected model, the commands the model proposes, terminal output, changed files, and a Stop action. Settings include a per-session command policy (review each command or explicitly authorize automatic execution), a run limit, and an output limit. A session can save its transcript or selected artifacts to Project Library; ordinary chat remains unchanged.

A model response must be a structured tool call with command, arguments, working directory, and purpose. Aistee executes it through one Android terminal adapter, returns the bounded result to the same provider/model turn, and repeats within the run limit. User text and model output are never interpolated into a shell string by the app. Shell expressions requested by a model remain executable commands and must be displayed verbatim before approval when review is enabled. The UI labels the provider, model, execution mode, workspace, and every command receipt.

This belongs to Aistee's **native/API** path: existing `shared` provider/model selection, `AiChatService` transport, and the built-in tool registry can supply a typed agent loop. Account-backed WebViews do not expose a supported third-party tool-call contract or their provider credentials. Do not extract cookies or silently pass Aistee API keys into a shell or another agent CLI. A CLI such as Claude Code, Codex, or Gemini CLI would be a separately configured runtime with its own authentication and model inventory; choosing a model in Aistee does not automatically configure that CLI.

## Execution choices

| Route | What it delivers | Constraint |
| --- | --- | --- |
| **External Termux adapter (first device experiment)** | Opt-in command dispatch to a user-installed Termux via its documented `RUN_COMMAND` Intent, with exit code and bounded result returned to Aistee. | Android permission plus Termux `allow-external-apps=true` are required. The documented result arrives on completion; it is not a live, embedded terminal stream. Termux runs with its own permissions, so choosing a working directory does not confine commands to it. |
| **Embedded terminal runtime (target for the full UI)** | Live output and input, session lifecycle and cancellation inside Aistee, with an app-controlled process and PTY/terminal renderer; optional packages and Linux userland can be evaluated after the basic session works. | Requires an Android runtime spike covering executable delivery, process/PTY management, foreground lifecycle, storage access, architecture support, packaging size, and isolation of Aistee's secrets. Bundling PRoot alone is not a security sandbox. |
| **Artemis on a development host** | Device UI smoke tests and diagnostics via CLI/MCP on an ADB-connected phone or emulator. | Artemis runs on the host and drives Android; it is not an on-phone terminal agent runtime. |

Termux's `RUN_COMMAND` Intent accepts an executable path, arguments, optional stdin/workdir/background flag and a one-shot `PendingIntent` for the result (version-dependent). Its returned stdout/stderr can be truncated; a foreground transcript is returned at session end. Start with a noninteractive, single-command prototype and show an explicit "Open in Termux" handoff for interactive sessions. Do not promise real-time in-app streaming or reliable cancellation through this Intent. Android background launch and battery behavior require device testing. If the real-time experience is central, prioritize the embedded runtime spike before expanding the Termux bridge.

## Authority and data boundary

- A terminal command has broader effects than an ordinary built-in tool. The existing `ALLOW / DENY / ASK / REQUIRES_USER_INTERACTION` decision must be applied to a **specific** command request. Imported skills and WebView page content never acquire terminal execution by being present in context.
- Default to command review. Automatic execution is a deliberate session choice with a visible scope and Stop control; it must not be represented as confined to the selected folder unless that restriction is enforced by a real isolation boundary. A working directory and a prompt instruction are not isolation.
- Show a command's exact invocation, exit status, truncated-output indicator, and changed-file summary. Bound the number of agent turns, execution time, input/output bytes, and retained history. Terminal output and repository files are untrusted model input.
- Keep shell history, transcripts, and backups free of Aistee API keys by default. Do not mount or copy private chat/WebView storage into the runtime. User-selected document URIs need an explicit import/export or file-descriptor bridge; Android app-private paths are not shared between Aistee and Termux.
- Evaluate automatic execution only after testing the runtime's effective filesystem, network, and secret access. If commands run under Aistee's app UID, assume they can reach the app's private data until proven otherwise.

## Small implementation slices

1. **Android feasibility:** on a real device, verify Termux discovery, permission onboarding, one noninteractive `RUN_COMMAND` call, result/error delivery, truncation and behavior when Termux is absent or stopped. Keep this behind an opt-in developer/experimental setting. No model-driven commands in this slice.
2. **Agent session:** add a typed terminal tool to the shared registry and provider-specific native tool adapters where tool calls are already verified. Show provider/model and command approval receipts; implement a bounded loop, Stop, and a persisted result summary. Keep this distinct from the existing async provider Jobs and from active imported skills.
3. **Embedded feasibility:** prove live PTY output, interactive input, cancellation, background/foreground handling and isolation on supported Android versions/ABIs. Add a bundled userland only if the basic process runner and app size justify it. Static inspection of the supplied Mobile Harness APK found an arm64 runtime archive and PRoot library, but does not establish a secure or portable implementation.
4. **Artemis testing:** from a development host, script the user journey (open Terminal agent, choose model/workspace, approve or reject a command, observe output, stop), collect screenshots/logcat, and assert UI states. Run on a connected test device or emulator when available; avoid installing a host Python/ADB/scrcpy stack in every docs or ordinary build job.

## Sources and verification

- [Google Artemis](https://github.com/google/artemis): host-side CLI/MCP, ADB-connected device workflow, and optional Android accessibility helper.
- [Termux RUN_COMMAND Intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent): permission/onboarding, command and result extras, output limits, and privacy implications.
- Existing Aistee boundaries: [provider runtime](provider-runtime.md), [product ideas](product-ideas.md), and the built-in tool capability registry.

This is a design proposal, not a shipped feature. Termux integration and embedded execution require Android device validation before product promises.
