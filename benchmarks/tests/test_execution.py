import json
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from benchmarks.execution import run_process
from benchmarks.run import main


class ExecutionTest(unittest.TestCase):
    def test_arguments_are_not_shell_expanded(self):
        value = "$(touch must-not-exist); 'quoted value'"
        result = run_process(
            [sys.executable, "-c", "import sys; print(sys.argv[1])", value],
            timeout_ms=5_000,
        )
        self.assertEqual(value + "\n", result["stdout"])
        self.assertEqual(0, result["exit_code"])
        self.assertGreaterEqual(result["duration_ns"], 0)

    def test_timeout_terminates_owned_process_group_and_keeps_output(self):
        result = run_process(
            [sys.executable, "-c", "import time; print('started', flush=True); time.sleep(10)"],
            timeout_ms=100,
        )
        self.assertTrue(result["timed_out"])
        self.assertIsNone(result["exit_code"])
        self.assertIn("started", result["stdout"])
        self.assertGreaterEqual(result["duration_ns"], 0)

    def test_stderr_is_drained_while_stdout_is_read(self):
        result = run_process(
            [sys.executable, "-c", "import sys; sys.stderr.write('e'*200000); print('done')"],
            timeout_ms=5_000,
        )
        self.assertEqual(0, result["exit_code"])
        self.assertEqual("done\n", result["stdout"])
        self.assertEqual(200000, len(result["stderr"]))

    def test_environment_overrides_are_merged(self):
        result = run_process(
            [sys.executable, "-c", "import os; print(os.environ['BENCHMARK_VALUE'])"],
            timeout_ms=5_000,
            env={"BENCHMARK_VALUE": "provided"},
        )
        self.assertEqual("provided\n", result["stdout"])
        self.assertEqual(0, result["exit_code"])

    def test_missing_executable_is_a_retained_process_failure(self):
        result = run_process(["benchmark-command-that-does-not-exist"], timeout_ms=100)
        self.assertEqual(127, result["exit_code"])
        self.assertFalse(result["timed_out"])
        self.assertRegex(result["stderr"], "No such file|not found")

    def test_runner_keeps_sample_order_and_failed_assertions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {
                "schemaVersion": 1, "platform": "android", "target": "emulator",
                "fixtureIdentity": "com.example.visiontest.benchmark", "fixtureArtifact": "fixture.apk",
                "fixtureRevision": "test", "cliPrefix": [sys.executable, "-c",
                    "import json; print(json.dumps({'success': True, 'displayWidth': 10, 'displayHeight': 20, 'productName': 'test', 'sdkVersion': 35}))"],
                "mcpPrefix": [sys.executable, "-c", "pass"],
                "environment": {key: {"value": "test"} for key in (
                    "build", "nativeArtifactVersions", "connectionType", "targetOS",
                    "targetModel", "JVM", "hostOS")},
                "cases": [{"id": "info", "fixtureScenario": "navigation", "fixtureLanguage": "en",
                    "setup": [
                        ["adb", "-s", "{target}", "install", "-r", "{fixtureArtifact}"],
                        ["adb", "-s", "{target}", "shell", "am", "force-stop", "{fixtureIdentity}"],
                        ["adb", "-s", "{target}", "shell", "am", "start", "-n",
                         "com.example.visiontest.benchmark/.MainActivity", "--es", "scenario", "{scenario}",
                         "--es", "language", "{language}"],
                    ], "cliArgs": ["ignored"], "mcpTool": "info",
                    "mcpArguments": {}, "timeoutMs": 2000,
                    "assertion": {"kind": "device_info", "platform": "android"}}],
            }
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            output = root / "run"
            with patch("benchmarks.run._run_setup", return_value=(True, 0, None)):
                result = main(["commands", "--manifest", str(manifest_path),
                    "--output", str(output), "--mode", "cli", "--samples", "2", "--warmup", "0"])
            self.assertEqual(0, result)
            samples = [json.loads(line) for line in (output / "samples.jsonl").read_text().splitlines()]
            self.assertEqual(["info-sample-1", "info-sample-2"], [sample["sample_id"] for sample in samples])
            self.assertEqual(["passed", "passed"], [sample["status"] for sample in samples])


if __name__ == "__main__":
    unittest.main()
