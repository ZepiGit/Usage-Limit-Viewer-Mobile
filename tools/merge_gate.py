#!/usr/bin/env python3
"""Pass a pull request's head commit only when every other workflow run on it has passed.

The Android and iOS workflows filter on paths and the status icon evidence runs on push only, so
none of their checks can be required by branch protection. The gate is the one required check: it
always runs, waits for the runs GitHub started for the commit, under any event, and judges them.

Usage: merge_gate.py <full commit SHA>
Environment: GITHUB_REPOSITORY, GITHUB_TOKEN (actions: read), optional GITHUB_API_URL and
GITHUB_STEP_SUMMARY.
Exit status: 0 passed, 1 failed or gave up, 2 bad usage.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

# The gate's own runs are never judged (earlier attempts, or the run of a force-pushed head).
GATE_WORKFLOW = ".github/workflows/gate.yml"

# Runs started by one push appear seconds apart. The gate decides only after the set of runs has
# been the same on STABLE_POLLS consecutive polls and MIN_SETTLE seconds have passed. A run the
# commit's push started before the pull request existed is listed from the first poll; MIN_SETTLE
# covers the pull_request runs GitHub creates alongside the gate's own. One request per poll keeps
# the 120 minutes at 240 requests, far below the job token's hourly limit.
INTERVAL = 30
STABLE_POLLS = 3
MIN_SETTLE = 120
# The longest chain is Android's: Debug APK (30 min limit), then the API 37 smoke (60 min limit,
# about 50 in practice), so 90 minutes of job limits plus 30 for runner queueing. The job's own
# timeout-minutes in gate.yml sits above this so the gate always gets to say what it waited for.
DEADLINE = 120 * 60

PASSING = {"success", "skipped", "neutral"}
FAILING = {"failure", "timed_out", "startup_failure", "action_required"}
FULL_SHA = re.compile(r"[0-9a-f]{40}")


class Transient(Exception):
    """The listing could not be trusted this time; the next poll tries again."""


class ApiError(Exception):
    """GitHub refused the request in a way another poll will not fix."""


class GaveUp(Exception):
    def __init__(self, message, runs):
        super().__init__(message)
        self.runs = runs


def label(run):
    return f"{run['name']} ({run['event']}) run {run['id']}"


def finished(run):
    return run["status"] == "completed" and run["conclusion"] is not None


def state(run):
    if finished(run):
        return run["conclusion"]
    return "completed without a conclusion" if run["status"] == "completed" else run["status"]


def verdict(runs):
    """(passed, reason) for the finished runs on one commit, the gate's own excluded."""
    if not runs:
        return True, "no other workflow ran on this commit"
    succeeded = {run["workflow_id"] for run in runs if run["conclusion"] == "success"}
    problems = []
    for run in runs:
        conclusion = run["conclusion"]
        if conclusion in PASSING:
            continue
        if conclusion == "cancelled":
            if run["workflow_id"] not in succeeded:
                problems.append(f"{label(run)} was cancelled and no run of {run['name']} on this commit succeeded")
        elif conclusion in FAILING:
            problems.append(f"{label(run)} concluded {conclusion}")
        else:
            problems.append(f"{label(run)} has unknown conclusion {conclusion!r}")
    if problems:
        return False, "; ".join(problems)
    return True, f"all {len(runs)} runs on this commit passed"


def parse_next_link(header):
    for part in (header or "").split(","):
        match = re.search(r'<([^>]+)>\s*;\s*rel="next"', part)
        if match:
            return match.group(1)
    return None


def list_runs(fetch, repo, sha, api="https://api.github.com"):
    """Every workflow run on the commit except the gate's own, following all pages."""
    url = f"{api}/repos/{repo}/actions/runs?head_sha={sha}&per_page=100"
    runs, totals = {}, set()
    while url:
        body, url = fetch(url)
        totals.add(body["total_count"])
        for run in body["workflow_runs"]:
            runs[run["id"]] = run
    if totals != {len(runs)}:
        raise Transient(f"listing gave {len(runs)} runs against a total of {sorted(totals)}")
    return [run for run in runs.values() if run["path"] != GATE_WORKFLOW]


def http_fetch(token):
    def fetch(url):
        request = urllib.request.Request(url, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response), parse_next_link(response.headers.get("Link"))
        except urllib.error.HTTPError as error:
            limited = error.code == 403 and error.headers.get("X-RateLimit-Remaining") == "0"
            if error.code >= 500 or error.code == 429 or limited:
                raise Transient(f"HTTP {error.code} for {url}") from error
            raise ApiError(f"HTTP {error.code} for {url}: {error.read()[:300].decode(errors='replace')}") from error
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise Transient(f"{error} for {url}") from error
    return fetch


def wait_for_runs(list_now, clock, sleep, log):
    """The runs once they have settled and all finished; GaveUp at the deadline."""
    start = clock()
    previous, streak, runs, last_line = None, 0, [], None
    while True:
        elapsed = clock() - start
        try:
            runs = list_now()
        except Transient as error:
            previous, streak = None, 0
            line = f"listing failed, trying again: {error}"
        else:
            ids = frozenset(run["id"] for run in runs)
            streak = streak + 1 if ids == previous else 1
            previous = ids
            busy = [run for run in runs if not finished(run)]
            if streak >= STABLE_POLLS and elapsed >= MIN_SETTLE and not busy:
                return runs
            waiting = "; ".join(f"{label(run)} {state(run)}" for run in busy) or "the set of runs to settle"
            line = f"{len(runs)} runs, waiting for {waiting}"
        if line != last_line:
            log(f"t+{int(elapsed)}s: {line}")
            last_line = line
        if elapsed >= DEADLINE:
            busy = [run for run in runs if not finished(run)]
            what = "; ".join(f"{label(run)} {state(run)} {run['html_url']}" for run in busy)
            raise GaveUp(f"gave up after {DEADLINE // 60} minutes waiting for "
                         f"{what or 'the set of runs to settle (' + line + ')'}", runs)
        sleep(INTERVAL)


def table(runs):
    lines = ["| Workflow | Event | Run | Conclusion | Link |", "| --- | --- | --- | --- | --- |"]
    for run in sorted(runs, key=lambda run: (run["name"], run["id"])):
        lines.append(f"| {run['name']} | {run['event']} | {run['id']} | {state(run)} | {run['html_url']} |")
    return "\n".join(lines)


def main(argv, env=os.environ, fetch=None, clock=time.monotonic, sleep=time.sleep, out=sys.stdout):
    def say(text):
        print(text, file=out, flush=True)

    if len(argv) != 1 or not FULL_SHA.fullmatch(argv[0]):
        say("usage: merge_gate.py <sha>; the gate expects the full 40-character commit SHA")
        return 2
    sha, repo = argv[0], env["GITHUB_REPOSITORY"]
    fetch = fetch or http_fetch(env["GITHUB_TOKEN"])
    api = env.get("GITHUB_API_URL", "https://api.github.com")
    say(f"Merge gate for {repo} at {sha}")
    try:
        runs = wait_for_runs(lambda: list_runs(fetch, repo, sha, api), clock, sleep, say)
        passed, reason = verdict(runs)
    except GaveUp as error:
        runs, passed, reason = error.runs, False, str(error)
    except ApiError as error:
        runs, passed, reason = [], False, str(error)
    report = f"{table(runs)}\n\nMerge gate {'passed' if passed else 'failed'}: {reason}"
    say(report)
    if summary := env.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as file:
            file.write(f"## Merge gate for {sha[:7]}\n\n{report}\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
