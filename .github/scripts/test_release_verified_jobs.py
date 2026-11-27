import unittest

from release_verified_jobs import verified


def jobs(**conclusions):
    return [{"name": name.replace("_", "-"), "conclusion": result}
            for name, result in conclusions.items()]


class VerifiedJobsTest(unittest.TestCase):
    def test_revert_passes_partial_retry(self):
        attempts = {
            1: jobs(build="success", android_smoke="failure", appfunctions_smoke="success"),
            2: jobs(android_smoke="success"),
        }
        self.assertTrue(verified(2, attempts.__getitem__))

    def test_latest_failure_overrides_previous_success(self):
        attempts = {
            1: jobs(build="success", android_smoke="success", appfunctions_smoke="success"),
            2: jobs(android_smoke="failure"),
        }
        self.assertFalse(verified(2, attempts.__getitem__))

    def test_pending_or_missing_job_is_not_verified(self):
        self.assertFalse(verified(1, lambda _: jobs(build="success", android_smoke=None)))
        self.assertFalse(verified(1, lambda _: jobs(build="success", android_smoke="success")))

    def test_complete_attempt_does_not_read_older_attempts(self):
        def fetch(number):
            self.assertEqual(number, 3)
            return jobs(build="success", android_smoke="success", appfunctions_smoke="success")

        self.assertTrue(verified(3, fetch))


if __name__ == "__main__":
    unittest.main()
