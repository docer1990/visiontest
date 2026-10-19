import unittest
from tempfile import TemporaryDirectory
from pathlib import Path
from unittest.mock import patch

from benchmarks.fixtures import resolve_argv, selector_resource_id, validate_fixture_identity, validate_target
from benchmarks.manifest import load_manifest
from benchmarks.run import _run_setup
from benchmarks.tests import test_fixture_smoke


class FixtureTargetTest(unittest.TestCase):
    def test_duplicate_label_smoke_accepts_platform_qualified_identifiers(self):
        identity = "com.example.visiontest.benchmark"
        for platform in ("android", "ios"):
            with self.subTest(platform=platform):
                payload = {"elements": [
                    {"text": "Continue", "resourceId": selector_resource_id(platform, identity, name)}
                    for name in ("continue_primary", "continue_secondary")
                ]}
                smoke_class = test_fixture_smoke.FixtureSmokeTest
                smoke = smoke_class("test_duplicate_visible_labels_have_distinct_inspection_ids")
                with patch.object(smoke_class, "manifest", {"platform": platform, "fixtureIdentity": identity},
                                  create=True), patch.object(smoke, "reset"), \
                        patch.object(smoke, "command", return_value=payload):
                    smoke.test_duplicate_visible_labels_have_distinct_inspection_ids()

    def test_lookup_cli_selects_the_same_fixture_element_as_mcp(self):
        root = Path(__file__).parents[1] / "manifests"
        for platform in ("android", "ios"):
            with self.subTest(platform=platform):
                manifest = load_manifest(root / f"{platform}.json")
                case = next(case for case in manifest["cases"] if case["id"] == "lookup")
                args = case["cliArgs"]
                self.assertIn("--resource-id", args)
                self.assertEqual(case["mcpArguments"]["resourceId"], args[args.index("--resource-id") + 1])

    def test_disappearance_cli_uses_the_public_gone_wait_command(self):
        root = Path(__file__).parents[1] / "manifests"
        for platform in ("android", "ios"):
            with self.subTest(platform=platform):
                manifest = load_manifest(root / f"{platform}.json")
                case = next(case for case in manifest["cases"] if case["id"] == "disappearance")
                self.assertEqual("wait_for_element", case["cliArgs"][0])
                self.assertIn("--gone", case["cliArgs"])

    def test_platform_manifests_cover_fixture_command_categories(self):
        expected = {"information", "lookup", "interaction", "appearance", "disappearance",
                    "inspection", "screenshot", "missing-target"}
        root = Path(__file__).parents[1] / "manifests"
        for platform in ("android", "ios"):
            manifest = load_manifest(root / f"{platform}.json")
            self.assertEqual(expected, {case["id"] for case in manifest["cases"]})
            self.assertTrue(all(case["setup"] for case in manifest["cases"]))
        ios = load_manifest(root / "ios.json")
        self.assertTrue(all(
            case["mcpArguments"].get("bundleId") == ios["fixtureIdentity"]
            for case in ios["cases"] if case["id"] not in {"information", "screenshot"}
        ))

    def test_android_continue_uses_accessibility_identifier_not_reserved_resource_name(self):
        root = Path(__file__).parents[1]
        manifest = load_manifest(root / "manifests/android.json")
        interaction = next(case for case in manifest["cases"] if case["id"] == "interaction")
        ids = (root / "fixtures/android/src/main/res/values/ids.xml").read_text(encoding="utf-8")
        self.assertEqual("continue", interaction["mcpArguments"]["contentDescription"])
        self.assertIn("--content-description", interaction["cliArgs"])
        self.assertIn("continue_action", ids)
        self.assertNotIn('name="continue"', ids)

    def test_ios_setup_resolves_scenario_and_language_only_as_complete_arguments(self):
        root = Path(__file__).parents[1]
        manifest = load_manifest(root / "manifests/ios.json")
        case = next(case for case in manifest["cases"] if case["id"] == "appearance")
        resolved = resolve_argv(case["setup"][2], {
            "target": "sim-1", "fixtureIdentity": manifest["fixtureIdentity"],
            "scenario": case["fixtureScenario"], "language": case["fixtureLanguage"],
        })
        self.assertEqual("appear", resolved[-2])
        self.assertEqual("en", resolved[-1])
        self.assertNotIn("scenario=appear", resolved)

    def test_selector_resource_ids_follow_platform_conventions(self):
        identity = "com.example.visiontest.benchmark"
        self.assertEqual(f"{identity}:id/done", selector_resource_id("android", identity, "done"))
        self.assertEqual("done", selector_resource_id("ios", identity, "done"))

    def test_ambiguous_target_prevents_mutating_setup(self):
        with self.assertRaises(ValueError):
            validate_target("android", "emulator-5554", ["emulator-5554", "emulator-5556"])
        validate_target("ios", "isolated-simulator", ["isolated-simulator"])

    def test_rejects_a_selected_target_that_is_not_the_only_active_target(self):
        with self.assertRaises(ValueError):
            validate_target("ios", "isolated-simulator", [])

    def test_manifest_tokens_are_resolved_only_as_complete_argv_values(self):
        self.assertEqual(["adb", "-s", "device-1"], resolve_argv(["adb", "-s", "{target}"], {
            "target": "device-1",
        }))
        with self.assertRaises(ValueError):
            resolve_argv(["adb", "-s", "serial-{target}"], {"target": "device-1"})

    def test_unknown_or_embedded_token_is_rejected(self):
        with self.assertRaises(ValueError):
            resolve_argv(["adb", "{unknown}"], {"target": "device-1"})

    def test_fixture_identity_must_match_the_platform_package(self):
        validate_fixture_identity("android", "com.example.visiontest.benchmark")
        with self.assertRaises(ValueError):
            validate_fixture_identity("ios", "com.example.user.app")

    def test_setup_installs_and_verifies_fixture_only_on_single_selected_target(self):
        with TemporaryDirectory() as directory:
            artifact = Path(directory) / "fixture.apk"
            artifact.touch()
            manifest = load_manifest(Path(__file__).parents[1] / "manifests/android.json")
            manifest.update(target="device-1", fixtureArtifact=str(artifact))
            case = manifest["cases"][0]
            devices = {"duration_ns": 0, "exit_code": 0, "stdout": "List of devices attached\ndevice-1 device\n",
                       "stderr": "", "timed_out": False}
            success = {"duration_ns": 0, "exit_code": 0, "stdout": "", "stderr": "", "timed_out": False}
            with patch("benchmarks.run.run_process", side_effect=[devices, success, success, success, success]) as run, \
                    patch("benchmarks.run.wait_fixture_ready", return_value=(True, None), create=True):
                ok, _, error = _run_setup(case, manifest, {})
            self.assertTrue(ok, error)
            self.assertIn("pm", run.call_args_list[2].args[0])
            self.assertEqual("{target}", case["setup"][0][2])

    def test_readiness_failure_skips_the_measured_operation(self):
        with TemporaryDirectory() as directory:
            artifact = Path(directory) / "fixture.apk"
            artifact.touch()
            manifest = load_manifest(Path(__file__).parents[1] / "manifests/android.json")
            manifest.update(target="device-1", fixtureArtifact=str(artifact))
            devices = {"exit_code": 0, "stdout": "List of devices attached\ndevice-1 device\n",
                       "stderr": "", "timed_out": False}
            with patch("benchmarks.run.run_process", return_value=devices), \
                    patch("benchmarks.run.wait_fixture_ready", return_value=(False, "fixture readiness expired"),
                          create=True) as ready:
                ok, _, error = _run_setup(manifest["cases"][0], manifest, {})
            self.assertFalse(ok)
            self.assertEqual("fixture readiness expired", error)
            ready.assert_called_once()

    def test_ambiguous_active_devices_fail_before_install(self):
        with TemporaryDirectory() as directory:
            artifact = Path(directory) / "fixture.apk"
            artifact.touch()
            manifest = load_manifest(Path(__file__).parents[1] / "manifests/android.json")
            manifest.update(target="device-1", fixtureArtifact=str(artifact))
            case = manifest["cases"][0]
            devices = {"duration_ns": 0, "exit_code": 0,
                       "stdout": "List of devices attached\ndevice-1 device\ndevice-2 device\n",
                       "stderr": "", "timed_out": False}
            with patch("benchmarks.run.run_process", return_value=devices) as run:
                ok, _, error = _run_setup(case, manifest, {})
            self.assertFalse(ok)
            self.assertIn("expected only selected", error)
            self.assertEqual(1, run.call_count)


if __name__ == "__main__":
    unittest.main()
