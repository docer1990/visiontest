import json
import os
import time
import unittest
from pathlib import Path

from benchmarks.fixtures import selector_resource_id, validate_fixture_identity
from benchmarks.manifest import load_manifest
from benchmarks.run import _run_setup
from benchmarks.execution import run_process


_MANIFEST = os.environ.get("VISIONTEST_BENCHMARK_MANIFEST")
_ENABLED = os.environ.get("VISIONTEST_BENCHMARK_SMOKE") == "1" and _MANIFEST


@unittest.skipUnless(_ENABLED, "set VISIONTEST_BENCHMARK_SMOKE=1 and VISIONTEST_BENCHMARK_MANIFEST")
class FixtureSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = load_manifest(Path(_MANIFEST))
        validate_fixture_identity(cls.manifest["platform"], cls.manifest["fixtureIdentity"])
        cls.env = dict(cls.manifest.get("processEnvironment", {}))
        cls.prefix = cls.manifest["cliPrefix"]
        cls.timeout = 10_000
        cls.platform_args = ["--platform", cls.manifest["platform"]]
        if cls.manifest["platform"] == "ios":
            cls.platform_args += ["--bundle-id", cls.manifest["fixtureIdentity"]]

    @classmethod
    def case(cls, case_id):
        return next(case for case in cls.manifest["cases"] if case["id"] == case_id)

    @classmethod
    def reset(cls, scenario):
        fixture_case = next(case for case in cls.manifest["cases"] if case["id"] == {
            "navigation": "information", "appear": "appearance", "disappear": "disappearance",
            "missing": "missing-target", "ambiguous": "inspection", "error": "information",
        }[scenario])
        setup = list(fixture_case["setup"])
        launch = list(setup[-1])
        if cls.manifest["platform"] == "android":
            launch[launch.index("{scenario}")] = scenario
        else:
            launch[launch.index("{scenario}")] = scenario
        setup[-1] = launch
        case = dict(fixture_case, setup=setup, fixtureScenario=scenario)
        case["setupBenignExitCodes"] = fixture_case.get("setupBenignExitCodes", [[], [], []])
        ok, _, error = _run_setup(case, cls.manifest, cls.env)
        if not ok:
            raise AssertionError(f"fixture reset failed: {error}")
        time.sleep(0.5)

    @classmethod
    def command(cls, args, timeout=None):
        result = run_process(cls.prefix + args + cls.platform_args, timeout or cls.timeout, cls.env)
        if result["timed_out"] or result["exit_code"] != 0:
            raise AssertionError(f"VisionTest command failed: {result['stderr']}")
        try:
            return json.loads(result["stdout"])
        except json.JSONDecodeError as error:
            if args[0] == "wait_for_element" and "--gone" in args:
                return result["stdout"].strip()
            raise AssertionError(f"VisionTest returned non-JSON inspection output: {result['stdout']}") from error

    def found(self, resource_id):
        resource_id = self.resource_id(resource_id)
        return self.command(["find_element", "--resource-id", resource_id, "--json"]).get("found")

    @classmethod
    def resource_id(cls, name):
        return selector_resource_id(cls.manifest["platform"], cls.manifest["fixtureIdentity"], name)

    def test_reset_is_idempotent(self):
        self.reset("navigation")
        self.assertTrue(self.found("start"))
        self.reset("navigation")
        self.assertTrue(self.found("start"))
        self.assertFalse(self.found("done"))

    def test_done_and_error_scenarios_are_exclusive(self):
        self.reset("error")
        self.assertTrue(self.found("error"))
        self.assertFalse(self.found("done"))
        self.reset("navigation")
        if self.manifest["platform"] == "ios":
            self.command(["tap_on_element", "--resource-id", "continue"])
        else:
            self.command(["tap_on_element", "--content-description", "continue"])
        self.assertTrue(self.found("done"))
        self.assertFalse(self.found("error"))

    def test_delayed_appearance_and_disappearance(self):
        self.reset("appear")
        self.command(["wait_for_element", "--resource-id", self.resource_id("done"), "--timeout", "4000"], 10_000)
        self.reset("disappear")
        self.command(["wait_for_element", "--resource-id", self.resource_id("loading"), "--gone", "--timeout", "4000"], 10_000)

    def test_missing_target_is_absent(self):
        self.reset("missing")
        self.assertFalse(self.found("email"))

    def test_duplicate_visible_labels_have_distinct_inspection_ids(self):
        self.reset("ambiguous")
        result = self.command(["get_interactive_elements", "--json"])
        elements = result.get("elements", [])
        matches = [element for element in elements
                   if isinstance(element.get("text"), str)
                   and element["text"].casefold() in {"continue", "continua"}
                   and element.get("resourceId") in {
                       self.resource_id("continue_primary"), self.resource_id("continue_secondary")
                   }]
        self.assertEqual(2, len(matches))
        self.assertEqual(2, len({element.get("resourceId") for element in matches}))


if __name__ == "__main__":
    unittest.main()
