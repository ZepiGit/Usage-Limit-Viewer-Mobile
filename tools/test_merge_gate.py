"""Merge gate verdict, run listing and waiting: python3 -m unittest discover -s tools -p 'test_merge_gate.py'

No network and no sleeping: the gate gets a fake fetch and a fake clock whose sleep only moves time.
"""

import io
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import merge_gate
from merge_gate import GaveUp, Transient, list_runs, main, parse_next_link, verdict, wait_for_runs

SHA = "80e06e95d9ad9dada6c479e006b23f6dd1b92024"
REPO = "ZepiGit/Usage-Limit-Viewer-Mobile"
WORKFLOWS = {
    "Android": (1, ".github/workflows/android.yml"),
    "iOS": (2, ".github/workflows/ios.yml"),
    "Status icon device evidence": (3, ".github/workflows/status-icons.yml"),
    "Merge gate": (9, ".github/workflows/gate.yml"),
    "Marketing screenshots": (4, ".github/workflows/screenshots.yml"),
    "Dependabot Updates": (5, "dynamic/dependabot/dependabot-updates"),
    "Release": (6, ".github/workflows/release.yml"),
    "Lint": (7, ".github/workflows/lint.yml"),
}


def run(run_id, workflow="Android", event="pull_request", status="completed", conclusion="success"):
    workflow_id, path = WORKFLOWS[workflow]
    return {
        "id": run_id, "name": workflow, "workflow_id": workflow_id, "path": path, "event": event,
        "status": status, "conclusion": conclusion,
        "html_url": f"https://github.com/{REPO}/actions/runs/{run_id}",
    }


class Clock:
    def __init__(self):
        self.now = 0.0
        self.slept = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds


def timeline(clock, *steps):
    """A list_runs whose answer is the last step whose start time has passed: (start, runs or exception)."""
    def list_now():
        answer = [runs for start, runs in steps if start <= clock.now][-1]
        if isinstance(answer, Exception):
            raise answer
        return answer
    return list_now


def wait(clock, list_now):
    return wait_for_runs(list_now, clock, clock.sleep, log=lambda line: None)


class VerdictTest(unittest.TestCase):
    def test_success_skipped_and_neutral_pass(self):
        for conclusion in ("success", "skipped", "neutral"):
            with self.subTest(conclusion):
                passed, reason = verdict([run(1, conclusion=conclusion), run(2, "iOS")])
                self.assertTrue(passed, reason)

    def test_failure_timed_out_startup_failure_and_action_required_fail(self):
        for conclusion in ("failure", "timed_out", "startup_failure", "action_required"):
            with self.subTest(conclusion):
                passed, reason = verdict([run(1, conclusion=conclusion), run(2, "iOS")])
                self.assertFalse(passed)
                self.assertIn(f"Android (pull_request) run 1 concluded {conclusion}", reason)

    def test_a_failure_is_not_excused_by_a_passing_run_of_the_same_workflow(self):
        passed, _ = verdict([run(1, event="push", conclusion="failure"), run(2)])
        self.assertFalse(passed)

    def test_a_cancelled_run_passes_when_the_same_workflow_succeeded_on_the_commit(self):
        passed, reason = verdict([run(1, event="push", conclusion="cancelled"), run(2), run(3, "iOS")])
        self.assertTrue(passed, reason)

    def test_a_cancelled_run_without_a_successful_sibling_fails(self):
        cases = {
            "alone": [run(1, conclusion="cancelled")],
            "sibling also cancelled": [run(1, event="push", conclusion="cancelled"), run(2, conclusion="cancelled")],
            "sibling only skipped": [run(1, event="push", conclusion="cancelled"), run(2, conclusion="skipped")],
            "success only in another workflow": [run(1, conclusion="cancelled"), run(2, "iOS")],
        }
        for name, runs in cases.items():
            with self.subTest(name):
                passed, reason = verdict(runs)
                self.assertFalse(passed)
                self.assertIn("Android (", reason)
                self.assertIn("cancelled", reason)

    def test_no_other_run_passes(self):
        for runs in ([], [run(9, "Merge gate", status="in_progress", conclusion=None)]):
            with self.subTest(runs=len(runs)):
                passed, reason = verdict(runs)
                self.assertTrue(passed)
                self.assertEqual(reason, "no other workflow run was visible for this commit")

    def test_failed_manual_dependabot_and_release_runs_do_not_block_passing_ci(self):
        others = {
            "workflow_dispatch": run(21, "Marketing screenshots", event="workflow_dispatch", conclusion="failure"),
            "dynamic": run(22, "Dependabot Updates", event="dynamic", conclusion="failure"),
            "release push": run(23, "Release", event="push", conclusion="failure"),
        }
        for name, other in others.items():
            with self.subTest(name):
                passed, reason = verdict([run(1), run(2, "iOS"), other])
                self.assertTrue(passed, reason)
                self.assertEqual(reason, "all 2 judged runs visible for this commit passed")

    def test_a_workflow_the_gate_has_never_heard_of_is_judged(self):
        passed, reason = verdict([run(1), run(2, "Lint", conclusion="failure")])
        self.assertFalse(passed)
        self.assertIn("Lint (pull_request) run 2 concluded failure", reason)

    def test_an_unknown_or_missing_conclusion_fails_closed(self):
        for conclusion in ("stale", "something_new", None):
            with self.subTest(conclusion):
                passed, reason = verdict([run(1, conclusion=conclusion), run(2, "iOS")])
                self.assertFalse(passed)
                self.assertIn(f"unknown conclusion {conclusion!r}", reason)

    def test_every_failing_run_is_named(self):
        _, reason = verdict([run(1, conclusion="failure"), run(2, "iOS", conclusion="timed_out")])
        self.assertIn("run 1", reason)
        self.assertIn("run 2", reason)


