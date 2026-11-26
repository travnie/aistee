import subprocess
import unittest
from unittest.mock import patch

from appfunctions_scope import plan, required


class ScopeTests(unittest.TestCase):
    def test_unrelated(self):
        self.assertFalse(required([
            "app/src/main/java/ais/tee/ui/screens/WebChatScreen.kt",
            "app/src/main/java/ais/tee/data/preferences/WebChatBookmarkStore.kt",
            "app/src/test/java/ais/tee/WebChatTest.kt",
            "benchmark/src/main/Benchmark.kt",
            "README.md",
        ]))

    def test_sensitive_and_unknown(self):
        for path in (
            "app/src/debug/AndroidManifest.xml",
            "app/src/debug/java/ais/tee/data/appfunctions/SystemAssistantAccess.kt",
            "app/src/main/java/ais/tee/data/engine/SystemAssistantTokenCountAction.kt",
            "app/src/main/java/ais/tee/data/preferences/BuiltInBenchPreferencesStore.kt",
            "app/src/main/java/ais/tee/security/AppLockStore.kt",
            "app/src/main/java/ais/tee/ui/screens/PrivacySettingsPanel.kt",
            "app/src/androidTest/java/ais/tee/SystemAssistantInvocationTest.kt",
            "app/src/main/java/ais/tee/AisteeApplication.kt",
            "shared/src/commonMain/A.kt",
            "gradle/libs.versions.toml",
            ".github/actions/run-android-emulator/action.yml",
            ".github/workflows/android-ci.yml",
            ".github/scripts/appfunctions_scope.py",
            "unknown.config",
        ):
            with self.subTest(path=path):
                self.assertTrue(required([path, "README.md"]))

    def test_full_and_failed_diff(self):
        self.assertTrue(plan("push", ""))
        with patch("appfunctions_scope.subprocess.run", side_effect=subprocess.CalledProcessError(1, "git")):
            self.assertTrue(plan("pull_request", "base"))


if __name__ == "__main__":
    unittest.main()
