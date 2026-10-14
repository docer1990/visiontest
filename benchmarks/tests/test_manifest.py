import json
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

from benchmarks.manifest import load_manifest
from benchmarks.run import main


def valid_manifest():
    return {
        "schemaVersion": 1,
        "platform": "android",
        "target": "emulator-5554",
        "fixtureRevision": "fixture-v1",
        "cliPrefix": ["visiontest"],
        "mcpPrefix": ["visiontest", "serve"],
        "environment": {
            name: {"value": "recorded"}
            for name in (
                "build", "nativeArtifactVersions", "connectionType", "targetOS",
                "targetModel", "JVM", "hostOS",
            )
        },
        "cases": [{
            "id": "device-info",
            "setup": [["adb", "shell", "am", "force-stop", "fixture"]],
            "cliArgs": ["device-info"],
            "mcpTool": "get_device_info",
            "mcpArguments": {},
            "timeoutMs": 1000,
            "assertion": {"kind": "device_info", "platform": "android"},
        }],
    }


class ManifestTest(unittest.TestCase):
    def write_manifest(self, directory, value):
        path = Path(directory) / "manifest.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_loads_version_one_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_manifest(directory, valid_manifest())
            self.assertEqual("android", load_manifest(path)["platform"])

    def test_rejects_unsupported_schema_version(self):
        manifest = valid_manifest()
        manifest["schemaVersion"] = 2
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_boolean_schema_version(self):
        manifest = valid_manifest()
        manifest["schemaVersion"] = True
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_empty_launcher_argv(self):
        manifest = valid_manifest()
        manifest["cliPrefix"] = []
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_ambiguous_target_configuration(self):
        manifest = valid_manifest()
        manifest["target"] = ["emulator-5554", "emulator-5556"]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_nonpositive_case_timeout(self):
        manifest = valid_manifest()
        manifest["cases"][0]["timeoutMs"] = 0
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_invalid_assertion_kind(self):
        manifest = valid_manifest()
        manifest["cases"][0]["assertion"] = {"kind": "parse_cli_text"}
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_non_string_assertion_kind(self):
        manifest = valid_manifest()
        manifest["cases"][0]["assertion"] = {"kind": ["device_info"]}
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_device_info_assertion_without_platform(self):
        manifest = valid_manifest()
        manifest["cases"][0]["assertion"] = {"kind": "device_info"}
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "platform"):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_invalid_interactive_resource_ids(self):
        manifest = valid_manifest()
        manifest["cases"][0]["assertion"] = {
            "kind": "interactive_elements", "requiredResourceIds": []
        }
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "requiredResourceIds"):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_stateful_action_without_fixture_reset(self):
        manifest = valid_manifest()
        case = manifest["cases"][0]
        case["setup"] = []
        case["assertion"] = {
            "kind": "action_then_element", "resourceId": "done", "expectedFound": True,
            "lookupTool": "find_element", "lookupArguments": {"resourceId": "done"},
            "verificationCliArgs": ["find_element", "--resource-id", "done", "--json"],
        }
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "fixture reset"):
                load_manifest(self.write_manifest(directory, manifest))

    def test_rejects_non_string_process_environment_value(self):
        manifest = valid_manifest()
        manifest["processEnvironment"] = {"VISIONTEST_VALUE": 3}
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "processEnvironment"):
                load_manifest(self.write_manifest(directory, manifest))

    def test_native_cold_requires_explicit_restart_before_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = self.write_manifest(directory, valid_manifest())
            output = Path(directory) / "run"
            with contextlib.redirect_stderr(io.StringIO()):
                result = main(["commands", "--manifest", str(manifest_path), "--output", str(output),
                               "--mode", "cli", "--lifecycle", "native-cold"])
            self.assertEqual(2, result)
            self.assertFalse(output.exists())

    def test_rejects_existing_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = self.write_manifest(directory, valid_manifest())
            output = Path(directory) / "run"
            output.mkdir()
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, main([
                    "commands", "--manifest", str(manifest_path), "--output", str(output),
                    "--mode", "cli",
                ]))

    def test_cli_failure_is_retained_in_run_files(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = valid_manifest()
            manifest["cliPrefix"] = [sys.executable, "-c", "import sys; print('failed', file=sys.stderr); sys.exit(7)"]
            manifest["cases"][0]["cliArgs"] = ["ignored"]
            manifest["cases"][0]["setup"] = []
            manifest_path = self.write_manifest(directory, manifest)
            output = Path(directory) / "fresh-run"
            result = main(["commands", "--manifest", str(manifest_path), "--output", str(output),
                           "--mode", "cli", "--samples", "1", "--warmup", "0"])
            self.assertEqual(1, result)
            samples = [json.loads(line) for line in (output / "samples.jsonl").read_text().splitlines()]
            self.assertEqual("failed", samples[0]["status"])
            self.assertIn("code 7", samples[0]["failure"])
            self.assertTrue((output / "summary.json").exists())
