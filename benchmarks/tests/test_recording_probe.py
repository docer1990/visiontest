import unittest

from benchmarks.recording_probe import fixture_selector, fixture_steps, phase_durations, traced_flow_command, run_probe, summarize_samples


class RecordingProbeTest(unittest.TestCase):
    def test_invalid_video_does_not_contribute_a_successful_pair(self):
        samples = [
            dict(round=0, mode="plain", success=True, elapsedMs=100, videoValid=None),
            dict(round=0, mode="record", success=True, elapsedMs=120, videoValid=False),
            dict(round=1, mode="plain", success=True, elapsedMs=200, videoValid=None),
            dict(round=1, mode="record", success=True, elapsedMs=240, videoValid=True),
        ]
        summary = summarize_samples(samples)
        self.assertEqual(4, summary["totalSamples"])
        self.assertEqual(1, summary["failedSamples"])
        self.assertEqual(40, summary["pairedMedianDeltaMs"])
        self.assertEqual(4, len(samples))

    def test_missing_pairs_have_no_delta(self):
        samples = [dict(round=0, mode="record", success=True, elapsedMs=100, videoValid=True)]
        self.assertIsNone(summarize_samples(samples)["pairedMedianDeltaMs"])

    def test_paired_median_uses_per_round_differences_not_independent_medians(self):
        samples = [dict(round=round_number, mode=mode, success=True,
                        elapsedMs=elapsed, videoValid=mode == "record" if mode == "record" else None)
                   for round_number, plain, record in [(0, 100, 300), (1, 400, 410), (2, 500, 520)]
                   for mode, elapsed in [("plain", plain), ("record", record)]]
        self.assertEqual(20, summarize_samples(samples)["pairedMedianDeltaMs"])

    def test_failed_subprocess_is_counted_and_excluded(self):
        samples = [dict(round=0, mode="plain", success=False, elapsedMs=100, videoValid=None),
                   dict(round=0, mode="record", success=True, elapsedMs=120, videoValid=True)]
        self.assertEqual(1, summarize_samples(samples)["failedSamples"])
        self.assertIsNone(summarize_samples(samples)["pairedMedianDeltaMs"])

    def test_duplicate_round_mode_is_rejected(self):
        sample = dict(round=0, mode="plain", success=True, elapsedMs=100, videoValid=None)
        with self.assertRaises(ValueError):
            summarize_samples([sample, sample])

    def test_phase_arithmetic_preserves_total_elapsed(self):
        phases = phase_durations(1_000_000, 4_000_000, 11_000_000, 16_000_000)
        self.assertEqual({"startupMs": 3, "executionMs": 7, "finalizationMs": 5}, phases)
        self.assertEqual(15, sum(phases.values()))

    def test_out_of_order_phase_timestamps_are_rejected(self):
        with self.assertRaises(ValueError):
            phase_durations(2_000_000, 1_000_000, 4_000_000, 5_000_000)

    def test_summary_does_not_mutate_input(self):
        samples = [dict(round=0, mode="plain", success=True, elapsedMs=100, videoValid=None)]
        before = [sample.copy() for sample in samples]
        summarize_samples(samples)
        self.assertEqual(before, samples)

    def test_runner_rejects_invalid_round_count_without_device_access(self):
        with self.assertRaises(ValueError):
            run_probe("ios", "unused", "missing.jar", "unused-output", rounds=0)

    def test_trace_option_precedes_flow_subcommand(self):
        self.assertEqual(["java", "-jar", "source.jar", "--trace-performance", "trace.jsonl",
                          "flow", "-p", "ios", "--record"],
                         traced_flow_command(["java", "-jar", "source.jar", "flow", "-p", "ios"],
                                             "trace.jsonl", record=True))

    def test_android_fixture_uses_accessibility_descriptions_not_short_resource_ids(self):
        self.assertEqual(["--content-description", "fixture_ready"], fixture_selector("android", "fixture_ready"))
        self.assertEqual(["--target-content-description", "email"], fixture_selector("android", "email", target=True))
        self.assertEqual(["--resource-id", "done", "--bundle-id", "com.example.visiontest.benchmark"],
                         fixture_selector("ios", "done"))

    def test_android_flow_dismisses_keyboard_before_tapping_continue(self):
        self.assertEqual("press_back", fixture_steps("android")[1][0])
        self.assertEqual("tap_on_element", fixture_steps("android")[2][0])
        self.assertEqual(3, len(fixture_steps("ios")))


if __name__ == "__main__":
    unittest.main()
