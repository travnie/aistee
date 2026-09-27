# Android performance

Aistee keeps Android performance tests in the `:benchmark` module.

- `BaselineProfileGenerator` captures startup, the account-backed Web provider-switch journey and native chat journeys (opening the conversation list, then reopening and scrolling the local long-chat fixture, imported once).
- `StartupBenchmark` measures cold and warm startup.
- `ProviderSwitchBenchmark` measures frame timing for a warmed account-backed Web provider switch, with both provider WebViews prewarmed before each sample.
- `NativeChatBenchmark` measures native list-detail round trips, reopening a long conversation from the list and long-conversation scrolling. The long-chat fixture is imported as canonical local Aistee Markdown, so benchmark setup never calls provider APIs or consumes stored API keys.
- GitHub CI runs Macrobenchmark in dry-run mode only to verify that release/profileable journeys stay executable.
- Treat performance numbers from CI emulators as non-authoritative; compare real metrics on a physical device using the benchmark release variant.

Generate the shipping profile with `:app:generateBaselineProfile`. The generated profile under `app/src/**/generated/baselineProfiles/` is the release source of truth and should be regenerated when startup or covered critical journeys materially change.

CI regenerates the profiles and compares canonical Aistee-owned (Lais/tee/) rule coverage. It ignores H/S/P hotness prefixes and framework-owned rules because emulator collection can vary those between otherwise equivalent runs.
