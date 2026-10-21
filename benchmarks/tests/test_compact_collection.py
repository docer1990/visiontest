"""Exercise the portable collector without launching a process or contacting a device."""
import json
from pathlib import Path
import runpy
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


COLLECTOR = Path(__file__).resolve().parents[1] / "compact-inspection/collect_ios.py"


class CompactCollectionTest(unittest.TestCase):
    def collect(self, setup_exit=0, command_error=None):
        commands = []

        def run(argv, **kwargs):
            commands.append(argv)
            if argv[0] == "xcrun":
                return SimpleNamespace(returncode=setup_exit, stderr="setup diagnostic")
            if command_error:
                raise command_error
            return SimpleNamespace(returncode=0, stdout='{"success":true}\n', stderr="")

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
                    b'{"result":{"found":true}}'
                runpy.run_path(str(COLLECTOR), run_name="__main__")
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
                          "--bundle-id com.example.visiontest.benchmark", argv)
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