class ListRunsTest(unittest.TestCase):
    def pages(self, *bodies):
        urls = [f"https://api.example/page{n}" for n in range(len(bodies))]
        served = dict(zip(urls, bodies))
        requested = []

        def fetch(url):
            requested.append(url)
            key = urls[0] if url.startswith("https://api.example/repos/") else url
            next_url = urls[urls.index(key) + 1] if urls.index(key) + 1 < len(urls) else None
            return served[key], next_url
        return fetch, requested

    def test_follows_every_page(self):
        fetch, requested = self.pages(
            {"total_count": 4, "workflow_runs": [run(1), run(2, "Merge gate")]},
            {"total_count": 4, "workflow_runs": [run(3, "iOS"), run(4, "Status icon device evidence", event="push")]},
        )
        runs = list_runs(fetch, REPO, SHA, api="https://api.example")
        self.assertEqual([r["id"] for r in runs], [1, 2, 3, 4])
        self.assertEqual(len(requested), 2)
        self.assertIn(f"head_sha={SHA}", requested[0])
        self.assertIn("per_page=100", requested[0])

    def test_a_listing_that_does_not_add_up_is_transient(self):
        # A run created between two page requests shifts the pages; the next poll lists it whole.
        fetch, _ = self.pages(
            {"total_count": 3, "workflow_runs": [run(1), run(2, "iOS")]},
            {"total_count": 3, "workflow_runs": [run(2, "iOS")]},
        )
        with self.assertRaises(Transient):
            list_runs(fetch, REPO, SHA, api="https://api.example")

    def test_reads_the_next_link(self):
        header = ('<https://api.github.com/repositories/1/actions/runs?page=2>; rel="next", '
                  '<https://api.github.com/repositories/1/actions/runs?page=5>; rel="last"')
        self.assertEqual(parse_next_link(header), "https://api.github.com/repositories/1/actions/runs?page=2")
        self.assertIsNone(parse_next_link('<https://api.github.com/x?page=1>; rel="prev"'))
        self.assertIsNone(parse_next_link(None))


