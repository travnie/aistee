"""Skip the Android 17 OS smoke only for clearly unrelated PR changes."""

import os
from pathlib import PurePosixPath
import subprocess

UNRELATED_PREFIXES = (
    "benchmark/",
    "app/src/test/",
    "app/src/testDebug/",
    "app/src/main/java/ais/tee/ui/screens/Web",
    "app/src/main/java/ais/tee/data/preferences/WebChat",
)
UNRELATED_FILES = {
    "app/src/main/java/ais/tee/ui/screens/ProviderBrowserTab.kt",
    "app/src/main/java/ais/tee/ui/screens/ProviderDiagnosticsDialog.kt",
}


def required(paths):
    # AppFunctions depends on application startup, debug manifest/service,
    # capability policy, preferences, lock, tokenizer and shared code.
    # Unknown paths always run the test.
    for path in paths:
        if PurePosixPath(path).suffix.lower() in {".md", ".mdx"}:
            continue
        if path.startswith(UNRELATED_PREFIXES) or path in UNRELATED_FILES:
            continue
        return True
    return False


def plan(event, base_sha):
    if event != "pull_request":
        return True
    for base in ("HEAD^1", base_sha):
        if not base:
            continue
        try:
            result = subprocess.run(
                ["git", "diff", "--name-only", "--no-renames", "-z", base, "HEAD"],
                check=True, capture_output=True,
            )
            return required(os.fsdecode(p) for p in result.stdout.split(b"\0") if p)
        except subprocess.CalledProcessError:
            continue
    return True


if __name__ == "__main__":
    print("required=" + str(plan(os.getenv("EVENT_NAME"), os.getenv("BASE_SHA"))).lower())
