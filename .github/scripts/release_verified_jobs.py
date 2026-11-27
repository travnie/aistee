"""Resolve required Android job conclusions across partial CI retries."""

import json
import subprocess
import sys

REQUIRED = {"build", "android-smoke", "appfunctions-smoke"}


def verified(attempt, fetch):
    latest = {}
    for number in range(attempt, 0, -1):
        for job in fetch(number):
            name = job["name"]
            if name in REQUIRED and name not in latest:
                latest[name] = job["conclusion"]
        if REQUIRED <= latest.keys():
            break
    return all(latest.get(name) == "success" for name in REQUIRED)


def main():
    repo, run_id, attempt = sys.argv[1:]

    def fetch(number):
        endpoint = f"repos/{repo}/actions/runs/{run_id}/attempts/{number}/jobs?per_page=100"
        pages = json.loads(subprocess.check_output(
            ["gh", "api", "--paginate", "--slurp", endpoint], text=True
        ))
        return [job for page in pages for job in page["jobs"]]

    print("true" if verified(int(attempt), fetch) else "false")


if __name__ == "__main__":
    main()