class WaitTest(unittest.TestCase):
    def test_does_not_decide_before_the_minimum_time_even_when_everything_is_done(self):
        clock = Clock()
        wait(clock, timeline(clock, (0, [run(1)])))
        self.assertGreaterEqual(clock.now, merge_gate.MIN_SETTLE)

    def test_a_run_that_appears_late_resets_the_settling_and_is_judged(self):
        clock = Clock()
        late = merge_gate.MIN_SETTLE - 1
        runs = wait(clock, timeline(clock, (0, [run(1)]), (late, [run(1), run(2, "iOS", conclusion="failure")])))
        self.assertEqual([r["id"] for r in runs], [1, 2])
        # The new set must itself be seen on enough consecutive polls.
        first_poll_with_both = min(t for t in range(0, 10_000, merge_gate.INTERVAL) if t >= late)
        self.assertGreaterEqual(clock.now, first_poll_with_both + (merge_gate.STABLE_POLLS - 1) * merge_gate.INTERVAL)
        self.assertFalse(verdict(runs)[0])

    def test_a_run_replaced_by_another_of_the_same_count_is_judged_only_after_three_sightings(self):
        clock = Clock()
        swap = merge_gate.MIN_SETTLE
        runs = wait(clock, timeline(clock, (0, [run(1), run(2, "iOS")]),
                                    (swap, [run(1), run(3, "iOS", conclusion="failure")])))
        self.assertEqual([r["id"] for r in runs], [1, 3])
        self.assertEqual(clock.now, swap + (merge_gate.STABLE_POLLS - 1) * merge_gate.INTERVAL)

    def test_runs_the_gate_does_not_judge_neither_delay_nor_unsettle_it(self):
        clock = Clock()
        manual = run(21, "Marketing screenshots", event="workflow_dispatch", status="in_progress", conclusion=None)
        own = run(22, "Merge gate", status="in_progress", conclusion=None)
        runs = wait(clock, timeline(clock, (0, [run(1), own]), (merge_gate.MIN_SETTLE - 1, [run(1), own, manual])))
        self.assertEqual(clock.now, merge_gate.MIN_SETTLE)
        self.assertEqual([r["id"] for r in runs], [1, 22, 21])
        self.assertTrue(verdict(runs)[0])

    def test_waits_while_any_run_is_queued_in_progress_waiting_requested_or_pending(self):
        for status in ("queued", "in_progress", "waiting", "requested", "pending"):
            with self.subTest(status):
                clock = Clock()
                runs = wait(clock, timeline(clock, (0, [run(1), run(2, "iOS", status=status, conclusion=None)]),
                                            (600, [run(1), run(2, "iOS")])))
                self.assertGreaterEqual(clock.now, 600)
                self.assertTrue(verdict(runs)[0])

    def test_a_completed_run_without_a_conclusion_is_waited_for(self):
        clock = Clock()
        runs = wait(clock, timeline(clock, (0, [run(1, conclusion=None)]), (300, [run(1, conclusion="failure")])))
        self.assertGreaterEqual(clock.now, 300)
        self.assertEqual(runs[0]["conclusion"], "failure")

    def test_gives_up_at_the_deadline_and_names_the_run_it_waited_for(self):
        clock = Clock()
        stuck = run(7, "Android", status="in_progress", conclusion=None)
        with self.assertRaises(GaveUp) as caught:
            wait(clock, timeline(clock, (0, [run(1, "iOS"), stuck])))
        self.assertGreaterEqual(clock.now, merge_gate.DEADLINE)
        self.assertLess(clock.now, merge_gate.DEADLINE + merge_gate.INTERVAL)
        self.assertIn("Android (pull_request) run 7 in_progress", str(caught.exception))
        self.assertIn(stuck["html_url"], str(caught.exception))
        self.assertEqual(caught.exception.runs, [run(1, "iOS"), stuck])

    def test_slow_requests_do_not_carry_the_gate_past_its_deadline_by_more_than_one_request(self):
        clock = Clock()
        request = 40
        stuck = run(7, status="in_progress", conclusion=None)

        def slow():
            clock.now += request
            return [stuck]
        with self.assertRaises(GaveUp):
            wait(clock, slow)
        self.assertLessEqual(clock.now, merge_gate.DEADLINE + request)

    def test_an_api_error_breaks_the_settling_but_not_the_gate(self):
        clock = Clock()
        error_at = merge_gate.MIN_SETTLE - merge_gate.INTERVAL
        runs = wait(clock, timeline(clock, (0, [run(1)]), (error_at, Transient("HTTP 502")),
                                    (error_at + 1, [run(1)])))
        self.assertEqual([r["id"] for r in runs], [1])
        self.assertGreaterEqual(clock.now, error_at + merge_gate.STABLE_POLLS * merge_gate.INTERVAL)

    def test_no_runs_at_all_settles_and_passes(self):
        clock = Clock()
        runs = wait(clock, timeline(clock, (0, [])))
        self.assertEqual(runs, [])
        self.assertTrue(verdict(runs)[0])


