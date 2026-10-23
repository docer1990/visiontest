import json
import unittest
from unittest.mock import patch

from benchmarks import fixtures


class FixtureReadinessTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(callable(getattr(fixtures, "wait_fixture_ready", None)),
                        "fixture setup must expose bounded observed readiness")


    def manifest(self, platform="ios"):
        return {"platform": platform, "fixtureIdentity": "dev.visiontest.benchmark",
                "cliPrefix": ["visiontest"]}

    def result(self, resource_id, found=True):
        return {"stdout": json.dumps({"found": found, "resourceId": resource_id}),
                "exit_code": 0, "timed_out": False, "stderr": ""}

    def test_transient_elements_are_not_queried_before_settled_marker(self):
        observed = []
        marker_probes = 0
        def probe(argv, timeout_ms, env):
            nonlocal marker_probes
            resource_id = argv[argv.index("--resource-id") + 1]
            observed.append(resource_id)
            if resource_id == "fixture_ready":
                marker_probes += 1
                return self.result(resource_id, marker_probes >= 3)
            self.assertGreaterEqual(marker_probes, 3, "do not snapshot transitioning elements")
            return self.result(resource_id, resource_id != "loading")
        with patch("benchmarks.fixtures.run_process", side_effect=probe), \
                patch("benchmarks.fixtures.time.sleep"):
            ok, error = fixtures.wait_fixture_ready({"fixtureScenario": "disappear", "timeoutMs": 1000},
                                                   self.manifest(), {})
        self.assertTrue(ok, error)
        self.assertEqual(["fixture_ready", "fixture_ready", "fixture_ready", "start", "loading"], observed)

    def test_retries_absent_start_before_accepting_disappearance(self):
        replies = [self.result("fixture_ready"), self.result("start", False),
                   self.result("fixture_ready"), self.result("start"), self.result("loading", False)]
        with patch("benchmarks.fixtures.run_process", side_effect=replies) as run, \
                patch("benchmarks.fixtures.time.sleep"):
            ok, error = fixtures.wait_fixture_ready({"fixtureScenario": "disappear", "timeoutMs": 1000},
                                          self.manifest(), {"VISIONTEST_TRACE_PERFORMANCE": "inherited"})
        self.assertTrue(ok, error)
        self.assertEqual(5, run.call_count)
        self.assertEqual("", run.call_args.kwargs["env"]["VISIONTEST_TRACE_PERFORMANCE"])
        self.assertIn("--bundle-id", run.call_args.args[0])

    def test_appearance_settles_done_before_measurement(self):
        replies = [self.result("fixture_ready"), self.result("start"), self.result("done", False),
                   self.result("fixture_ready"), self.result("start"), self.result("done")]
        with patch("benchmarks.fixtures.run_process", side_effect=replies) as run, \
                patch("benchmarks.fixtures.time.sleep"):
            ok, error = fixtures.wait_fixture_ready({"fixtureScenario": "appear", "timeoutMs": 1000},
                                          self.manifest(), {})
        self.assertTrue(ok, error)
        self.assertEqual(6, run.call_count)

    def test_android_navigation_uses_platform_resource_ids(self):
        identity = self.manifest()["fixtureIdentity"]
        def found(argv, timeout_ms, env):
            return self.result(argv[argv.index("--resource-id") + 1])
        with patch("benchmarks.fixtures.run_process", side_effect=found) as run:
            ok, error = fixtures.wait_fixture_ready({"fixtureScenario": "navigation", "timeoutMs": 1000},
                                          self.manifest("android"), {})
        self.assertTrue(ok, error)
        selectors = [call.args[0][call.args[0].index("--resource-id") + 1] for call in run.call_args_list]
        self.assertEqual([f"{identity}:id/fixture_ready", f"{identity}:id/start", f"{identity}:id/email", f"{identity}:id/continue_action"], selectors)
        self.assertNotIn("--bundle-id", run.call_args.args[0])

    def test_protocol_failure_cannot_prove_absence(self):
        for stdout in ('not json', '{}', '{"found":0}', '{"found":false,"success":false}',
                       '{"error":"offline"}', '{"found":true,"resourceId":"wrong"}'):
            with self.subTest(stdout=stdout), patch("benchmarks.fixtures.run_process", side_effect=[
                    self.result("fixture_ready"), self.result("start"), {**self.result("loading", False), "stdout": stdout}]) as run:
                ok, error = fixtures.wait_fixture_ready({"fixtureScenario": "disappear", "timeoutMs": 1000},
                                              self.manifest(), {})
            self.assertFalse(ok)
            self.assertIn("readiness", error)
            self.assertEqual(3, run.call_count)

    def test_process_error_stops_setup_without_retry(self):
        for changes in ({"exit_code": 3}, {"timed_out": True, "exit_code": None}):
            with self.subTest(changes=changes), patch("benchmarks.fixtures.run_process", return_value={
                    **self.result("start"), **changes}) as run:
                ok, error = fixtures.wait_fixture_ready({"fixtureScenario": "missing", "timeoutMs": 1000},
                                              self.manifest(), {})
            self.assertFalse(ok)
            self.assertIn("readiness", error)
            self.assertEqual(1, run.call_count)

    def test_readiness_expires_with_one_total_budget(self):
        now = [0.0]
        def advance(seconds):
            now[0] += seconds
        with patch("benchmarks.fixtures.time.monotonic", side_effect=lambda: now[0]), \
                patch("benchmarks.fixtures.time.sleep", side_effect=advance), \
                patch("benchmarks.fixtures.run_process", return_value=self.result("start", False)) as run:
            ok, error = fixtures.wait_fixture_ready({"fixtureScenario": "missing", "timeoutMs": 250},
                                          self.manifest(), {})
        self.assertFalse(ok)
        self.assertIn("250 ms", error)
        self.assertEqual(3, run.call_count)
        self.assertLessEqual(run.call_args.kwargs["timeout_ms"], 50)
        self.assertTrue(all("fixture_ready" in call.args[0] for call in run.call_args_list))
