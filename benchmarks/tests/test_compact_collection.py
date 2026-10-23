"""Exercise the portable collector without launching a process or contacting a device."""
import json
from pathlib import Path
import runpy
import sys
import subprocess
import urllib.error
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


COLLECTOR = Path(__file__).resolve().parents[1] / "compact-inspection/collect_ios.py"


class CompactCollectionTest(unittest.TestCase):
    def collect(self, setup_exit=0, command_error=None, setup_error=None,
                readiness_error=None, readiness_body=None, command_stdout=None):
        commands = []

        def run(argv, **kwargs):
            commands.append(argv)
            if argv[0] == "xcrun":
                if setup_error:
                    raise setup_error
                return SimpleNamespace(returncode=setup_exit, stderr="setup diagnostic")
            if command_error:
                raise command_error
            return SimpleNamespace(returncode=0, stdout=command_stdout if command_stdout is not None else '{"success":true}\n',
                                   stderr="command diagnostic")

        tokenizer = SimpleNamespace(
            __version__="test", get_encoding=lambda name: SimpleNamespace(name=name, encode=list),
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "run"
            argv = [str(COLLECTOR), "--udid", "test-simulator", "--runtime", "test-runtime",
                    "--output", str(output)]
            with patch.object(sys, "argv", argv), patch.dict(sys.modules, {"tiktoken": tokenizer}), \
                    patch("subprocess.run", side_effect=run), \
                    patch("subprocess.check_output", return_value="test-revision\n"), \
                    patch("platform.platform", return_value="test-host"), \
                    patch.object(Path, "read_bytes", return_value=b"synthetic-artifact"), \
                    patch("urllib.request.urlopen") as request, patch("builtins.print"):
                request.return_value.__enter__.return_value.read.return_value = \
                    readiness_body if readiness_body is not None else b'{"result":{"found":true}}'
                if readiness_error:
                    request.side_effect = readiness_error
                try:
                    runpy.run_path(str(COLLECTOR), run_name="__main__")
                except Exception as error:
                    self.fail(f"Collection aborted instead of retaining failed attempts: {error}")
                report = json.loads((output / "results.json").read_text())
                request_count = request.call_count
        return report, commands, request_count

    def test_corrected_form_argv_and_all_pairs_are_recorded(self):
        report, commands, request_count = self.collect()
        self.assertEqual(8, len(report["samples"]))
        self.assertEqual(8, request_count)
        self.assertEqual(16, len([argv for argv in commands if argv[0] == "java"]))
        forms = [argv for argv in commands if any("input_text " in arg for arg in argv)]
        self.assertEqual(2, len(forms))
        for argv in forms:
            self.assertIn("input_text agent@example.test --target-resource-id email "
                          "--bundle-id dev.visiontest.benchmark", argv)
        self.assertNotIn("--compact", forms[0])
        self.assertIn("--compact", forms[1])
        self.assertEqual("test-runtime", report["runtime"])
        for sample in report["samples"]:
            self.assertEqual(0, sample["flow"]["exitCode"])
            self.assertEqual(len('{"success":true}\n'), sample["flow"]["bytes"])

    def test_failed_setup_is_retained_for_every_case_without_cli_calls(self):
        report, commands, request_count = self.collect(setup_exit=7)
        self.assertEqual(8, len(report["samples"]))
        self.assertEqual(0, request_count)
        self.assertTrue(all(argv[0] == "xcrun" for argv in commands))
        for sample in report["samples"]:
            self.assertEqual({"ok": False, "exitCode": 7, "error": "setup diagnostic"}, sample["setup"])
            self.assertNotIn("inspection", sample)
            self.assertNotIn("flow", sample)

    def test_command_exceptions_remain_visible_in_every_sample(self):
        report, _, _ = self.collect(command_error=TimeoutError("command timed out"))
        self.assertEqual(8, len(report["samples"]))
        for sample in report["samples"]:
            for kind in ("inspection", "flow"):
                self.assertEqual("command timed out", sample[kind]["error"])
                self.assertGreaterEqual(sample[kind]["durationMs"], 0)

    def test_setup_launch_timeout_is_retained(self):
        error = subprocess.TimeoutExpired("simctl", 30, output=b"launch partial", stderr=b"launch error")
        report, commands, _ = self.collect(setup_error=error)
        self.assertEqual(8, len(report["samples"]))
        self.assertTrue(all(argv[0] == "xcrun" for argv in commands))
        for sample in report["samples"]:
            setup = sample["setup"]
            self.assertFalse(setup["ok"])
            self.assertEqual("TimeoutExpired", setup["errorType"])
            self.assertEqual("launch partial", setup["rawStdout"])
            self.assertEqual("launch error", setup["stderr"])
            self.assertIsNone(setup["exitCode"])

    def test_readiness_transport_error_is_retained(self):
        report, commands, _ = self.collect(readiness_error=urllib.error.URLError("unavailable"))
        self.assertEqual(8, len(report["samples"]))
        self.assertTrue(all(argv[0] == "xcrun" for argv in commands))
        for sample in report["samples"]:
            self.assertFalse(sample["setup"]["ok"])
            self.assertEqual("URLError", sample["setup"]["errorType"])

    def test_malformed_readiness_json_is_retained(self):
        report, _, _ = self.collect(readiness_body=b"not json")
        self.assertEqual(8, len(report["samples"]))
        for sample in report["samples"]:
            self.assertFalse(sample["setup"]["ok"])
            self.assertEqual("JSONDecodeError", sample["setup"]["errorType"])

    def test_invalid_command_json_keeps_process_evidence(self):
        raw = "invalid stdout é\n"
        report, _, _ = self.collect(command_stdout=raw)
        for sample in report["samples"]:
            for kind in ("inspection", "flow"):
                record = sample[kind]
                self.assertEqual(raw, record.get("rawStdout"))
                self.assertEqual("command diagnostic", record["stderr"])
                self.assertEqual(0, record["exitCode"])
                self.assertEqual(len(raw.encode()), record["bytes"])
                self.assertEqual(len(raw), record["estimatedTokens"])
                self.assertIsNone(record["stdout"])
                self.assertEqual("JSONDecodeError", record["errorType"])
                self.assertIn("error", record)

    def test_command_timeout_keeps_partial_process_evidence(self):
        error = subprocess.TimeoutExpired("java", 60, output=b"partial output", stderr=b"partial error")
        report, _, _ = self.collect(command_error=error)
        for sample in report["samples"]:
            for kind in ("inspection", "flow"):
                record = sample[kind]
                self.assertEqual("partial output", record.get("rawStdout"))
                self.assertEqual("partial error", record["stderr"])
                self.assertIsNone(record["exitCode"])
                self.assertIsNone(record["stdout"])
                self.assertEqual(14, record["bytes"])
                self.assertEqual(14, record["estimatedTokens"])
                self.assertEqual("TimeoutExpired", record["errorType"])