class MainTest(unittest.TestCase):
    def gate(self, bodies, sha=SHA):
        clock = Clock()
        out = io.StringIO()
        with TemporaryDirectory() as tmp:
            summary = Path(tmp, "summary.md")
            env = {"GITHUB_REPOSITORY": REPO, "GITHUB_STEP_SUMMARY": str(summary)}
            code = main([sha], env=env, fetch=lambda url: (bodies(clock.now), None),
                        clock=clock, sleep=clock.sleep, out=out)
            return code, out.getvalue(), summary.read_text() if summary.exists() else ""

    def test_prints_the_judged_runs_and_the_reason_to_the_log_and_the_summary(self):
        runs = [run(11, event="push", conclusion="cancelled"), run(12), run(13, "iOS", conclusion="failure")]
        code, log, summary = self.gate(lambda now: {"total_count": 3, "workflow_runs": runs})
        self.assertEqual(code, 1)
        for text in (log, summary):
            self.assertIn("| Android | push | 11 | cancelled | https://github.com/", text)
            self.assertIn("| iOS | pull_request | 13 | failure | https://github.com/", text)
            self.assertIn("Merge gate failed: iOS (pull_request) run 13 concluded failure", text)

    def test_lists_the_runs_it_does_not_judge_with_the_reason(self):
        runs = [run(1), run(21, "Marketing screenshots", event="workflow_dispatch", conclusion="failure"),
                run(23, "Release", event="push", conclusion="failure"), run(24, "Merge gate", status="in_progress", conclusion=None)]
        code, log, summary = self.gate(lambda now: {"total_count": 4, "workflow_runs": runs})
        self.assertEqual(code, 0)
        for text in (log, summary):
            judged_part, not_judged_part = text.split("Not judged:")
            self.assertIn("| Android | pull_request | 1 | success |", judged_part)
            self.assertIn("| Marketing screenshots | workflow_dispatch | 21 | failure | https://github.com/"
                          f"{REPO}/actions/runs/21 | event workflow_dispatch |", not_judged_part)
            self.assertIn("| Release | push | 23 | failure |", not_judged_part)
            self.assertIn("| path .github/workflows/release.yml |", not_judged_part)
            self.assertIn("| Merge gate | pull_request | 24 | in_progress |", not_judged_part)
            self.assertIn("Merge gate passed: all 1 judged runs visible for this commit passed", text)

    def test_passes_with_exit_status_zero(self):
        code, log, _ = self.gate(lambda now: {"total_count": 1, "workflow_runs": [run(1)]})
        self.assertEqual(code, 0)
        self.assertIn("Merge gate passed", log)

    def test_the_deadline_fails_the_gate_with_the_last_runs_it_saw(self):
        stuck = run(5, status="queued", conclusion=None)
        code, log, summary = self.gate(lambda now: {"total_count": 1, "workflow_runs": [stuck]})
        self.assertEqual(code, 1)
        self.assertIn("| Android | pull_request | 5 | queued |", summary)
        self.assertIn("Merge gate failed: gave up after 120 minutes waiting for Android (pull_request) run 5 queued", log)

    def test_a_refused_request_fails_at_once_with_the_reason(self):
        def refuse(now):
            raise merge_gate.ApiError("HTTP 403 for https://api.example: Resource not accessible by integration")
        code, log, _ = self.gate(refuse)
        self.assertEqual(code, 1)
        self.assertIn("Merge gate failed: HTTP 403", log)

    def test_refuses_anything_but_a_full_commit_sha(self):
        code, log, _ = self.gate(lambda now: self.fail("must not call the API"), sha="80e06e9")
        self.assertEqual(code, 2)
        self.assertIn("full 40-character commit SHA", log)


if __name__ == "__main__":
    unittest.main()
