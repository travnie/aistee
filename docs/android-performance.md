# Android performance

Aistee keeps Android performance tests in the `:benchmark` module.

- `BaselineProfileGenerator` captures startup plus the account-backed Web provider-switch journey.
- `StartupBenchmark` measures cold and warm startup.
- `ProviderSwitchBenchmark` measures frame timing while switching account-backed Web providers.
- GitHub CI runs Macrobenchmark in dry-run mode only to verify that release/profileable journeys stay executable.
- Treat performance numbers from CI emulators as non-authoritative; compare real metrics on a physical device using the benchmark release variant.

Generate the shipping profile with `:app:generateBaselineProfile`. The generated profile under `app/src/**/generated/baselineProfiles/` is the release source of truth and should be regenerated when startup or covered critical journeys materially change.
