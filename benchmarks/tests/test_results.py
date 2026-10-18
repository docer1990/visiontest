import unittest
import tempfile
import json
import signal
import subprocess
import sys
from unittest.mock import Mock, patch
from pathlib import Path

from benchmarks.results import summarize, write_samples


class ResultsTest(unittest.TestCase):
    def test_failures_remain_in_population(self):
        samples = [
            {"status": "passed", "duration_ns": 10},
            {"status": "failed", "duration_ns": 90},
            {"status": "timeout", "duration_ns": 100},
            {"status": "setup_failed", "duration_ns": None},
        ]
        result = summarize(samples)
        self.assertEqual(4, result["attempts"])
        self.assertEqual(1, result["passed"])
        self.assertEqual(1, result["setup_failed"])
        self.assertEqual(90, result["all_timed"]["median_ns"])
        self.assertEqual(100, result["all_timed"]["p95_ns"])
        self.assertEqual(10, result["successful"]["median_ns"])

    def test_warmups_have_a_separate_population(self):
        result = summarize([
            {"status": "passed", "duration_ns": 100, "warmup": True},
            {"status": "passed", "duration_ns": 10, "warmup": False},
        ])
        self.assertEqual(2, result["attempts"])
        self.assertEqual(1, result["measured"]["attempts"])
        self.assertEqual(1, result["warmups"]["attempts"])
        self.assertEqual(10, result["measured"]["successful"]["median_ns"])
        self.assertEqual(100, result["warmups"]["successful"]["median_ns"])

    def test_empty_populations_have_null_percentiles(self):
        result = summarize([])
        self.assertEqual(0, result["all_timed"]["count"])
        self.assertIsNone(result["all_timed"]["median_ns"])
        self.assertIsNone(result["all_timed"]["p95_ns"])

    def test_setup_duration_is_not_operation_latency(self):
        result = summarize([{
            "status": "setup_failed",
            "duration_ns": None,
            "setup_duration_ns": 500,
        }])
        self.assertIsNone(result["all_timed"]["median_ns"])
        self.assertEqual(500, result["setup_timed"]["median_ns"])

    def test_setup_failure_cannot_be_counted_as_operation_time(self):
        with self.assertRaises(ValueError):
            summarize([{"status": "setup_failed", "duration_ns": 500}])

    def test_summary_reports_operation_and_trace_outcomes(self):
        result = summarize([{
            "status": "failed",
            "duration_ns": 15,
            "operation_outcome": "failure",
            "assertion_outcome": "failed",
            "trace_complete": False,
            "measurement_gaps": ["native_missing"],
        }])
        self.assertEqual({"failure": 1}, result["operation_outcomes"])
        self.assertEqual({"failed": 1}, result["assertion_outcomes"])
        self.assertEqual({"complete": 0, "incomplete": 1, "unavailable": 0}, result["trace_completeness"])
        self.assertEqual({"native_missing": 1}, result["measurement_gaps"])

    def test_jsonl_keeps_failed_sample_records(self):
        sample = {"status": "timeout", "duration_ns": 40}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.jsonl"
            write_samples(path, [sample])
            self.assertEqual(sample, json.loads(path.read_text(encoding="utf-8")))

    def test_successful_update_publishes_all_samples_in_order(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.jsonl"
            samples = [{"status": "passed", "duration_ns": 10}]
            write_samples(path, samples)
            samples.append({"status": "failed", "duration_ns": 20})
            write_samples(path, samples)
            self.assertEqual(samples, [json.loads(line) for line in path.read_text().splitlines()])
            self.assertEqual([path], list(Path(directory).iterdir()))

    def test_write_failure_preserves_existing_samples(self):
        create_temporary_file = tempfile.NamedTemporaryFile

        def failing_output(*args, **kwargs):
            output = create_temporary_file(*args, **kwargs)
            output.write = Mock(side_effect=OSError("write failed"))
            return output

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.jsonl"
            write_samples(path, [{"status": "passed", "duration_ns": 10}])
            previous = path.read_bytes()
            with patch("benchmarks.results.tempfile.NamedTemporaryFile", side_effect=failing_output):
                with self.assertRaisesRegex(OSError, "write failed"):
                    write_samples(path, [{"status": "failed"}])
            self.assertEqual(previous, path.read_bytes())
            self.assertEqual([path], list(Path(directory).iterdir()))

    def test_serialization_failure_preserves_existing_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.jsonl"
            write_samples(path, [{"status": "passed", "duration_ns": 10}])
            previous = path.read_bytes()
            with self.assertRaises(TypeError):
                write_samples(path, [{"status": "failed"}, {"unsupported": object()}])
            self.assertEqual(previous, path.read_bytes())
            self.assertEqual([path], list(Path(directory).iterdir()))

    def test_replace_failure_preserves_existing_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.jsonl"
            write_samples(path, [{"status": "passed", "duration_ns": 10}])
            previous = path.read_bytes()
            with patch.object(Path, "replace", side_effect=OSError("replacement failed")):
                with self.assertRaisesRegex(OSError, "replacement failed"):
                    write_samples(path, [{"status": "failed"}])
            self.assertEqual(previous, path.read_bytes())
            self.assertEqual([path], list(Path(directory).iterdir()))

    @unittest.skipUnless(sys.platform != "win32", "POSIX termination signals")
    def test_interrupted_update_preserves_existing_samples(self):
        for interruption in (signal.SIGTERM, signal.SIGKILL):
            with self.subTest(signal=interruption), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "samples.jsonl"
                write_samples(path, [{"status": "passed", "duration_ns": 10}])
                previous = path.read_bytes()
                script = """
import sys
from pathlib import Path
from benchmarks.results import write_samples

class PausedSample(dict):
    def items(self):
        print("serializing", flush=True)
        sys.stdin.read(1)
        return super().items()

write_samples(Path(sys.argv[1]), [PausedSample(status="failed")])
"""
                with subprocess.Popen([sys.executable, "-c", script, str(path)],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, text=True) as child:
                    try:
                        self.assertEqual("serializing\n", child.stdout.readline())
                        child.send_signal(interruption)
                        child.communicate(timeout=5)
                        self.assertEqual(-interruption, child.returncode)
                        self.assertEqual(previous, path.read_bytes())
                    finally:
                        if child.poll() is None:
                            child.kill()
                            child.communicate(timeout=5)

    def test_operation_and_process_boundaries_have_separate_populations(self):
        result = summarize([{'status': 'passed', 'duration_ns': 100,
                             'process_duration_ns': 100, 'operation_duration_ns': 10},
                            {'status': 'timeout', 'duration_ns': 200,
                             'process_duration_ns': 200, 'operation_duration_ns': 20}])
        self.assertIn('process_timed', result)
        self.assertEqual(150, result['process_timed']['median_ns'])
        self.assertEqual(15, result['operation_timed']['median_ns'])
