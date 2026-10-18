import json
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from benchmarks.execution import run_process
from benchmarks.cli import run_cli
from benchmarks.run import main, _mcp_environment


class ExecutionTest(unittest.TestCase):
    def test_failed_warmup_sets_failure_exit_and_keeps_successful_measured_sample(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "warmup-attempted"
            manifest = json.loads(Path("benchmarks/manifests/android.json").read_text())
            manifest["cases"] = manifest["cases"][:1]
            manifest["cliPrefix"] = [sys.executable, "-c",
                "import json, pathlib, sys; marker = pathlib.Path(sys.argv[1]); "
                "passed = marker.exists(); marker.touch(); "
                "print(json.dumps({'success': passed, 'displayWidth': 10, 'displayHeight': 20, "
                "'productName': 'test', 'sdkVersion': 35}))", str(marker)]
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest))
            output = root / "run"
            with patch("benchmarks.run._run_setup", return_value=(True, 0, None)):
                result = main(["commands", "--manifest", str(manifest_path),
                               "--output", str(output), "--mode", "cli",
                               "--samples", "1", "--warmup", "1"])
            samples = [json.loads(line) for line in (output / "samples.jsonl").read_text().splitlines()]
            self.assertEqual(["failed", "passed"], [sample["status"] for sample in samples])
            self.assertEqual([True, False], [sample["warmup"] for sample in samples])
            self.assertEqual([1, 2], [sample["sample_sequence"] for sample in samples])
            self.assertEqual(1, result)

    def test_cli_screenshot_text_success_is_verified_from_the_created_png(self):
        with tempfile.TemporaryDirectory() as directory:
            case = {"id": "screenshot", "cliArgs": [], "timeoutMs": 2000,
                    "assertion": {"kind": "screenshot_file"}}
            prefix = [sys.executable, "-c",
                      "import pathlib, sys; "
                      "path = pathlib.Path(sys.argv[sys.argv.index('--output') + 1]); "
                      "path.write_bytes(b'\\x89PNG\\r\\n\\x1a\\n'); "
                      "print('Screenshot saved to ' + str(path))"]
            result = run_cli(prefix, case, output_dir=Path(directory))
            self.assertEqual("success", result["operation_outcome"])
            self.assertEqual("passed", result["assertion_outcome"])

    def test_cli_screenshot_text_without_a_new_file_fails_assertion(self):
        with tempfile.TemporaryDirectory() as directory:
            case = {"id": "screenshot", "cliArgs": [], "timeoutMs": 2000,
                    "assertion": {"kind": "screenshot_file"}}
            path = Path(directory) / "capture.png"
            path.write_bytes(b"\x89PNG\r\n\x1a\n")
            result = run_cli([sys.executable, "-c", "print('Screenshot failed')"],
                             case, output_dir=Path(directory))
            self.assertEqual("success", result["operation_outcome"])
            self.assertEqual("failed", result["assertion_outcome"])
            self.assertFalse(path.exists())

    def test_off_state_explicitly_disables_inherited_mcp_tracing(self):
        self.assertEqual('', _mcp_environment({'processEnvironment': {'VISIONTEST_TRACE_PERFORMANCE': 'inherited'}}, None)['VISIONTEST_TRACE_PERFORMANCE'])

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


