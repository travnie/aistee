# Android performance

Aistee keeps Android performance tests in the `:benchmark` module.

- `BaselineProfileGenerator` captures startup, the account-backed Web provider-switch journey and native chat journeys (opening the conversation list, then reopening and scrolling the local long-chat fixture, imported once).
- `StartupBenchmark` measures cold and warm startup.
- `ProviderSwitchBenchmark` measures frame timing for a warmed account-backed Web provider switch, with both provider WebViews prewarmed before each sample.
- `NativeChatBenchmark` measures native list-detail round trips, reopening a long conversation from the list and long-conversation scrolling. The long-chat fixture is imported as canonical local Aistee Markdown, so benchmark setup never calls provider APIs or consumes stored API keys.
- GitHub CI runs Macrobenchmark in dry-run mode only to verify that release/profileable journeys stay executable.
- Treat performance numbers from CI emulators as non-authoritative; compare real metrics on a physical device using the benchmark release variant.

## Before opening an Android PR

Regenerate Baseline Profiles when changing startup, covered Web provider switching, native chat list/detail/scroll journeys, the profile generator, or release/R8 configuration. Documentation-only and unrelated changes do not need this step.

With a connected API 33+ device or emulator (CI uses API 36), run from the repository root:

```bash
./gradlew :app:generateBaselineProfile \
  -Pandroidx.baselineprofile.forceonlyconnecteddevices \
  -Pandroid.testInstrumentationRunnerArguments.class=ais.tee.benchmark.BaselineProfileGenerator \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile \
  --stacktrace
```

Inspect `git diff -- app/src/release/generated/baselineProfiles/`. Commit generated `baseline-prof.txt` and/or `startup-prof.txt` with the feature when stable Aistee-owned rule coverage expands. Do not blindly replace committed profiles with every emulator-specific difference: CI checks newly missing stable `Lais/tee/` method rules, ignoring hotness prefixes and unstable generated lambdas.

If benchmark journeys themselves changed, also run the Macrobenchmark dry-run command maintained in `.github/workflows/performance-ci.yml` before opening the PR.

Without an emulator, mark profile regeneration as unverified in the PR. Use the CI-uploaded `aistee-baseline-profile` artifact to investigate mismatches, commit any required profiles, and do not merge while profile coverage is failing. `assembleRelease` and unit tests do not regenerate these files.

Generate the shipping profile with `:app:generateBaselineProfile`. The generated profile under `app/src/**/generated/baselineProfiles/` is the release source of truth and should be regenerated when startup or covered critical journeys materially change.

CI regenerates the profiles and compares canonical Aistee-owned (Lais/tee/) rule coverage. It ignores H/S/P hotness prefixes and framework-owned rules because emulator collection can vary those between otherwise equivalent runs.
