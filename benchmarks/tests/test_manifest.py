import json
import contextlib
import io
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
            "assertion": {"kind": "device_info"},
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

    def test_scaffold_writes_separate_run_files_before_adapter_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = self.write_manifest(directory, valid_manifest())
            output = Path(directory) / "fresh-run"
            with contextlib.redirect_stderr(io.StringIO()):
                result = main([
                    "commands", "--manifest", str(manifest_path), "--output", str(output),
                    "--mode", "cli", "--samples", "1", "--warmup", "0",
                ])
            self.assertEqual(1, result)
            self.assertEqual({"samples.jsonl", "environment.json", "summary.json"},
                             {path.name for path in output.iterdir()})
