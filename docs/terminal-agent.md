# Terminal agent in Aistee: proposal

## User flow

From **Studio Tools → Terminal agent**, choose a native/API provider and a verified model, select a project workspace, write a task, and start a session. The screen shows the prompt, the selected model, the commands the model proposes, terminal output, changed files, and a Stop action. Settings include a per-session command policy (review each command or explicitly authorize automatic execution), a run limit, and an output limit. A session can save its transcript or selected artifacts to Project Library; ordinary chat remains unchanged.

A model response must be a structured tool call with command, arguments, working directory, and purpose. Aistee executes it through one Android terminal adapter, returns the bounded result to the same provider/model turn, and repeats within the run limit. User text and model output are never interpolated into a shell string by the app. Shell expressions requested by a model remain executable commands and must be displayed verbatim before approval when review is enabled. The UI labels the provider, model, execution mode, workspace, and every command receipt.

This belongs to Aistee's **native/API** path: existing `shared` provider/model selection, `AiChatService` transport, and the built-in tool registry can supply a typed agent loop. Account-backed WebViews do not expose a supported third-party tool-call contract or their provider credentials. Do not extract cookies or silently pass Aistee API keys into a shell or another agent CLI. A CLI such as Claude Code, Codex, or Gemini CLI would be a separately configured runtime with its own authentication and model inventory; choosing a model in Aistee does not automatically configure that CLI.

## Runtime direction: built into Aistee

[Xed-Editor](https://github.com/Xed-Editor/Xed-Editor) demonstrates the target shape on Android: an in-app terminal backed by an app-managed Ubuntu root filesystem and PRoot, plus optional language servers started as processes. Its terminal API exposes separate visible sessions and background commands. The first Aistee prototype should likewise display live output and accept input **inside Aistee**, starting with a small Android shell and a real process/PTY bridge. Add a packaged or installed Linux userland only when actual agent CLI dependencies or developer tools require it. Xed is an architecture reference, not an SDK that Aistee can call across apps or source code to copy without reviewing its GPL-3.0 licensing and dependencies.

| Component | First proof | Later capability |
| --- | --- | --- |
| Terminal UI and session manager | In-app terminal pane with live output, input, resize, Stop, exit status and bounded scrollback; close processes when sessions end. | Multiple persistent tabs, keyboard shortcuts and background session recovery. |
| Android execution | Spawn a command with an argument array, explicit working directory and per-session limits; validate start/stop on the user's device. | PTY-backed interactive tools, process groups and robust Android foreground lifecycle. |
| Linux userland | Evaluate only after the shell proof: reproducible rootfs install/update, ABI compatibility, footprint, executable loading and extraction integrity. | Git, language runtimes and independently configured agent CLIs; PRoot maps paths but does not isolate Aistee secrets. |
| Language servers | Defer until there is an editable code workspace; decide whether diagnostics/completion are useful to the agent/editor. | Start LSP processes via stdio or socket per project, with install/update controls, resource limits and cleanup. |
| Optional external Termux | A separate opt-in compatibility bridge for users who already manage a Termux environment. | Noninteractive `RUN_COMMAND` dispatch and a completion result; it cannot replace the in-app live terminal. |
| Artemis | Run device UI smoke tests from a development host via ADB. | Test agent commands and terminal lifecycle on real devices. |

Xed's published configuration binds a wide range of host paths into its Ubuntu guest, including `/data`. Aistee must design its own narrower data boundary and test what the actual process UID can read; neither a PRoot mapping nor a chosen working directory is a security boundary. Xed's extension terminal API is internal to Xed; integration into Aistee calls for an Aistee-owned runtime.

The optional Termux `RUN_COMMAND` Intent needs its Android permission and `allow-external-apps=true`. It returns a bounded completion result via `PendingIntent`, not an in-app live stream, and Android background launch behavior needs device testing.

## Authority and data boundary

- A terminal command has broader effects than an ordinary built-in tool. The existing `ALLOW / DENY / ASK / REQUIRES_USER_INTERACTION` decision must be applied to a **specific** command request. Imported skills and WebView page content never acquire terminal execution by being present in context.
- Default to command review. Automatic execution is a deliberate session choice with a visible scope and Stop control; it must not be represented as confined to the selected folder unless that restriction is enforced by a real isolation boundary. A working directory and a prompt instruction are not isolation.
- Show a command's exact invocation, exit status, truncated-output indicator, and changed-file summary. Bound the number of agent turns, execution time, input/output bytes, and retained history. Terminal output and repository files are untrusted model input.
- Keep shell history, transcripts, and backups free of Aistee API keys by default. Do not mount or copy private chat/WebView storage into the runtime. User-selected document URIs need an explicit import/export or file-descriptor bridge; Android app-private paths are not shared between Aistee and Termux.
- Evaluate automatic execution only after testing the runtime's effective filesystem, network, and secret access. If commands run under Aistee's app UID, assume they can reach the app's private data until proven otherwise.

## Small implementation slices

1. **In-app shell proof:** add an experimental terminal pane and session controller; start a harmless native command, stream output while it runs, accept input, resize and Stop on a real device. Validate process termination and measure which private Aistee paths the process can reach. No model-driven commands in this slice.
2. **Agent session:** add a typed terminal tool to the shared registry and provider-specific native tool adapters where tool calls are already verified. Show provider/model and command approval receipts; implement a bounded loop, Stop, and a persisted result summary. Keep this distinct from the existing async provider Jobs and from active imported skills.
3. **Linux/PTY expansion:** prove interactive PTY behavior, lifecycle and resource limits on supported Android versions/ABIs. If required for usable CLI agents, prototype reproducible rootfs installation and PRoot, measure APK/storage cost, and review license/dependency obligations. Static inspection of the supplied Mobile Harness APK found an arm64 runtime archive and PRoot library, but does not establish secure or portable behavior.
4. **Editor/LSP and optional Termux:** add language servers only alongside actual code editing and diagnostics. Evaluate Termux dispatch only as a user-selected fallback; its result callback does not provide the main terminal UI.
5. **Artemis testing:** from a development host, script the user journey (open Terminal agent, choose model/workspace, approve or reject a command, observe live output, stop), collect screenshots/logcat, and assert UI states. Run on a connected test device or emulator when available; avoid installing a host Python/ADB/scrcpy stack in every docs or ordinary build job.

## Sources and verification

- [Google Artemis](https://github.com/google/artemis): host-side CLI/MCP, ADB-connected device workflow, and optional Android accessibility helper.
- [Xed terminal architecture](https://xed-editor.github.io/Xed-Docs/docs/terminal/advanced.html), [terminal process API](https://xed-editor.github.io/Xed-Docs/docs/extensions/general/terminal.html) and [LSP process connections](https://xed-editor.github.io/Xed-Docs/docs/extensions/general/lsp-server.html): reference for a built-in terminal, PRoot and optional editor tooling.
- [Termux RUN_COMMAND Intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent): optional external-app bridge and its completion-only result.
- Existing Aistee boundaries: [provider runtime](provider-runtime.md), [product ideas](product-ideas.md), and the built-in tool capability registry.

This is a design proposal, not a shipped feature. The in-app process/PTY experiment requires Android device validation before product promises.