class PairedRunnerTest(unittest.TestCase):
    def test_paired_screenshot_attempts_retain_distinct_artifacts_including_failures(self):
        for mode, lifecycle in (("cli", "warm"), ("mcp", "warm"), ("mcp", "host-cold")):
            for fail in (False, True):
                with self.subTest(mode=mode, lifecycle=lifecycle, fail=fail), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    manifest = json.loads(Path("benchmarks/manifests/android.json").read_text())
                    case = next(case for case in manifest["cases"] if case["assertion"]["kind"] == "screenshot_file")
                    manifest["cases"] = [case]
                    case["mcpTool"] = "first"
                    manifest["mcpPrefix"] = [sys.executable, str(Path(__file__).with_name("fake_mcp.py")),
                                             "screenshot-error" if fail else "screenshot"]
                    manifest["cliPrefix"] = [sys.executable, "-c",
                        "import pathlib, sys; "
                        "path = pathlib.Path(sys.argv[sys.argv.index('--output') + 1]); "
                        "path.write_bytes(bytes([137,80,78,71,13,10,26,10])); "
                        "print('Screenshot saved'); sys.exit(" + ("7" if fail else "0") + ")"]
                    path = root / "manifest.json"
                    path.write_text(json.dumps(manifest))
                    output = root / "run"
                    with patch("benchmarks.run._run_setup", return_value=(True, 0, None)):
                        code = main(["commands", "--manifest", str(path), "--output", str(output),
                                     "--mode", mode, "--samples", "1", "--warmup", "0",
                                     "--tracing", "paired", "--lifecycle", lifecycle])
                    self.assertEqual(1 if fail else 0, code)
                    samples = [json.loads(line) for line in (output / "samples.jsonl").read_text().splitlines()]
                    self.assertEqual(["failed" if fail else "passed"] * 2, [sample["status"] for sample in samples])
                    references = [sample.get("screenshot_path") for sample in samples]
                    self.assertEqual(["artifacts/1/capture.png", "artifacts/2/capture.png"], references)
                    for reference in references:
                        self.assertEqual(bytes([137,80,78,71,13,10,26,10]), (output / reference).read_bytes())

    def test_every_pair_has_both_states_and_alternates_order(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = json.loads(Path('benchmarks/manifests/android.json').read_text())
            manifest['cases'] = manifest['cases'][:1]
            manifest['cliPrefix'] = [sys.executable, '-c',
                "import json; print(json.dumps({'success': True, 'displayWidth': 10, 'displayHeight': 20, 'productName': 'test', 'sdkVersion': 35}))"]
            path = root / 'manifest.json'; path.write_text(json.dumps(manifest))
            output = root / 'run-{run_id}'
            with patch('benchmarks.run._run_setup', return_value=(True, 0, None)):
                code = main(['commands', '--manifest', str(path), '--output', str(output),
                             '--mode', 'cli', '--samples', '2', '--warmup', '1', '--tracing', 'paired'])
            self.assertEqual(0, code)
            self.assertFalse(output.exists())
            output = next(root.glob('run-*'))
            environment = json.loads((output / 'environment.json').read_text())
            self.assertEqual('run-' + environment['run_id'], output.name)
            self.assertIn('manifest_sha256', environment)
            samples = [json.loads(line) for line in (output / 'samples.jsonl').read_text().splitlines()]
            self.assertEqual(6, len(samples))
            measured = [sample for sample in samples if not sample['warmup']]
            self.assertEqual(['off', 'on', 'on', 'off'], [sample['tracing'] for sample in measured])
            self.assertEqual(measured[0]['pair_id'], measured[1]['pair_id'])
            self.assertNotEqual(measured[0]['sample_id'], measured[1]['sample_id'])
            self.assertEqual(measured[0]['duration_ns'], measured[0]['process_duration_ns'])
            self.assertIsNone(measured[0]['operation_duration_ns'])
            self.assertFalse(measured[1]['trace_complete'])
            self.assertIn('overhead', json.loads((output / 'summary.json').read_text()))

    def test_warm_mcp_trace_attributes_after_verification_and_finalization(self):
        for lifecycle in ('warm', 'host-cold'):
            with self.subTest(lifecycle=lifecycle), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest = json.loads(Path('benchmarks/manifests/android.json').read_text())
                manifest['cases'] = manifest['cases'][2:3]
                case = manifest['cases'][0]
                case['mcpTool'] = 'info'
                case['assertion']['lookupTool'] = 'second'
                manifest['mcpPrefix'] = [sys.executable, str(Path(__file__).with_name('fake_mcp.py')), 'trace']
                path = root / 'manifest.json'; path.write_text(json.dumps(manifest))
                output = root / 'run'
                with patch('benchmarks.run._run_setup', return_value=(True, 0, None)):
                    code = main(['commands', '--manifest', str(path), '--output', str(output),
                                 '--mode', 'mcp', '--samples', '2', '--warmup', '0',
                                 '--tracing', 'paired', '--lifecycle', lifecycle])
                self.assertEqual(0, code)
                samples = [json.loads(line) for line in (output / 'samples.jsonl').read_text().splitlines()]
                traced = [sample for sample in samples if sample['tracing'] == 'on']
                self.assertTrue(all(sample['trace_complete'] for sample in traced))
                self.assertEqual([1, 3] if lifecycle == 'warm' else [1, 1],
                                 [sample['invocation_sequence'] for sample in traced])
                self.assertTrue(all(len(sample['trace_dispatches']) == 2 for sample in traced))
                self.assertTrue(all(sample['operation_duration_ns'] is not None for sample in samples))
                if lifecycle == 'host-cold':
                    self.assertTrue(all(sample['process_duration_ns'] >= sample['operation_duration_ns'] for sample in samples))


if __name__ == "__main__":
    unittest.main()
