import unittest
from unittest.mock import patch
import subprocess

from codeql_matrix import ALL, matrix, plan, select


class ScopeTests(unittest.TestCase):
    def test_docs(self):
        self.assertEqual(select(["README.md", "app/README.md"]), set())

    def test_workflow(self):
        self.assertEqual(select([".github/workflows/release.yml"]), {"actions"})
        self.assertEqual(select([".github/workflows/codeql.yml"]), ALL)

    def test_unknown_and_setup(self):
        self.assertEqual(select(["unknown.config"]), ALL)
        self.assertEqual(select([".github/actions/setup/action.yml"]), ALL)

    def test_android(self):
        self.assertEqual(select(["app/src/main/A.kt"]), {"java-kotlin"})
        self.assertEqual(select(["app/src/main/A.kt", ".github/workflows/release.yml"]), {"java-kotlin", "actions"})

    def test_full_and_failed_diff(self):
        self.assertEqual(plan("push", ""), ALL)
        self.assertEqual(plan("schedule", ""), ALL)
        with patch("codeql_matrix.subprocess.run", side_effect=subprocess.CalledProcessError(1, "git")):
            self.assertEqual(plan("pull_request", "base"), ALL)

    def test_matrix(self):
        self.assertTrue(all(not row["required"] for row in matrix(set())))
        self.assertEqual({row["language"] for row in matrix(set())}, ALL)
        self.assertTrue(all(row["required"] for row in matrix(ALL)))
        mixed = matrix({"java-kotlin"})
        self.assertEqual({row["language"] for row in mixed if row["required"]}, {"java-kotlin"})
        self.assertEqual({row["language"] for row in mixed if not row["required"]}, ALL - {"java-kotlin"})
        self.assertEqual({row["language"] for row in matrix(ALL)}, ALL)


if __name__ == "__main__":
    unittest.main()
